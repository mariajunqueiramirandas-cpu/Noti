export const SERVER = "https://noti.vendua.com.br";
/** MV3 service workers stay alive through WebSocket traffic (Chrome/Edge 116+). */
export class RelayClient {
  constructor({ token, onMessage, onState, WebSocketClass = WebSocket, base = SERVER }) {
    Object.assign(this, { token, onMessage, onState, WebSocketClass, base });
    this.stopped = false; this.pending = new Map(); this.backoff = 1000;
  }
  connect() {
    if (this.stopped || this.socket?.readyState <= 1) return;
    this.onState("connecting");
    const socket = this.socket = new this.WebSocketClass(this.base.replace(/^http/, "ws") + "/v1/stream");
    socket.onopen = () => {
      socket.send(JSON.stringify({ type: "authenticate", token: this.token }));
      this.heartbeat = setInterval(() => this.send("ping"), 20_000);
    };
    socket.onmessage = event => {
      if (this.socket !== socket) return;
      let message; try { message = JSON.parse(event.data); } catch { return; }
      if (message.type === "session") { this.backoff = 1000; this.onState("connected", message.payload); }
      if (message.type === "accepted") this.pending.get(message.payload.batchId)?.resolve(message.payload);
      this.onMessage(message);
    };
    socket.onerror = () => socket.close();
    socket.onclose = event => {
      if (this.socket !== socket) return;
      clearInterval(this.heartbeat);
      for (const pending of this.pending.values()) pending.reject(new Error("connection_lost"));
      this.pending.clear();
      if (this.stopped) return;
      if (event.code === 4001) { this.onState("expired"); this.stop(); return; }
      this.onState("disconnected");
      this.retry = setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(30_000, this.backoff * 2);
    };
  }
  send(type, payload) {
    if (this.socket?.readyState !== 1) return false;
    this.socket.send(JSON.stringify(payload === undefined ? { type } : { type, payload })); return true;
  }
  publish(batch) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(batch.batchId); reject(new Error("ack_timeout")); }, 8000);
      const finish = callback => value => { clearTimeout(timer); this.pending.delete(batch.batchId); callback(value); };
      this.pending.set(batch.batchId, { resolve: finish(resolve), reject: finish(reject) });
      if (!this.send("questions", batch)) this.pending.get(batch.batchId).reject(new Error("not_connected"));
    });
  }
  stop() {
    this.stopped = true; clearTimeout(this.retry); clearInterval(this.heartbeat);
    for (const pending of this.pending.values()) pending.reject(new Error("disconnected"));
    this.pending.clear(); this.socket?.close();
  }
}
