/*
 * Drxwnify — сайт загрузки.
 *
 * Патч-ноуты берутся из releases.json (генерируется scripts/sync_site_changelog.py
 * из тех же строк, что показывает приложение). Ссылки на APK и размер — из GitHub
 * Releases; если GitHub недоступен, кнопка ведёт на /releases/latest/download.
 *
 * Приложение открывает сайт как  https://<домен>/?v=<текущая версия>  — тогда
 * наверху показывается плашка «доступна новая версия», а в истории отмечено,
 * что изменилось именно с версии пользователя.
 */
const CONFIG = {
  repo: "slvx666/DRXWNIFY",
  apkName: "Drxwnify.apk",
  initiallyShown: 4,
};

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

const ghReleasesPage = `https://github.com/${CONFIG.repo}/releases`;
const fallbackApkUrl = `${ghReleasesPage}/latest/download/${CONFIG.apkName}`;
const userVersion = (new URLSearchParams(location.search).get("v") || "").trim().replace(/^v/i, "") || null;

// Перезагрузка всегда открывает страницу сверху: браузер не восстанавливает
// прокрутку, а якоря разделов не остаются в адресе. Ссылки #v1.3.0 работают.
const releaseAnchor = /^#v\d/.test(location.hash) ? decodeURIComponent(location.hash.slice(1)) : null;
if ("scrollRestoration" in history) history.scrollRestoration = "manual";
if (location.hash && !releaseAnchor) history.replaceState(null, "", location.pathname + location.search);

/* ───────── Версии ───────── */
function compareVersions(a, b) {
  const pa = String(a).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);
  const pb = String(b).replace(/^v/i, "").split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d > 0 ? 1 : -1;
  }
  return 0;
}

function plural(n, one, few, many) {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

function formatDate(iso) {
  if (!iso) return "";
  const d = new Date(iso + (iso.length === 10 ? "T12:00:00" : ""));
  if (isNaN(d)) return "";
  return d.toLocaleDateString("ru-RU", { day: "numeric", month: "long", year: "numeric" }).replace(" г.", "");
}

function formatSize(bytes) {
  return bytes ? `${Math.round(bytes / 1048576)} МБ` : "";
}

const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const inline = (s) => escapeHtml(s).replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/`(.+?)`/g, "<code>$1</code>");

/* ───────── Разбор патч-ноутов ───────── */
function kindFromHeading(title) {
  const t = title.toLowerCase();
  if (/добав|нов|add|new/.test(t)) return "add";
  if (/исправ|fix/.test(t)) return "fix";
  if (/измен|улучш|chang|improv/.test(t)) return "change";
  return "plain";
}
const kindFromSymbol = (sym) => ({ "+": "add", "✓": "fix", "✔": "fix", "•": "change" })[sym] || "plain";
const groupTitles = { add: "Добавлено", change: "Изменено", fix: "Исправлено", plain: "Изменения" };

function parseNotes(text) {
  const groups = [];
  let current = null;
  for (const raw of String(text || "").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (/^#{1,6}\s/.test(line)) {
      const title = line.replace(/^#+\s*/, "");
      current = { title, kind: kindFromHeading(title), items: [] };
      groups.push(current);
      continue;
    }
    const m = line.match(/^([+•\-*✓✔])\s+(.*)$/);
    const sym = m ? m[1] : "";
    const item = m ? m[2] : line;
    if (!current) {
      const kind = kindFromSymbol(sym);
      current = { title: groupTitles[kind], kind, items: [] };
      groups.push(current);
    }
    current.items.push(item);
  }
  return groups.filter((g) => g.items.length);
}

/* ───────── Данные ───────── */
async function fetchJson(url, timeoutMs) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: ctrl.signal, headers: { Accept: "application/json" } });
    if (!res.ok) throw new Error(res.status);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

async function loadReleases() {
  const [local, gh] = await Promise.allSettled([
    fetchJson("releases.json", 6000),
    fetchJson(`https://api.github.com/repos/${CONFIG.repo}/releases?per_page=30`, 5000),
  ]);

  const releases = local.status === "fulfilled" ? local.value.releases.map((r) => ({ ...r })) : [];
  const ghList = gh.status === "fulfilled" && Array.isArray(gh.value)
    ? gh.value.filter((r) => !r.draft && !r.prerelease)
    : [];

  // Привязываем APK из GitHub к версиям из патч-ноутов.
  for (const g of ghList) {
    const version = g.tag_name.replace(/^v/i, "");
    const apk = g.assets.find((a) => a.name === CONFIG.apkName) || g.assets.find((a) => a.name.endsWith(".apk"));
    let rel = releases.find((r) => compareVersions(r.version, version) === 0);
    if (!rel) {
      // Релиз уже на GitHub, но сайт ещё не пересобран — берём описание оттуда.
      rel = { version, date: (g.published_at || "").slice(0, 10), notes: g.body || "" };
      releases.push(rel);
    }
    rel.published = true;
    rel.pageUrl = g.html_url;
    if (apk) {
      rel.apkUrl = apk.browser_download_url;
      rel.apkSize = apk.size;
    }
  }

  releases.sort((a, b) => compareVersions(b.version, a.version));
  // Версия из патч-ноутов, которой ещё нет на GitHub, — «скоро»: скачать её нельзя.
  const newestPublished = releases.find((r) => r.published);
  if (newestPublished) {
    releases.forEach((r) => { r.upcoming = compareVersions(r.version, newestPublished.version) > 0; });
  }
  return releases;
}

/* ───────── Отрисовка ───────── */
function renderDownload(latest) {
  const url = latest?.apkUrl || fallbackApkUrl;
  $$(".js-download").forEach((a) => {
    a.href = url;
    a.setAttribute("download", "");
    a.rel = "noopener";
  });
  if (!latest) return;
  $$(".js-version").forEach((el) => (el.textContent = latest.version));
  const size = $(".js-size");
  if (latest.apkSize) {
    size.textContent = formatSize(latest.apkSize);
    size.hidden = false;
  }
  $(".js-date").textContent = latest.date ? `от ${formatDate(latest.date)}` : "";
}

function renderBanner(latest) {
  if (!userVersion || !latest) return;
  const banner = $("#update-banner");
  const text = $("#update-banner-text");
  if (compareVersions(latest.version, userVersion) > 0) {
    text.textContent = `У тебя ${userVersion} — доступна ${latest.version}`;
  } else {
    banner.classList.add("is-current");
    text.textContent = `У тебя последняя версия — ${userVersion}`;
  }
  banner.hidden = false;
}

function releaseCounts(groups) {
  const sum = (k) => groups.filter((g) => g.kind === k).reduce((n, g) => n + g.items.length, 0);
  const parts = [];
  const add = sum("add"), change = sum("change"), fix = sum("fix"), plain = sum("plain");
  if (add) parts.push(`+${add} ${plural(add, "новое", "новых", "новых")}`);
  if (change) parts.push(`${change} ${plural(change, "изменение", "изменения", "изменений")}`);
  if (fix) parts.push(`${fix} ${plural(fix, "фикс", "фикса", "фиксов")}`);
  if (plain) parts.push(`${plain} ${plural(plain, "изменение", "изменения", "изменений")}`);
  return parts;
}

function releaseNode(rel, { isLatest, isNew, isYours, isOld, isUpcoming, open }) {
  const groups = parseNotes(rel.notes);
  const el = document.createElement("details");
  el.className = "release reveal";
  el.id = `v${rel.version}`;
  if (isLatest) el.classList.add("is-latest");
  if (isNew) el.classList.add("is-new");
  if (isOld) el.classList.add("is-old");
  el.open = open;

  const tags = [
    isLatest && `<span class="tag tag--latest">Последняя</span>`,
    isUpcoming && `<span class="tag tag--soon">Скоро</span>`,
    isNew && !isLatest && `<span class="tag tag--new">Новое для тебя</span>`,
    isYours && `<span class="tag tag--yours">Твоя версия</span>`,
  ].filter(Boolean).join("");

  const counts = releaseCounts(groups).map((c) => `<span>${c}</span>`).join("");
  const body = groups.length
    ? groups.map((g) => `
        <div class="group group--${g.kind}">
          <h4>${escapeHtml(g.title)}</h4>
          <ul>${g.items.map((i) => `<li>${inline(i)}</li>`).join("")}</ul>
        </div>`).join("")
    : `<p class="release__date">Описание пока не добавлено.</p>`;

  const dl = rel.apkUrl
    ? `<a class="release__dl" href="${rel.apkUrl}" download rel="noopener">Скачать ${escapeHtml(rel.version)}${rel.apkSize ? ` · ${formatSize(rel.apkSize)}` : ""}</a>`
    : "";

  el.innerHTML = `
    <summary>
      <span class="release__ver">${escapeHtml(rel.version)}</span>
      ${tags}
      <span class="release__date">${formatDate(rel.date)}</span>
      <span class="release__counts">${counts}</span>
      <svg class="release__chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
    </summary>
    <div class="release__body">${body}${isLatest ? "" : dl}</div>`;
  return el;
}

function renderTimeline(releases) {
  const root = $("#timeline");
  root.innerHTML = "";
  if (!releases.length) {
    root.innerHTML = `<p class="timeline__loading">Не удалось загрузить список изменений. Он есть на <a href="${ghReleasesPage}" target="_blank" rel="noopener" style="color:var(--red)">GitHub</a>.</p>`;
    return;
  }

  const latest = releases.find((r) => !r.upcoming) || releases[0];
  const hasUser = !!userVersion;
  const newCount = hasUser ? releases.filter((r) => !r.upcoming && compareVersions(r.version, userVersion) > 0).length : 0;

  if (hasUser && newCount) {
    $("#changelog-sub").textContent = `С твоей версии ${userVersion} ${plural(newCount, "вышло", "вышло", "вышло")} ${newCount} ${plural(newCount, "обновление", "обновления", "обновлений")} — они отмечены красным.`;
  }

  const hidden = [];
  releases.forEach((rel, i) => {
    const isUpcoming = !!rel.upcoming;
    const isNew = hasUser && !isUpcoming && compareVersions(rel.version, userVersion) > 0;
    const isYours = hasUser && compareVersions(rel.version, userVersion) === 0;
    const isOld = hasUser && !isNew && !isYours && !isUpcoming;
    const node = releaseNode(rel, {
      isLatest: rel === latest,
      isNew,
      isYours,
      isOld,
      isUpcoming,
      open: hasUser ? isNew : rel === latest,
    });
    const limit = Math.max(CONFIG.initiallyShown, newCount + 1 + releases.filter((r) => r.upcoming).length);
    if (i >= limit) {
      node.hidden = true;
      hidden.push(node);
    }
    root.appendChild(node);
  });

  if (hidden.length) {
    const more = document.createElement("button");
    more.className = "timeline__more";
    more.type = "button";
    more.textContent = `Показать ещё ${hidden.length} ${plural(hidden.length, "версию", "версии", "версий")}`;
    more.addEventListener("click", () => {
      hidden.forEach((n) => { n.hidden = false; n.classList.add("is-visible"); });
      more.remove();
    });
    root.appendChild(more);
  }

  observeReveals($$(".reveal", root));

  // Пришли по ссылке вида #v1.3.0 — раскрываем нужную версию.
  const target = releaseAnchor && document.getElementById(releaseAnchor);
  if (target) {
    target.hidden = false;
    target.open = true;
    target.scrollIntoView({ block: "start" });
  }
}

/* ───────── Плавное раскрытие патч-ноутов и FAQ ───────── */
const OPEN_EASE = "cubic-bezier(0.22, 1, 0.36, 1)";
const CLOSE_EASE = "cubic-bezier(0.4, 0, 0.2, 1)";

function toggleDetails(details) {
  const content = $(":scope > summary", details)?.nextElementSibling;
  if (!content || reducedMotion) {
    details.open = !details.open;
    return;
  }
  if (details._anim) details._anim.forEach((a) => a.cancel());

  const opening = !details.open;
  const cs = getComputedStyle(content);
  details.open = true;
  const full = content.scrollHeight;
  const from = opening ? 0 : content.getBoundingClientRect().height;
  const to = opening ? full : 0;
  const pad = [cs.paddingTop, cs.paddingBottom];
  const duration = Math.min(700, 320 + full * 0.35);

  content.style.overflow = "hidden";
  const frames = opening
    ? [{ height: `${from}px`, paddingTop: 0, paddingBottom: 0, opacity: 0 },
       { height: `${to}px`, paddingTop: pad[0], paddingBottom: pad[1], opacity: 1 }]
    : [{ height: `${from}px`, paddingTop: pad[0], paddingBottom: pad[1], opacity: 1 },
       { height: `${to}px`, paddingTop: 0, paddingBottom: 0, opacity: 0 }];
  const anim = content.animate(frames, { duration: opening ? duration : duration * 0.75, easing: opening ? OPEN_EASE : CLOSE_EASE });
  const kids = opening
    ? [...content.children].map((c, i) => c.animate(
        [{ opacity: 0, transform: "translateY(-6px)" }, { opacity: 1, transform: "none" }],
        { duration: duration, delay: 60 + i * 50, easing: OPEN_EASE, fill: "backwards" }))
    : [];
  details._anim = [anim, ...kids];
  anim.onfinish = () => {
    content.style.overflow = "";
    if (!opening) details.open = false;
    details._anim = null;
  };
}

document.addEventListener("click", (e) => {
  const summary = e.target.closest("details > summary");
  if (!summary || !summary.parentElement.matches(".release, .faq details")) return;
  e.preventDefault();
  toggleDetails(summary.parentElement);
});

// Ссылки на разделы прокручивают плавно и не меняют адрес.
document.addEventListener("click", (e) => {
  const link = e.target.closest('a[href^="#"]');
  if (!link) return;
  const id = link.getAttribute("href").slice(1);
  const el = id ? document.getElementById(id) : document.body;
  if (!el) return;
  e.preventDefault();
  if (id === "top") scrollTo({ top: 0, behavior: reducedMotion ? "auto" : "smooth" });
  else el.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "start" });
});

/* ───────── Анимации ───────── */
let revealObserver;
function observeReveals(nodes) {
  if (!("IntersectionObserver" in window) || reducedMotion) {
    nodes.forEach((n) => {
      n.classList.add("is-visible");
      if (n.matches(".stat")) countUp($("b", n));
    });
    return;
  }
  revealObserver ||= new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return;
      e.target.classList.add("is-visible");
      if (e.target.matches(".stat")) countUp($("b", e.target));
      revealObserver.unobserve(e.target);
    });
  }, { threshold: 0.12, rootMargin: "0px 0px -6% 0px" });

  // Небольшая лесенка для соседних элементов.
  nodes.forEach((n) => {
    const siblings = [...n.parentElement.children].filter((c) => c.classList.contains("reveal"));
    n.style.setProperty("--rd", `${Math.min(siblings.indexOf(n), 6) * 0.07}s`);
    revealObserver.observe(n);
  });
}

function countUp(el) {
  if (!el || el.dataset.done) return;
  el.dataset.done = "1";
  const to = +el.dataset.count, pre = el.dataset.prefix || "", suf = el.dataset.suffix || "";
  if (reducedMotion || to === 0) { el.textContent = pre + to + suf; return; }
  const start = performance.now(), dur = 1400;
  const tick = (now) => {
    const p = Math.min((now - start) / dur, 1);
    el.textContent = pre + Math.round(to * (1 - Math.pow(1 - p, 4))) + suf;
    if (p < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

function buildBars(container, count, shape) {
  const frag = document.createDocumentFragment();
  for (let i = 0; i < count; i++) {
    const bar = document.createElement("i");
    const [from, to] = shape(i / (count - 1));
    bar.style.setProperty("--from", from.toFixed(2));
    bar.style.setProperty("--to", to.toFixed(2));
    bar.style.setProperty("--d", `${(0.7 + Math.random() * 0.9).toFixed(2)}s`);
    bar.style.setProperty("--delay", `${(-Math.random() * 2).toFixed(2)}s`);
    frag.appendChild(bar);
  }
  container.appendChild(frag);
}

function initVisuals() {
  const bars = $(".hero__bars");
  const barCount = Math.max(20, Math.round(Math.min(innerWidth, 1600) / 30));
  buildBars(bars, barCount, (x) => {
    const hump = Math.sin(x * Math.PI);
    return [0.05 + hump * 0.15 * Math.random(), 0.25 + hump * 0.75 * (0.4 + Math.random() * 0.6)];
  });

  // Спектр «обрывается» на верхних частотах, как у реального MP3.
  buildBars($(".spectrum"), 40, (x) => {
    const cut = x > 0.82 ? 0.04 : 1;
    const level = (1 - x * 0.6) * cut;
    return [level * 0.3, level * (0.6 + Math.random() * 0.4)];
  });

  const nav = $("#nav");
  const onScroll = () => nav.classList.toggle("is-scrolled", scrollY > 20);
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  // Липкая кнопка на телефоне появляется, когда главная кнопка ушла за экран.
  const sticky = $("#sticky-dl");
  const heroBtn = $("#hero-download");
  const finalSection = $(".final");
  if ("IntersectionObserver" in window) {
    let heroVisible = true, finalVisible = false;
    const update = () => sticky.classList.toggle("is-shown", !heroVisible && !finalVisible);
    new IntersectionObserver(([e]) => { heroVisible = e.isIntersecting; update(); }).observe(heroBtn);
    new IntersectionObserver(([e]) => { finalVisible = e.isIntersecting; update(); }).observe(finalSection);
  }

  // Бесконечные анимации крутятся, только пока блок на экране.
  if ("IntersectionObserver" in window) {
    const pauser = new IntersectionObserver((entries) => {
      entries.forEach((e) => e.target.classList.toggle("is-offscreen", !e.isIntersecting));
    });
    $$(".hero, .sources, .spectrum").forEach((el) => pauser.observe(el));
  }

  if (matchMedia("(hover: hover)").matches && !reducedMotion) {
    // Свечение догоняет курсор и засыпает, когда догнало.
    const glow = $(".glow-cursor");
    let x = innerWidth / 2, y = innerHeight / 3, gx = x, gy = y, running = false;
    const loop = () => {
      gx += (x - gx) * 0.14;
      gy += (y - gy) * 0.14;
      glow.style.transform = `translate3d(${gx}px, ${gy}px, 0)`;
      running = Math.abs(x - gx) + Math.abs(y - gy) > 0.5;
      if (running) requestAnimationFrame(loop);
    };
    addEventListener("pointermove", (e) => {
      x = e.clientX; y = e.clientY;
      if (!running) { running = true; requestAnimationFrame(loop); }
    }, { passive: true });

    $$(".card").forEach((card) => {
      let pending = null;
      card.addEventListener("pointermove", (e) => {
        if (!pending) requestAnimationFrame(() => {
          const r = card.getBoundingClientRect();
          card.style.setProperty("--mx", `${pending.x - r.left}px`);
          card.style.setProperty("--my", `${pending.y - r.top}px`);
          pending = null;
        });
        pending = { x: e.clientX, y: e.clientY };
      }, { passive: true });
    });
  }
}

/* QR-код для тех, кто открыл сайт с компьютера. */
function initQr() {
  const isPhone = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent);
  if (isPhone || !/^https?:$/.test(location.protocol)) return;
  const draw = () => {
    if (!window.QRCode) return;
    new QRCode($("#qr-code"), {
      text: location.origin + location.pathname,
      width: 184, height: 184,
      colorDark: "#07070a", colorLight: "#ffffff",
      correctLevel: QRCode.CorrectLevel.M,
    });
    $("#qr").hidden = false;
  };
  window.QRCode ? draw() : addEventListener("load", draw, { once: true });
}

/* ───────── Старт ───────── */
document.addEventListener("DOMContentLoaded", async () => {
  if (!releaseAnchor) scrollTo(0, 0);
  $("#all-builds").href = ghReleasesPage;
  $("#repo-link").href = `https://github.com/${CONFIG.repo}`;
  renderDownload(null);
  initVisuals();
  observeReveals($$(".reveal"));
  initQr();

  const releases = await loadReleases();
  const latest = releases.find((r) => !r.upcoming) || releases[0];
  renderDownload(latest);
  renderBanner(latest);
  renderTimeline(releases);
});
