// GET /api/online — сколько человек слушают музыку в приложении прямо сейчас.
//
// Приложение (friends/FriendsHub.kt) публикует «что играет» в Nostr событием kind 30078 с тегом
// d = "drxw:now" (всем) или "drxw:now:<друг>" (зашифровано для друга) при каждой смене трека,
// паузе или перемотке. Считаем разных авторов таких событий за последние минуты. Префикс тега
// фильтром relay не задать, поэтому берём все kind 30078 за это окно и отбираем сами.
// Ответ кешируется на минуту: relay опрашиваются не чаще раза в минуту, сколько бы ни заходило людей.
const RELAYS = [
  "wss://relay.damus.io",
  "wss://nos.lol",
  "wss://relay.primal.net",
  "wss://nostr.mom",
  "wss://relay.snort.social",
];
const KIND_APP_DATA = 30078;
const WINDOW_SEC = 10 * 60;
const RELAY_TIMEOUT_MS = 4000;
const CACHE_SEC = 60;

/** Авторы событий «что играет» на одном relay; при ошибке или таймауте — то, что успели получить. */
function listenersOn(url, since) {
  const found = new Set();
  // Подключение к недоступному relay может висеть долго: ждём не дольше таймаута.
  const deadline = new Promise((resolve) => setTimeout(resolve, RELAY_TIMEOUT_MS + 500));
  return Promise.race([ask(url, since, found), deadline]).then(() => found);
}

async function ask(url, since, found) {
  let ws;
  try {
    const res = await fetch(url.replace(/^wss:/, "https:"), { headers: { Upgrade: "websocket" } });
    ws = res.webSocket;
    if (!ws) return found;
    ws.accept();
  } catch (_) {
    return found;
  }
  await new Promise((resolve) => {
    const timer = setTimeout(resolve, RELAY_TIMEOUT_MS);
    const finish = () => { clearTimeout(timer); resolve(); };
    ws.addEventListener("message", (m) => {
      let msg;
      try { msg = JSON.parse(m.data); } catch (_) { return; }
      if (msg[0] === "EOSE" || msg[0] === "CLOSED") return finish();
      if (msg[0] !== "EVENT") return;
      const event = msg[2];
      const d = event.tags?.find((t) => t[0] === "d")?.[1] || "";
      if (d !== "drxw:now" && !d.startsWith("drxw:now:")) return;
      // Пустое содержимое — человек скрыл «что играет»; открытое с playing:false — пауза.
      if (!event.content) return;
      if (d === "drxw:now") {
        try { if (JSON.parse(event.content).playing === false) return; } catch (_) { return; }
      }
      found.add(event.pubkey);
    });
    ws.addEventListener("close", finish);
    ws.addEventListener("error", finish);
    ws.send(JSON.stringify(["REQ", "online", { kinds: [KIND_APP_DATA], since, limit: 2000 }]));
  });
  try { ws.close(); } catch (_) { /* уже закрыт */ }
  return found;
}

export async function onRequestGet({ request, waitUntil }) {
  const cache = caches.default;
  const key = new Request(new URL("/api/online", request.url).toString());
  const cached = await cache.match(key);
  if (cached) return cached;

  const since = Math.floor(Date.now() / 1000) - WINDOW_SEC;
  const sets = await Promise.all(RELAYS.map((url) => listenersOn(url, since)));
  const listeners = new Set(sets.flatMap((s) => [...s]));
  const response = Response.json(
    { listening: listeners.size, windowMin: WINDOW_SEC / 60 },
    { headers: { "Cache-Control": `public, max-age=${CACHE_SEC}` } }
  );
  waitUntil(cache.put(key, response.clone()));
  return response;
}
