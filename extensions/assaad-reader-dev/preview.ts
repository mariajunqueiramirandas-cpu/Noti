// Local visual QA only. This stub never reaches Assaad or the deployed relay.
import { join } from "node:path";
const directory = join(import.meta.dir, "../assaad-reader");
const stub = `let paired=false;window.chrome={runtime:{sendMessage:async m=>{if(m.type==='NOTI_PAIR')paired=true;if(m.type==='NOTI_DISCONNECT')paired=false;return{paired,connection:'connected',peerOnline:true}}}};`;
const server = Bun.serve({ hostname: "127.0.0.1", port: 4187, async fetch(request) {
  const path = new URL(request.url).pathname;
  if (path === "/preview-stub.js") return new Response(stub, { headers: { "Content-Type": "application/javascript" } });
  if (path === "/" || path === "/popup.html") {
    const html = await Bun.file(join(directory, "popup.html")).text();
    return new Response(html.replace('<script type="module"', '<script src="/preview-stub.js"></script><script type="module"'), { headers: { "Content-Type": "text/html" } });
  }
  if (!/^\/(popup\.(js|css))$/.test(path)) return new Response("Not found", { status: 404 });
  return new Response(Bun.file(join(directory, path.slice(1))));
} });
console.info(`Pairing preview: http://127.0.0.1:${server.port}`);
