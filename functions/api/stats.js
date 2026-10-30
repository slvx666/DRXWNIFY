// GET /api/stats — цифры для страницы /stats: за всё время, за сегодня и по дням за 30 дней.
const COLUMNS = `
  COUNT(DISTINCT CASE WHEN kind = 'view' THEN visitor END) AS visitors,
  COALESCE(SUM(CASE WHEN kind = 'view' THEN n END), 0)     AS views,
  COUNT(DISTINCT CASE WHEN kind = 'apk' THEN visitor END)  AS apkVisitors,
  COALESCE(SUM(CASE WHEN kind = 'apk' THEN n END), 0)      AS apk,
  COALESCE(SUM(CASE WHEN kind = 'pc' THEN n END), 0)       AS pc`;

const DAYS = 30;

export async function onRequestGet({ env }) {
  const today = new Date().toISOString().slice(0, 10);
  const since = new Date(Date.now() - (DAYS - 1) * 86400000).toISOString().slice(0, 10);
  const [total, todayRow, days] = await env.DB.batch([
    env.DB.prepare(`SELECT ${COLUMNS} FROM hits`),
    env.DB.prepare(`SELECT ${COLUMNS} FROM hits WHERE day = ?1`).bind(today),
    env.DB.prepare(`SELECT day, ${COLUMNS} FROM hits WHERE day >= ?1 GROUP BY day ORDER BY day`).bind(since),
  ]);
  return Response.json(
    { total: total.results[0], today: todayRow.results[0], days: days.results, since, until: today },
    { headers: { "Cache-Control": "public, max-age=60" } }
  );
}
