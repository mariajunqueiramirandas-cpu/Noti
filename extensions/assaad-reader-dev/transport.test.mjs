import test from "node:test";
import assert from "node:assert/strict";
import { RelayClient } from "../assaad-reader/transport.js";
const flush = () => new Promise(resolve => setImmediate(resolve));
function setup() {
  const sockets = [], states = [];
  class Socket {
    readyState = 0; sent = [];
    constructor() { sockets.push(this); }
    send(value) { this.sent.push(JSON.parse(value)); }
    open() { this.readyState = 1; this.onopen(); }
    message(type, payload) { this.onmessage({ data: JSON.stringify({ type, payload }) }); }
    close(code = 1000) { this.readyState = 3; this.onclose?.({ code }); }
  }
  const client = new RelayClient({ token: "NOTI_TEST_TOKEN", WebSocketClass: Socket, onState: state => states.push(state), onMessage() {} });
  client.connect();
  return { client, sockets, states, authenticated() { const socket = sockets.at(-1); socket.open(); socket.message("session", { paired: true, peerOnline: true }); return socket; } };
}
test("question publication waits for authenticated session, not just an open socket", async () => {
  const app = setup();
  try {
    const pending = app.client.publish({ batchId: "saved", questions: [] });
    app.sockets[0].open(); await flush();
    assert.deepEqual(app.sockets[0].sent.map(m => m.type), ["authenticate"]);
    app.sockets[0].message("session", { peerOnline: true }); await flush();
    assert.equal(app.sockets[0].sent.at(-1).type, "questions");
    app.sockets[0].message("accepted", { batchId: "saved", count: 1 });
    assert.equal((await pending).count, 1);
  } finally { app.client.stop(); }
});
test("a reconnect safely replays the same unacknowledged snapshot and no answer", async () => {
  const app = setup();
  try {
    const first = app.authenticated();
    const pending = app.client.publish({ batchId: "saved", questions: [] }); await flush();
    first.close(1006); app.client.connect(); const next = app.authenticated(); await flush();
    assert.deepEqual(next.sent.map(m => m.type), ["authenticate", "questions"]);
    assert.equal(next.sent[1].payload.batchId, "saved");
    next.message("accepted", { batchId: "saved", count: 2 }); assert.equal((await pending).count, 2);
  } finally { app.client.stop(); }
});
test("server rejection reports its actual cause instead of an acknowledgement timeout", async () => {
  const app = setup();
  try {
    const socket = app.authenticated();
    const pending = app.client.publish({ batchId: "saved", questions: [] }); await flush();
    socket.message("error", { error: "library_full_or_conflict", batchId: "saved" });
    await assert.rejects(pending, /library_full_or_conflict/); assert.equal(app.client.pending.size, 0);
  } finally { app.client.stop(); }
});
test("replaced browser connections stop competing and require an explicit reconnect", async () => {
  const app = setup(); app.authenticated().close(4000);
  assert.equal(app.states.at(-1), "replaced"); assert.equal(app.client.stopped, true);
  app.client.connect(); assert.equal(app.sockets.length, 1);
  await assert.rejects(app.client.waitUntilConnected(), /connection_replaced/);
});
test("connection waits time out without claiming the browser is ready", async () => {
  const app = setup();
  try { await assert.rejects(app.client.waitUntilConnected(5), /not_connected/); assert.equal(app.client.authenticated, false); }
  finally { app.client.stop(); }
});
