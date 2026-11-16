import type { ServerWebSocket } from "bun";
import { clientMessage, type Presence, type Role } from "./protocol";
import { COMMAND_TTL, Store } from "./store";

export type SocketData = { sessionId?: string; role?: Role; timer?: ReturnType<typeof setTimeout>; pendingAuth?: boolean; window?: number; count?: number };
type Socket = ServerWebSocket<SocketData>;
export class Relay {
  private peers = new Map<string, Map<Role, Socket>>();
  private presence = new Map<string, Presence & { at: number }>();
  private unauthenticated = 0;
  constructor(readonly store: Store) {}
  send(socket: Socket, type: string, payload: unknown) { socket.send(JSON.stringify({ type, payload })); }
  peer(id: string, role: Role) { return this.peers.get(id)?.get(role); }
  notify = (id: string) => {
    const room = this.store.room(id);
    if (!room) return;
    const current = this.presence.get(id);
    const presence = current && current.at + 45_000 > this.store.now() && this.peer(id, "browser") ? current : { active: false, path: null };
    for (const [role, socket] of this.peers.get(id) || []) this.send(socket, "session", {
      sessionId: id, paired: !!room.browser_hash, peerOnline: !!this.peer(id, role === "app" ? "browser" : "app"), presence,
      expiresAt: room.expires_at
    });
  };
  revoke = (id: string) => {
    const sockets = [...(this.peers.get(id)?.values() || [])];
    this.peers.delete(id); this.presence.delete(id);
    for (const socket of sockets) socket.close(4001, "Session ended");
  };
  open(socket: Socket) {
    socket.data.pendingAuth = true;
    if (++this.unauthenticated > 200) { socket.close(4008, "Capacity reached"); return; }
    socket.data.timer = setTimeout(() => socket.close(4001, "Authentication required"), 5000);
  }
  close(socket: Socket) {
    clearTimeout(socket.data.timer);
    if (socket.data.pendingAuth) { this.unauthenticated--; socket.data.pendingAuth = false; }
    const { sessionId, role } = socket.data;
    if (!sessionId || !role || this.peer(sessionId, role) !== socket) return;
    this.peers.get(sessionId)?.delete(role);
    if (!this.peers.get(sessionId)?.size) this.peers.delete(sessionId);
    if (role === "browser") this.presence.delete(sessionId);
    this.notify(sessionId);
  }
  message(socket: Socket, raw: string | Buffer) {
    if (!socket.data.window || socket.data.window + 60_000 <= this.store.now()) { socket.data.window = this.store.now(); socket.data.count = 0; }
    if ((socket.data.count = (socket.data.count || 0) + 1) > 240) { socket.close(4008, "Rate limited"); return; }
    let input;
    try { input = JSON.parse(String(raw)); } catch { socket.close(4002, "Invalid JSON"); return; }
    if (!socket.data.sessionId) {
      if (input?.type !== "authenticate" || typeof input.token !== "string") { socket.close(4001, "Authentication required"); return; }
      const auth = this.store.auth(input.token);
      if (!auth) { socket.close(4001, "Invalid session"); return; }
      clearTimeout(socket.data.timer);
      if (socket.data.pendingAuth) { this.unauthenticated--; socket.data.pendingAuth = false; }
      const peers = this.peers.get(auth.room.id) || new Map<Role, Socket>();
      const previous = peers.get(auth.role);
      socket.data = { sessionId: auth.room.id, role: auth.role };
      peers.set(auth.role, socket); this.peers.set(auth.room.id, peers);
      previous?.close(4000, "Replaced connection");
      this.notify(auth.room.id);
      if (auth.role === "app") {
        const batch = this.store.batch(auth.room.id);
        if (batch) this.send(socket, "questions", batch);
        for (const result of this.store.results(auth.room.id)) this.send(socket, "answer-result", result);
      } else for (const command of this.store.pending(auth.room.id)) this.send(socket, "answer", command);
      return;
    }
    const { sessionId, role } = socket.data;
    if (!this.store.room(sessionId)) { this.revoke(sessionId); return; }
    const parsed = clientMessage.safeParse(input);
    if (!parsed.success) { this.send(socket, "error", { error: "invalid_message" }); return; }
    const message = parsed.data;
    if (message.type === "ping") { this.send(socket, "pong", {}); this.notify(sessionId); return; }
    const allowed = role === "browser" ? ["presence", "questions", "answer-result"] : ["answer"];
    if (!allowed.includes(message.type)) { this.send(socket, "error", { error: "wrong_role" }); return; }
    if (message.type === "presence") {
      this.presence.set(sessionId, { ...message.payload, at: this.store.now() }); this.notify(sessionId);
    } else if (message.type === "questions") {
      this.store.saveBatch(sessionId, message.payload);
      const app = this.peer(sessionId, "app");
      if (app) this.send(app, "questions", message.payload);
      this.send(socket, "accepted", { batchId: message.payload.batchId, count: message.payload.questions.length });
    } else if (message.type === "answer") {
      const value = message.payload;
      const existing = this.store.command(sessionId, value.commandId);
      if (existing) {
        if (existing.payload !== JSON.stringify(value)) { this.send(socket, "error", { error: "command_conflict", commandId: value.commandId }); return; }
        if (existing.result) this.send(socket, "answer-result", this.store.result(sessionId, value.commandId));
        else if (existing.created_at + COMMAND_TTL <= this.store.now()) this.send(socket, "error", { error: "command_expired", commandId: value.commandId });
        return;
      }
      const current = this.store.batch(sessionId);
      const question = current?.questions.find(q => q.id === value.questionId);
      const browser = this.peer(sessionId, "browser");
      const active = this.presence.get(sessionId);
      if (!browser || !active?.active || active.at + 45_000 <= this.store.now()) {
        this.send(socket, "error", { error: "browser_offline", commandId: value.commandId }); return;
      }
      if (current?.batchId !== value.batchId || !question?.alternatives.some(a => a.id === value.alternativeId)) {
        this.send(socket, "error", { error: "stale_question", commandId: value.commandId }); return;
      }
      if (this.store.pending(sessionId).length >= 100 || this.store.saveCommand(sessionId, value) !== "new") {
        this.send(socket, "error", { error: "command_conflict", commandId: value.commandId }); return;
      }
      this.send(browser, "answer", value);
    } else if (message.type === "answer-result") {
      if (!this.store.saveResult(sessionId, message.payload)) return;
      const app = this.peer(sessionId, "app");
      if (app) this.send(app, "answer-result", this.store.result(sessionId, message.payload.commandId));
    }
  }
}
