import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import { z } from "zod";
import { Store } from "./store";

/** Trusted-proxy mode is opt-in; an arbitrary X-Forwarded-For cannot bypass limits. */
export function createApp(store: Store, options: { address?: (request: Request) => string; notify?: (id: string) => void; revoke?: (id: string) => void } = {}) {
  const app = new Hono();
  const buckets = new Map<string, { at: number; count: number }>();
  app.use("*", async (c, next) => {
    c.header("Cache-Control", "no-store");
    c.header("X-Content-Type-Options", "nosniff");
    const origin = c.req.header("Origin");
    // The platform itself cannot call the relay. Only extension pages or native clients.
    if (origin && !/^chrome-extension:\/\/[a-p]{32}$/.test(origin)) return c.json({ error: "origin_not_allowed" }, 403);
    if (origin) { c.header("Access-Control-Allow-Origin", origin); c.header("Vary", "Origin"); }
    if (c.req.method === "OPTIONS") {
      c.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
      c.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
      return c.body(null, 204);
    }
    await next();
  });
  app.use("/v1/*", bodyLimit({ maxSize: 2048, onError: c => c.json({ error: "body_too_large" }, 413) }));
  app.use("/v1/pairings/*", async (c, next) => {
    const now = store.now();
    for (const [key, bucket] of buckets) if (bucket.at + 60_000 <= now) buckets.delete(key);
    const key = options.address?.(c.req.raw) || "unknown";
    const bucket = buckets.get(key) || { at: now, count: 0 };
    bucket.count++;
    buckets.set(key, bucket);
    if (bucket.count > 10 || buckets.size > 10_000) { c.header("Retry-After", "60"); return c.json({ error: "rate_limited" }, 429); }
    await next();
  });
  app.get("/health", c => c.json({ ok: true, service: "noti-assaad-relay", protocol: 1 }));
  app.post("/v1/pairings/create", c => {
    try { return c.json(store.create(), 201); } catch { return c.json({ error: "try_again" }, 503); }
  });
  app.post("/v1/pairings/join", async c => {
    const input = z.object({ code: z.string().regex(/^\d{6}$/) }).strict().safeParse(await c.req.json().catch(() => null));
    if (!input.success) return c.json({ error: "invalid_code" }, 400);
    const joined = store.join(input.data.code);
    if (!joined) return c.json({ error: "expired_or_used_code" }, 400);
    options.notify?.(joined.sessionId);
    return c.json(joined);
  });
  app.get("/v1/session", c => {
    const auth = store.auth(c.req.header("Authorization")?.replace(/^Bearer /, "") || "");
    if (!auth) return c.json({ error: "unauthorized" }, 401);
    return c.json({ sessionId: auth.room.id, role: auth.role, paired: !!auth.room.browser_hash, expiresAt: auth.room.expires_at });
  });
  app.delete("/v1/session", c => {
    const auth = store.auth(c.req.header("Authorization")?.replace(/^Bearer /, "") || "");
    if (!auth) return c.json({ error: "unauthorized" }, 401);
    store.remove(auth.room.id);
    options.revoke?.(auth.room.id);
    return c.json({ ok: true });
  });
  app.notFound(c => c.json({ error: "not_found" }, 404));
  app.onError((_error, c) => c.json({ error: "server_error" }, 500));
  return app;
}
