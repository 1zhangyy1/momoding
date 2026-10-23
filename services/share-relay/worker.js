// Momoding share relay — a Cloudflare Worker that turns a know-how skill sent by the app into a
// pull request on github.com/1zhangyy1/momoding-skills (调研/M21). The GitHub token lives here, as
// a Worker secret, never in the app. Every submission is checked and lands as a PR for review;
// nothing is merged automatically.
//
// POST /v1/share  {"name": "cn-train-flight-search", "skill": "---\nname: ...", "install": "<random id>", "app": "1.0.3"}
//   → 200 {"pr": "https://github.com/..."}  |  4xx {"error": "..."}
// GET  /v1/health → 200 {"ok": true}
//
// Bindings (wrangler.toml): REPO ("owner/name"), KV namespace LIMITS; secret GITHUB_TOKEN
// (fine-grained: contents + pull requests, read/write, this repo only).

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

    try {
      const pr = await openPullRequest(env, name, skill, String(body.app || "").slice(0, 20));
      return json({ pr });
    } catch (e) {
      return json({ error: "could not open the pull request", detail: String(e.message || e).slice(0, 200) }, 502);
    }
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

async function gh(env, path, init = {}) {
  const res = await fetch(`https://api.github.com/repos/${env.REPO}${path}`, {
    ...init,
    headers: {
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      accept: "application/vnd.github+json",
      "user-agent": "momoding-share-relay",
      ...(init.body ? { "content-type": "application/json" } : {}),
    },
  });
  const data = res.status === 204 ? {} : await res.json();
  if (!res.ok) throw new Error(`${res.status} ${data.message || ""}`);
  return data;
}

async function openPullRequest(env, name, skill, app) {
  const main = await gh(env, "/git/ref/heads/main");
  const branch = `share/${name}-${Date.now().toString(36)}`;
  await gh(env, "/git/refs", { method: "POST", body: JSON.stringify({ ref: `refs/heads/${branch}`, sha: main.object.sha }) });
  const path = `skills/${name}/SKILL.md`;
  let sha;
  try { sha = (await gh(env, `/contents/${path}?ref=${branch}`)).sha; } catch { /* a new skill */ }
  await gh(env, `/contents/${path}`, {
    method: "PUT",
    body: JSON.stringify({ message: `${sha ? "Update" : "Add"} ${name} (shared from the app)`, content: b64(skill), branch, ...(sha ? { sha } : {}) }),
  });
  const pr = await gh(env, "/pulls", {
    method: "POST",
    body: JSON.stringify({
      title: `${sha ? "Update" : "Add"} ${name}`,
      head: branch,
      base: "main",
      body: `Shared automatically by a Momoding app${app ? ` (${app})` : ""}. Checked by the relay for format and obvious personal data; please review before merging.`,
    }),
  });
  return pr.html_url;
}

function b64(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}
