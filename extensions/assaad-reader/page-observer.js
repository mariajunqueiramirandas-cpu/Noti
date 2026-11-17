/**
 * Runs in Assaad's MAIN world at document start. It only remembers what the website
 * itself already fetched (its login session, refreshed or read, and its question searches), so Noti can
 * reuse those instead of asking Assaad again. It never makes a request, never changes a
 * request or a response, and keeps nothing after the page closes.
 */
(() => {
  if (globalThis.__notiAssaad) return;
  const origin = "https://app.plataformaassaad.com.br";
  const api = "https://api.plataformaassaad.com.br/v1/";
  const keep = 10 * 60_000;
  let session = null, apiOkAt = 0;
  const searches = [];
  function remember(url, method, status, body) {
    try {
      const target = new URL(url, origin);
      if (status < 200 || status >= 300) return;
      const verb = (method || "GET").toUpperCase();
      const login = target.origin === origin && (verb === "GET" && target.pathname === "/api/auth/session" || verb === "POST" && target.pathname === "/api/auth/refresh");
      if (verb !== "GET" && !login) return;
      if (login) {
        const value = JSON.parse(body)?.session;
        if (value?.access_token) session = { value, at: Date.now() };
      } else if (target.href.startsWith(api)) {
        apiOkAt = Date.now();
        if (target.pathname === "/v1/question") {
          const data = JSON.parse(body)?.data;
          if (Array.isArray(data)) { searches.unshift({ at: Date.now(), data }); searches.length = Math.min(searches.length, 8); }
        }
      }
    } catch { /* Not something to remember. */ }
  }
  const fresh = entry => entry && Date.now() - entry.at < keep;
  Object.defineProperty(globalThis, "__notiAssaad", { configurable: false, enumerable: false, writable: false, value: Object.freeze({
    /** The website's own current login session, or null. */
    session: () => fresh(session) ? session.value : null,
    /** The website reached its API successfully in the last few minutes. */
    apiReachable: () => Date.now() - apiOkAt < keep,
    /** A question the website loaded recently, as the API returned it, or null. */
    question: id => { for (const s of searches) if (fresh(s)) { const q = s.data.find(q => q?.id === id); if (q) return q; } return null; },
    /** Forget the session (after Assaad refused it). */
    forgetSession: () => { session = null; },
  }) });

  const fetch = globalThis.fetch;
  if (typeof fetch === "function") globalThis.fetch = function (input, init) {
    const url = typeof input === "string" ? input : input?.url ?? (input ? String(input) : "");
    const method = init?.method || (typeof input === "object" && input?.method) || "GET";
    const pending = fetch.apply(this, arguments);
    pending.then(response => {
      if (!url || !(/\/api\/auth\/(session|refresh)/.test(String(url)) || String(response.url || url).startsWith(api))) return;
      response.clone().text().then(body => remember(response.url || url, method, response.status, body), () => {});
    }, () => {});
    return pending;
  };
  const XHR = globalThis.XMLHttpRequest;
  if (typeof XHR === "function") {
    const open = XHR.prototype.open;
    XHR.prototype.open = function (method, url) {
      this.addEventListener("load", () => {
        try {
          if (this.responseType && this.responseType !== "text" && this.responseType !== "json") return;
          const body = this.responseType === "json" ? JSON.stringify(this.response) : this.responseText;
          remember(this.responseURL || url, method, this.status, body);
        } catch { /* Not something to remember. */ }
      });
      return open.apply(this, arguments);
    };
  }
})();
