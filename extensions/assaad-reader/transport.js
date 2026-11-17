export const SERVER = "https://noti.vendua.com.br";
/** MV3 service workers stay alive through WebSocket traffic (Chrome/Edge 116+). */
export class RelayClient {
  constructor({ token, onMessage, onState, WebSocketClass = WebSocket, base = SERVER }) {
    Object.assign(this, { token, onMessage, onState, WebSocketClass, base });
    this.stopped = false; this.authenticated = false; this.pending = new Map(); this.waiters = new Set(); this.backoff = 1000;
  }
  connect() {
    if (this.stopped || this.socket?.readyState <= 1) return;
    clearTimeout(this.retry); this.authenticated = false;
    this.onState("connecting");
    const socket = this.socket = new this.WebSocketClass(this.base.replace(/^http/, "ws") + "/v1/stream");
    socket.onopen = () => {
      if (this.socket !== socket || this.stopped) { socket.close(); return; }
      socket.send(JSON.stringify({ type: "authenticate", token: this.token }));
      this.heartbeat = setInterval(() => this.send("ping"), 20_000);
    };
    socket.onmessage = event => {
      if (this.socket !== socket) return;
      let message; try { message = JSON.parse(event.data); } catch { return; }
      if (message.type === "session") {
        const reconnected = !this.authenticated;
        this.authenticated = true; this.backoff = 1000; this.onState("connected", message.payload);
        if (reconnected) {
          // Only question snapshots are safe to replay. Answer commands are never replayed here.
          for (const pending of this.pending.values()) this.send("questions", pending.batch);
          for (const waiter of [...this.waiters]) waiter.resolve();
        }
      }
      if (message.type === "accepted") this.pending.get(message.payload.batchId)?.resolve(message.payload);
      if (message.type === "error") {
        const error = new Error(message.payload.error || "publish_failed");
        if (message.payload.batchId) this.pending.get(message.payload.batchId)?.reject(error);
        else if (!message.payload.commandId) for (const pending of [...this.pending.values()]) pending.reject(error);
      }
      this.onMessage(message);
    };
    socket.onerror = () => socket.close();
    socket.onclose = event => {
      if (this.socket !== socket) return;
      clearInterval(this.heartbeat); this.authenticated = false;
      if (this.stopped) return;
      if (event.code === 4001) { this.onState("expired"); this.stop("session_expired"); return; }
      if (event.code === 4000) { this.onState("replaced"); this.stop("connection_replaced"); return; }
      this.onState("disconnected");
      this.retry = setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(30_000, this.backoff * 2);
    };
  }
  send(type, payload) {
    if (this.socket?.readyState !== 1) return false;
    this.socket.send(JSON.stringify(payload === undefined ? { type } : { type, payload })); return true;
  }
  waitUntilConnected(timeoutMs = 10_000) {
    if (this.stopped) return Promise.reject(new Error(this.stopReason || "disconnected"));
    if (this.authenticated && this.socket?.readyState === 1) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const finish = callback => value => { clearTimeout(timer); this.waiters.delete(waiter); callback(value); };
      const waiter = { resolve: finish(resolve), reject: finish(reject) };
      const timer = setTimeout(() => waiter.reject(new Error("not_connected")), timeoutMs);
      this.waiters.add(waiter); this.connect();
    });
  }
  async publish(batch) {
    await this.waitUntilConnected();
    if (this.pending.has(batch.batchId)) return this.pending.get(batch.batchId).promise;
    let accept, fail;
    const promise = new Promise((resolve, reject) => { accept = resolve; fail = reject; });
    const timer = setTimeout(() => this.pending.get(batch.batchId)?.reject(new Error("ack_timeout")), 15_000);
    const finish = callback => value => { clearTimeout(timer); this.pending.delete(batch.batchId); callback(value); };
    this.pending.set(batch.batchId, { batch, promise, resolve: finish(accept), reject: finish(fail) });
    if (!this.send("questions", batch)) this.connect();
    return promise;
  }
  stop(reason = "disconnected") {
    this.stopped = true; this.authenticated = false; this.stopReason = reason; clearTimeout(this.retry); clearInterval(this.heartbeat);
    for (const waiter of [...this.waiters]) waiter.reject(new Error(reason));
    for (const pending of [...this.pending.values()]) pending.reject(new Error(reason));
    this.pending.clear(); this.socket?.close();
  }
}
