// POST /api/hit {kind, visitor} — сайт отмечает просмотр страницы или нажатие «Скачать».
// IP и cookies не сохраняются: visitor — случайный id, который браузер придумал сам.
const KINDS = new Set(["view", "apk", "pc"]);
// Больше стольких событий одного вида от одного посетителя за день не считаем.
const DAILY_CAP = 20;

export async function onRequestPost({ request, env }) {
  let hit;
  try {
    hit = JSON.parse(await request.text());
  } catch (_) {
    return new Response(null, { status: 400 });
  }
  if (!KINDS.has(hit?.kind) || !/^[0-9a-f]{32}$/.test(hit?.visitor || "")) {
    return new Response(null, { status: 400 });
  }
  const day = new Date().toISOString().slice(0, 10);
  await env.DB.prepare(
    `INSERT INTO hits (day, kind, visitor) VALUES (?1, ?2, ?3)
     ON CONFLICT (day, kind, visitor) DO UPDATE SET n = MIN(n + 1, ${DAILY_CAP})`
  ).bind(day, hit.kind, hit.visitor).run();
  return new Response(null, { status: 204 });
}
