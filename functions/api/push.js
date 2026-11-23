// POST /api/push — будит телефон друга через Firebase Cloud Messaging.
//
// Чаты и общие плейлисты идут через Nostr, а за ним не следит ни один сервер. Поэтому приложение
// отправителя (app/.../push/Push.kt) просит эту функцию разбудить телефон получателя:
// { "token": "<push-адрес телефона>", "type": "chat" | "collab" }.
// Телефон просыпается и сам забирает с relay то, что пришло. Через эту функцию идёт только тип
// («chat» / «collab»), без текста, отправителя и треков.
//
// Ключ сервисного аккаунта Firebase лежит в секрете FCM_SERVICE_ACCOUNT проекта Pages (JSON целиком):
//   npx wrangler pages secret put FCM_SERVICE_ACCOUNT --project-name drxwnify < ключ.json
// Один и тот же телефон будится не чаще раза в WAKE_GAP_SEC: так ссылку не превратить в спам.
const TYPES = new Set(["chat", "collab"]);
const TOKEN_RE = /^[A-Za-z0-9:_\-]{20,400}$/;
const WAKE_GAP_SEC = 3;
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

// Токен доступа Google живёт час: держим его, пока экземпляр функции жив.
let access = null; // { token, until }

export async function onRequestPost({ request, env }) {
  if (!env.FCM_SERVICE_ACCOUNT) return text("push is not configured", 503);
  let body;
  try { body = await request.json(); } catch (_) { return text("bad json", 400); }
  const token = String(body?.token || "");
  const type = String(body?.type || "");
  if (!TOKEN_RE.test(token) || !TYPES.has(type)) return text("bad request", 400);

  // Не чаще раза в несколько секунд на один телефон (кэш дата-центра: этого хватает).
  const cache = caches.default;
  const gateKey = new Request("https://push-gate.invalid/" + (await sha256(type + "|" + token)));
  if (await cache.match(gateKey)) return text("too soon", 429);
  await cache.put(gateKey, new Response("1", { headers: { "Cache-Control": `max-age=${WAKE_GAP_SEC}` } }));

  let account;
  try { account = JSON.parse(env.FCM_SERVICE_ACCOUNT); } catch (_) { return text("bad service account", 500); }
  let bearer;
  try { bearer = await accessToken(account); } catch (e) { return text("google auth failed", 502); }

  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token,
        data: { type },
        // Высокий приоритет: телефон в Doze просыпается сразу. Пробуждения одного типа схлопываются.
        android: { priority: "high", ttl: "3600s", collapse_key: type },
      },
    }),
  });
  if (res.ok) return text("ok", 200);
  // Адреса больше нет (приложение удалено, токен сменился): 410, отправителю незачем пробовать снова.
  if (res.status === 404 || res.status === 400) {
    const detail = await res.text();
    if (detail.includes("UNREGISTERED") || detail.includes("INVALID_ARGUMENT")) return text("gone", 410);
  }
  return text("fcm " + res.status, 502);
}

async function accessToken(account) {
  const now = Math.floor(Date.now() / 1000);
  if (access && access.until > now + 60) return access.token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({ iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const jwt = `${header}.${claims}.${b64url(signature)}`;
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${jwt}`,
  });
  if (!res.ok) throw new Error("token " + res.status);
  const json = await res.json();
  access = { token: json.access_token, until: now + (json.expires_in || 3600) };
  return access.token;
}

function pemToDer(pem) {
  const base64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bytes = atob(base64);
  const out = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) out[i] = bytes.charCodeAt(i);
  return out.buffer;
}

function b64url(data) {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : new Uint8Array(data);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function text(body, status) {
  return new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}
