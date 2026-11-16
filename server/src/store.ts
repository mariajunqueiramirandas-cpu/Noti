import { Database } from "bun:sqlite";
import { createHash, randomBytes, randomInt } from "node:crypto";
import type { Answer, AnswerResult, Batch, Role } from "./protocol";

export const CODE_TTL = 5 * 60_000;
export const SESSION_TTL = 30 * 24 * 60 * 60_000;
export const COMMAND_TTL = 2 * 60_000;
const hash = (value: string) => createHash("sha256").update(value).digest("hex");
const token = () => randomBytes(32).toString("base64url");
type Room = { id: string; app_hash: string; browser_hash: string | null; code_hash: string | null; code_expires: number; expires_at: number; batch: string | null };

/** A single SQLite-backed relay instance. Tokens and one-use pairing codes are hashed. */
export class Store {
  readonly db: Database;
  constructor(path: string, readonly now = Date.now) {
    this.db = new Database(path, { create: true });
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;
      CREATE TABLE IF NOT EXISTS rooms (id TEXT PRIMARY KEY, app_hash TEXT UNIQUE NOT NULL, browser_hash TEXT UNIQUE,
        code_hash TEXT UNIQUE, code_expires INTEGER NOT NULL, expires_at INTEGER NOT NULL, batch TEXT);
      CREATE TABLE IF NOT EXISTS commands (id TEXT PRIMARY KEY, room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
        payload TEXT NOT NULL, result TEXT, created_at INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS commands_room ON commands(room_id);
      CREATE TABLE IF NOT EXISTS batches (room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
        id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(room_id,id));
      INSERT OR IGNORE INTO batches SELECT id, json_extract(batch, '$.batchId'), batch FROM rooms WHERE batch IS NOT NULL;`);
  }
  cleanup() {
    this.db.query("DELETE FROM rooms WHERE expires_at <= ?").run(this.now());
    this.db.query("UPDATE rooms SET code_hash = NULL WHERE code_expires <= ?").run(this.now());
    this.db.query("DELETE FROM commands WHERE created_at < ?").run(this.now() - 24 * 60 * 60_000);
  }
  create() {
    this.cleanup();
    if ((this.db.query("SELECT count(*) AS n FROM rooms").get() as { n: number }).n >= 2000) throw new Error("capacity");
    const appToken = token();
    const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
    const sessionId = crypto.randomUUID();
    this.db.query("INSERT INTO rooms VALUES (?, ?, NULL, ?, ?, ?, NULL)")
      .run(sessionId, hash(appToken), hash(code), this.now() + CODE_TTL, this.now() + SESSION_TTL);
    return { sessionId, token: appToken, code, expiresAt: this.now() + CODE_TTL, sessionExpiresAt: this.now() + SESSION_TTL };
  }
  join(code: string) {
    return this.db.transaction(() => {
      const room = this.db.query("SELECT * FROM rooms WHERE code_hash = ? AND code_expires > ? AND expires_at > ? AND browser_hash IS NULL")
        .get(hash(code), this.now(), this.now()) as Room | null;
      if (!room) return null;
      const browserToken = token();
      this.db.query("UPDATE rooms SET browser_hash = ?, code_hash = NULL WHERE id = ?").run(hash(browserToken), room.id);
      return { sessionId: room.id, token: browserToken, sessionExpiresAt: room.expires_at };
    })();
  }
  auth(value: string): { room: Room; role: Role } | null {
    if (!/^[A-Za-z0-9_-]{43}$/.test(value)) return null;
    const digest = hash(value);
    const room = this.db.query("SELECT * FROM rooms WHERE (app_hash = ? OR browser_hash = ?) AND expires_at > ?")
      .get(digest, digest, this.now()) as Room | null;
    return room ? { room, role: room.app_hash === digest ? "app" : "browser" } : null;
  }
  room(id: string) { return this.db.query("SELECT * FROM rooms WHERE id = ? AND expires_at > ?").get(id, this.now()) as Room | null; }
  remove(id: string) { this.db.query("DELETE FROM rooms WHERE id = ?").run(id); }
  saveBatch(roomId: string, value: Batch, latest = true) {
    const payload = JSON.stringify(value);
    const existing = this.db.query("SELECT payload FROM batches WHERE room_id=? AND id=?").get(roomId, value.batchId) as { payload: string } | null;
    if (existing && existing.payload !== payload) return false;
    const count = (this.db.query("SELECT count(*) AS n FROM batches WHERE room_id=?").get(roomId) as { n: number }).n;
    if (!existing && count >= 100) return false;
    this.db.query("INSERT OR IGNORE INTO batches VALUES(?,?,?)").run(roomId, value.batchId, payload);
    if (latest) this.db.query("UPDATE rooms SET batch = ? WHERE id = ?").run(payload, roomId);
    return true;
  }
  batch(roomId: string, batchId?: string): Batch | null {
    const value = batchId ? (this.db.query("SELECT payload FROM batches WHERE room_id=? AND id=?").get(roomId, batchId) as { payload: string } | null)?.payload : this.room(roomId)?.batch;
    return value ? JSON.parse(value) : null;
  }
  forgetBatch(roomId: string, id: string) {
    const pending = this.pending(roomId).some(command => command.batchId === id);
    if (pending) return false;
    this.db.query("DELETE FROM batches WHERE room_id=? AND id=?").run(roomId, id);
    const latest = this.batch(roomId);
    if (latest?.batchId === id) this.db.query("UPDATE rooms SET batch=NULL WHERE id=?").run(roomId);
    return true;
  }
  command(roomId: string, commandId: string) {
    return this.db.query("SELECT payload, result, created_at FROM commands WHERE id = ? AND room_id = ?").get(commandId, roomId) as
      { payload: string; result: string | null; created_at: number } | null;
  }
  saveCommand(roomId: string, value: Answer) {
    const existing = this.command(roomId, value.commandId);
    if (existing) return existing.payload === JSON.stringify(value) ? "duplicate" : "conflict";
    try { this.db.query("INSERT INTO commands VALUES (?, ?, ?, NULL, ?)").run(value.commandId, roomId, JSON.stringify(value), this.now()); }
    catch { return "conflict"; }
    return "new";
  }
  saveResult(roomId: string, value: AnswerResult) {
    const command = this.command(roomId, value.commandId);
    if (!command) return false;
    if (command.result) return false;
    this.db.query("UPDATE commands SET result = ? WHERE id = ? AND room_id = ? AND result IS NULL").run(JSON.stringify(value), value.commandId, roomId);
    return true;
  }
  results(roomId: string) {
    return (this.db.query("SELECT result, payload FROM commands WHERE room_id = ? AND result IS NOT NULL ORDER BY created_at DESC LIMIT 100")
      .all(roomId) as { result: string; payload: string }[]).map(row => ({ ...JSON.parse(row.payload), ...JSON.parse(row.result) }));
  }
  result(roomId: string, commandId: string) {
    const command = this.command(roomId, commandId);
    return command?.result ? { ...JSON.parse(command.payload), ...JSON.parse(command.result) } : null;
  }
  pending(roomId: string) {
    return (this.db.query("SELECT payload FROM commands WHERE room_id = ? AND result IS NULL AND created_at > ? ORDER BY created_at")
      .all(roomId, this.now() - COMMAND_TTL) as { payload: string }[]).map(row => JSON.parse(row.payload));
  }
}
