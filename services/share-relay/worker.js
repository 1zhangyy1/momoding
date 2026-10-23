// Momoding share relay — a Cloudflare Worker that receives the know-how skills Momoding apps share
// and keeps them for review (调研/M21). It holds no GitHub token: a maintainer pulls the pending
// shares with scripts/review-shares.sh, reviews each, and opens the pull requests with their own
// signed-in GitHub. Every submission is checked here first (format, personal data, rate).
//
// POST /v1/share  {"name": "cn-train-flight-search", "skill": "---\nname: ...", "install": "<random id>", "app": "1.0.3"}
//   → 200 {"id": "<share id>", "status": "received"}  |  4xx {"error": "..."}
// GET  /v1/health → 200 {"ok": true}
// GET  /v1/pending, DELETE /v1/pending/<id>  (Authorization: Bearer <REVIEW_KEY>) — for the review script
//
// Bindings (wrangler.toml): KV namespace LIMITS (rate limits and the pending shares); secret
// REVIEW_KEY (a random string the deploy script makes and keeps on the maintainer's Mac).

const MAX_CHARS = 6000;
const PER_INSTALL_PER_DAY = 5;
const PER_IP_PER_DAY = 20;
const NAME = /^[a-z0-9][a-z0-9-]{1,60}$/;

// what must never be in a shared skill: personal data and secrets
const PERSONAL = [
  [/(?<!\d)1[3-9]\d{9}(?!\d)/, "a phone number"],
  [/[\w.+-]+@[\w-]+\.[\w.]+/, "an email address"],
  [/(?<!\d)\d{17}[\dXx](?!\d)/, "an ID card number"],
  [/(?<!\d)\d{16,19}(?!\d)/, "a card or account number"],
  [/(?:password|passwd|密码|验证码是|token\s*[:=]|api[_-]?key)/i, "a secret"],
  [/https?:\/\/\S+[?&](?:uid|user|token|session|sid|auth|key)=/i, "a link carrying personal or session data"],
];

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health") return json({ ok: true });
    if (url.pathname.startsWith("/v1/pending")) return pending(request, env, url);
    if (request.method !== "POST" || url.pathname !== "/v1/share") return json({ error: "not found" }, 404);

    let body;
    try { body = await request.json(); } catch { return json({ error: "body must be JSON" }, 400); }
    const name = String(body.name || "").trim();
    const skill = String(body.skill || "").replace(/\r\n/g, "\n");
    const install = String(body.install || "").slice(0, 64);
    const problem = check(name, skill);
    if (problem) return json({ error: problem }, 422);

    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const day = new Date().toISOString().slice(0, 10);
    if (!(await allow(env, `ip:${ip}:${day}`, PER_IP_PER_DAY)) || (install && !(await allow(env, `in:${install}:${day}`, PER_INSTALL_PER_DAY)))) {
      return json({ error: "too many shares today" }, 429);
    }

    const id = `${Date.now().toString(36)}-${crypto.randomUUID().slice(0, 8)}`;
    await env.LIMITS.put(`share:${id}`, JSON.stringify({ name, skill, app: String(body.app || "").slice(0, 20), at: new Date().toISOString() }), { expirationTtl: 60 * 86400 });
    return json({ id, status: "received" });
  },
};

/** Why [skill] cannot be shared, or null. Exported for tests. */
export function check(name, skill) {
  if (!NAME.test(name)) return "name: lowercase letters, digits and dashes";
  if (skill.length > MAX_CHARS) return `skill longer than ${MAX_CHARS} characters`;
  const fm = skill.match(/^---\n([\s\S]*?)\n---\n/);
  if (!fm) return "skill must start with front matter (--- … ---)";
  for (const key of ["name", "description", "sites", "topics", "verified"]) {
    if (!new RegExp(`^${key}:\\s*\\S`, "m").test(fm[1])) return `front matter needs ${key}`;
  }
  if (!new RegExp(`^name:\\s*${name}\\s*$`, "m").test(fm[1])) return "front matter name must match";
  for (const [re, what] of PERSONAL) if (re.test(skill)) return `looks like it contains ${what}`;
  return null;
}

async function allow(env, key, limit) {
  if (!env.LIMITS) return true;
  const n = parseInt((await env.LIMITS.get(key)) || "0", 10);
  if (n >= limit) return false;
  await env.LIMITS.put(key, String(n + 1), { expirationTtl: 2 * 86400 });
  return true;
}

async function pending(request, env, url) {
  if (!env.REVIEW_KEY || request.headers.get("authorization") !== `Bearer ${env.REVIEW_KEY}`) return json({ error: "not allowed" }, 401);
  if (request.method === "GET") {
    const list = await env.LIMITS.list({ prefix: "share:" });
    const items = [];
    for (const k of list.keys) {
      const v = await env.LIMITS.get(k.name);
      if (v) items.push({ id: k.name.slice(6), ...JSON.parse(v) });
    }
    return json({ items });
  }
  if (request.method === "DELETE") {
    const id = url.pathname.split("/").pop();
    await env.LIMITS.delete(`share:${id}`);
    return json({ deleted: id });
  }
  return json({ error: "not found" }, 404);
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}
