"use strict";
/* Game Catalog — app independente.
   Dados: planilha Google do usuário (Sheets API) e pasta no Google Drive (fotos).
   Identificação e leitura de anúncios: Gemini, com a chave gratuita do próprio usuário.
   Busca de preços: Tavily (opcional, chave gratuita) ou a busca do Google embutida no Gemini.
   Chaves de API ficam só no localStorage deste aparelho. Nada passa por servidor próprio. */

const SCOPES = "https://www.googleapis.com/auth/spreadsheets https://www.googleapis.com/auth/drive.file";
const DEFAULT_MODEL = "gemini-flash-latest";
const CFG_TAB = "APP_CONFIG", CAPAS_TAB = "CAPAS", PRECOS_TAB = "PRECOS";
const BUY_HEAD = ["DATA", "CONSOLE", "NOME", "REGIAO", "LISTA", "STATUS", "RECEBIDO_EM", "OBS"];
const SHEETS = "https://sheets.googleapis.com/v4/spreadsheets/";
const DRIVE = "https://www.googleapis.com/drive/v3/files";

/* ---------- utilidades ---------- */
const $ = (id) => document.getElementById(id);
const norm = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toUpperCase().replace(/[^A-Z0-9]/g, "");
const today = () => new Date().toLocaleDateString("pt-BR");
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k === "value") el.value = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}
const LS = {
  get(k, d) { try { const v = localStorage.getItem("gc." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { if (v === null || v === undefined || v === "") localStorage.removeItem("gc." + k); else localStorage.setItem("gc." + k, JSON.stringify(v)); } catch {} }
};
const colLetter = (i) => { let s = ""; i++; while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); } return s; };
const qt = (t) => "'" + String(t).replace(/'/g, "''") + "'";
const padRow = (r, n) => Array.from({ length: n }, (_, k) => String((r || [])[k] ?? "").trim());
function field(id, label, ...inputs) { return h("div", { class: "field" }, h("label", { for: id, text: label }), ...inputs); }
const swords = (s) => String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\*lacrado\*/g, " ")
  .replace(/ · .*$/, "").replace(/\([^)]*\)|\[[^\]]*\]/g, " ").replace(/[^a-z0-9]+/g, " ").trim();
function dice(a, b) {
  if (a.length < 2 || b.length < 2) return a === b ? 1 : 0;
  const m = new Map(); let n = 0;
  for (let i = 0; i < a.length - 1; i++) { const k = a.slice(i, i + 2); m.set(k, (m.get(k) || 0) + 1); }
  for (let i = 0; i < b.length - 1; i++) { const k = b.slice(i, i + 2), c = m.get(k); if (c) { n++; m.set(k, c - 1); } }
  return 2 * n / (a.length + b.length - 2);
}
function simW(a, b) {
  if (!a || !b) return 0;
  const ta = new Set(a.split(" ")), tb = new Set(b.split(" "));
  let inter = 0; ta.forEach(w => { if (tb.has(w)) inter++; });
  return Math.max(dice(a, b), inter / new Set([...ta, ...tb]).size, 0.9 * inter / ta.size);
}
const sim = (q, t) => simW(swords(q), swords(t));
function median(xs) { const s = xs.slice().sort((a, b) => a - b); const m = s.length >> 1; return s.length ? (s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2) : null; }
const brl = (n) => typeof n === "number" && isFinite(n) ? n.toLocaleString("pt-BR", { style: "currency", currency: "BRL" }) : "—";
const usd = (n) => typeof n === "number" && isFinite(n) ? "US$ " + n.toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : "—";
async function shrink(file, max = 1280, q = 0.85) {
  try {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, max / Math.max(bmp.width, bmp.height));
    const cv = document.createElement("canvas"); cv.width = Math.round(bmp.width * k); cv.height = Math.round(bmp.height * k);
    cv.getContext("2d").drawImage(bmp, 0, 0, cv.width, cv.height);
    return await new Promise((res) => cv.toBlob(b => res(b || file), "image/jpeg", q));
  } catch { return file; }
}
const blobB64 = (b) => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1]); r.onerror = rej; r.readAsDataURL(b); });

/* ---------- estado ---------- */
const app = { clientId: "", version: "", token: null, tokenExp: 0, email: "" };
let cfg = null;                 // configuração guardada na aba APP_CONFIG da planilha
const data = { loaded: false, head: [], games: [], wanted: [], buying: [], consoles: [], capas: {}, capasRows: {}, precos: {}, precosRows: {} };
const sid = () => LS.get("sheetId", "");

/* ---------- login Google (Google Identity Services, fluxo de token no navegador) ---------- */
let tokenClient = null, authWait = null;
function loadGis() {
  return new Promise((res, rej) => {
    if (window.google && google.accounts && google.accounts.oauth2) return res();
    const s = document.createElement("script"); s.src = "https://accounts.google.com/gsi/client"; s.async = true;
    s.onload = () => res(); s.onerror = () => rej(new Error("gis")); document.head.append(s);
  });
}
function initAuth() {
  tokenClient = google.accounts.oauth2.initTokenClient({
    client_id: app.clientId, scope: SCOPES,
    callback: (r) => {
      const w = authWait; authWait = null;
      if (r && r.access_token) {
        app.token = r.access_token; app.tokenExp = Date.now() + (Number(r.expires_in) || 3600) * 1000;
        $("authbanner").hidden = true;
        w && w.res(app.token);
      } else w && w.rej({ code: "auth", message: (r && (r.error_description || r.error)) || "login recusado" });
    },
    error_callback: (e) => { const w = authWait; authWait = null; w && w.rej({ code: "auth", message: (e && e.type) || "janela fechada" }); }
  });
}
// precisa ser chamado a partir de um toque (o Google abre uma janela)
function signIn() {
  return new Promise((res, rej) => { authWait = { res, rej }; tokenClient.requestAccessToken({ prompt: "" }); });
}
function needAuth() { app.token = null; $("authbanner").hidden = false; }

async function gapi(url, opt = {}) {
  if (!app.token || Date.now() > app.tokenExp - 30000) { needAuth(); throw { code: "auth", message: "Entre com o Google de novo." }; }
  const headers = { Authorization: "Bearer " + app.token, ...(opt.headers || {}) };
  if (typeof opt.body === "string") headers["Content-Type"] = "application/json";
  const r = await fetch(url, { ...opt, headers });
  if (r.status === 401) { needAuth(); throw { code: "auth", message: "Sessão do Google expirou." }; }
  if (!r.ok) { let m = ""; try { m = (await r.json()).error.message; } catch {} throw { code: "http", status: r.status, message: m || r.statusText }; }
  if (opt.raw) return r;
  return r.status === 204 ? null : r.json();
}
const errMsg = (e) => e && e.code === "auth" ? "Sessão do Google expirou: toque em Entrar de novo." :
  e && e.status === 403 ? "O Google negou o acesso a esta planilha (" + (e.message || "403") + ")." :
  e && e.status === 404 ? "Planilha ou aba não encontrada." :
  e && e.message ? "Erro do Google: " + e.message : "Falha de conexão com o Google.";

/* Sheets */
const getValues = async (range) => ((await gapi(SHEETS + sid() + "/values/" + encodeURIComponent(range))).values) || [];
async function batchGet(ranges) {
  const q = ranges.map(r => "ranges=" + encodeURIComponent(r)).join("&");
  return ((await gapi(SHEETS + sid() + "/values:batchGet?" + q)).valueRanges || []).map(v => v.values || []);
}
const putValues = (range, values) => gapi(SHEETS + sid() + "/values/" + encodeURIComponent(range) + "?valueInputOption=RAW", { method: "PUT", body: JSON.stringify({ values }) });
const appendValues = (range, values) => gapi(SHEETS + sid() + "/values/" + encodeURIComponent(range) + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", { method: "POST", body: JSON.stringify({ values }) });
const sheetMeta = (id = sid()) => gapi(SHEETS + id + "?fields=properties.title,sheets.properties(title,sheetId,hidden)");
async function ensureTab(title, header, hidden = false) {
  const meta = await sheetMeta();
  if (meta.sheets.some(s => s.properties.title === title)) return false;
  await gapi(SHEETS + sid() + ":batchUpdate", { method: "POST", body: JSON.stringify({ requests: [{ addSheet: { properties: { title, hidden } } }] }) });
  if (header) await putValues(qt(title) + "!A1", [header]);
  return true;
}

/* Drive (só arquivos criados pelo app: escopo drive.file) */
async function photoFolder() {
  if (cfg.pastaFotos) return cfg.pastaFotos;
  const q = encodeURIComponent("name='Game Catalog - fotos' and mimeType='application/vnd.google-apps.folder' and trashed=false");
  const found = await gapi(`${DRIVE}?q=${q}&fields=files(id)`);
  let id = found.files && found.files[0] && found.files[0].id;
  if (!id) id = (await gapi(`${DRIVE}?fields=id`, { method: "POST", body: JSON.stringify({ name: "Game Catalog - fotos", mimeType: "application/vnd.google-apps.folder" }) })).id;
  cfg.pastaFotos = id; await saveCfg();
  return id;
}
async function driveUpload(blob, name) {
  const folder = await photoFolder();
  const form = new FormData();
  form.append("metadata", new Blob([JSON.stringify({ name, parents: [folder] })], { type: "application/json" }));
  form.append("file", blob);
  return (await gapi("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", { method: "POST", body: form })).id;
}
const driveCache = {};
function driveImg(id) {
  return driveCache[id] ||= gapi(`${DRIVE}/${encodeURIComponent(id)}?alt=media`, { raw: true }).then(r => r.blob()).then(b => URL.createObjectURL(b)).catch(() => { delete driveCache[id]; return ""; });
}

/* ---------- configuração da planilha ---------- */
async function loadCfg() {
  const meta = await sheetMeta();
  data.title = meta.properties.title;
  data.tabs = meta.sheets.map(s => s.properties.title);
  if (!data.tabs.includes(CFG_TAB)) return null;
  const v = await getValues(qt(CFG_TAB) + "!A1");
  try { return JSON.parse((v[0] || [])[0] || "null"); } catch { return null; }
}
async function saveCfg() {
  await ensureTab(CFG_TAB, null, true);
  await putValues(qt(CFG_TAB) + "!A1:B1", [[JSON.stringify(cfg), "Configuração do app Game Catalog. Não edite esta aba à mão."]]);
}

/* Plataformas: código do console na planilha → acervo do libretro-thumbnails */
let LIBRETRO = [];      // [{repo, nome, n}]
const ALIAS = {
  SNES: "Nintendo_-_Super_Nintendo_Entertainment_System", SUPERNINTENDO: "Nintendo_-_Super_Nintendo_Entertainment_System", SFC: "Nintendo_-_Super_Nintendo_Entertainment_System", SUPERFAMICOM: "Nintendo_-_Super_Nintendo_Entertainment_System",
  NES: "Nintendo_-_Nintendo_Entertainment_System", FAMICOM: "Nintendo_-_Nintendo_Entertainment_System", NINTENDINHO: "Nintendo_-_Nintendo_Entertainment_System", FC: "Nintendo_-_Nintendo_Entertainment_System",
  MD: "Sega_-_Mega_Drive_-_Genesis", MEGADRIVE: "Sega_-_Mega_Drive_-_Genesis", GENESIS: "Sega_-_Mega_Drive_-_Genesis",
  MS: "Sega_-_Master_System_-_Mark_III", MASTERSYSTEM: "Sega_-_Master_System_-_Mark_III", SMS: "Sega_-_Master_System_-_Mark_III",
  GG: "Sega_-_Game_Gear", GAMEGEAR: "Sega_-_Game_Gear", SEGACD: "Sega_-_Mega-CD_-_Sega_CD", MEGACD: "Sega_-_Mega-CD_-_Sega_CD", SATURN: "Sega_-_Saturn", SEGASATURN: "Sega_-_Saturn",
  DC: "Sega_-_Dreamcast", DREAMCAST: "Sega_-_Dreamcast", GB: "Nintendo_-_Game_Boy", GAMEBOY: "Nintendo_-_Game_Boy", GBC: "Nintendo_-_Game_Boy_Color", GAMEBOYCOLOR: "Nintendo_-_Game_Boy_Color",
  GBA: "Nintendo_-_Game_Boy_Advance", GAMEBOYADVANCE: "Nintendo_-_Game_Boy_Advance", GAMEBOYADVANCED: "Nintendo_-_Game_Boy_Advance", N64: "Nintendo_-_Nintendo_64", NINTENDO64: "Nintendo_-_Nintendo_64",
  GC: "Nintendo_-_GameCube", NGC: "Nintendo_-_GameCube", GAMECUBE: "Nintendo_-_GameCube", WII: "Nintendo_-_Wii", WIIU: "Nintendo_-_Wii_U", DS: "Nintendo_-_Nintendo_DS", NDS: "Nintendo_-_Nintendo_DS",
  "3DS": "Nintendo_-_Nintendo_3DS", N3DS: "Nintendo_-_Nintendo_3DS", PS1: "Sony_-_PlayStation", PSX: "Sony_-_PlayStation", PS: "Sony_-_PlayStation", PLAYSTATION: "Sony_-_PlayStation",
  PS2: "Sony_-_PlayStation_2", PLAYSTATION2: "Sony_-_PlayStation_2", PS3: "Sony_-_PlayStation_3", PLAYSTATION3: "Sony_-_PlayStation_3", PS4: "Sony_-_PlayStation_4", PLAYSTATION4: "Sony_-_PlayStation_4",
  PSP: "Sony_-_PlayStation_Portable", MVS: "SNK_-_Neo_Geo", NEOGEO: "SNK_-_Neo_Geo", AES: "SNK_-_Neo_Geo", ODYSSEY: "Magnavox_-_Odyssey2", ODYSSEY2: "Magnavox_-_Odyssey2", VIDEOPAC: "Magnavox_-_Odyssey2",
  ATARI: "Atari_-_2600", ATARI2600: "Atari_-_2600", "2600": "Atari_-_2600", MSX: "Microsoft_-_MSX", XBOX: "Microsoft_-_Xbox", X360: "Microsoft_-_Xbox_360", XBOX360: "Microsoft_-_Xbox_360",
  PCENGINE: "NEC_-_PC_Engine_-_TurboGrafx_16", TURBOGRAFX: "NEC_-_PC_Engine_-_TurboGrafx_16"
};
function guessPlatform(code) {
  const n = norm(code);
  if (ALIAS[n]) return ALIAS[n];
  for (const p of ["ATARI", "NES", "PS2", "PS3", "PS4", "PS1", "MD", "MS", "SNES"]) if (n.startsWith(p) && ALIAS[p]) return ALIAS[p];
  return "";
}
const platformOf = (console) => (cfg && cfg.plataformas && norm(console) in cfg.plataformas) ? cfg.plataformas[norm(console)] : guessPlatform(console);
const PLAT_LABEL = (repo) => (LIBRETRO.find(x => x.repo === repo) || {}).nome || repo.replace(/_-_/g, " · ").replace(/_/g, " ");

/* ---------- leitura dos dados ---------- */
function setSheet(msg, state) { $("sheetbar").hidden = false; $("sheetmsg").textContent = msg; $("sheetdot").className = "dot " + (state || ""); }
let loading = false;
async function loadData() {
  if (loading) return; loading = true;
  setSheet("Planilha: lendo…", "wait");
  try {
    const c = cfg.colecao, last = colLetter(c.ultima);
    const ranges = [`${qt(c.aba)}!A1:${last}5000`, ...cfg.procurados.map(w => `${qt(w.aba)}!A1:Z5000`), `${qt(cfg.compras.aba)}!A1:H5000`];
    const tabsNow = (await sheetMeta()).sheets.map(s => s.properties.title);
    for (const t of [CAPAS_TAB, PRECOS_TAB]) if (tabsNow.includes(t)) ranges.push(`${qt(t)}!A1:F5000`);
    const vals = await batchGet(ranges);
    const gv = vals[0];
    data.head = padRow(gv[0], c.ultima + 1);
    data.games = gv.slice(1).map((r, i) => {
      const raw = padRow(r, c.ultima + 1);
      return { id: "G" + (i + 2), row: i + 2, raw, console: raw[c.console], nome: raw[c.nome], regiao: c.regiao >= 0 ? raw[c.regiao] : "",
        flags: data.head.map((hd, k) => [hd || colLetter(k), raw[k], k]).filter(([, , k]) => k !== c.console && k !== c.nome && k !== c.regiao) };
    }).filter(g => g.nome);
    data.wanted = [];
    cfg.procurados.forEach((w, wi) => {
      (vals[1 + wi] || []).slice(1).forEach((r, i) => {
        const nome = String(r[w.nome] || "").trim(); if (!nome) return;
        data.wanted.push({ id: `W${wi}-${i + 2}`, wi, tab: w.aba, row: i + 2, nome, console: w.consoleCol >= 0 ? String(r[w.consoleCol] || "").trim() : (w.console || ""),
          info: w.info >= 0 ? String(r[w.info] || "") : "", status: w.status >= 0 ? String(r[w.status] || "").trim() : "" });
      });
    });
    const bv = vals[1 + cfg.procurados.length] || [];
    data.buying = bv.slice(1).map((r, i) => ({ id: "B" + (i + 2), row: i + 2, data: r[0] || "", console: r[1] || "", nome: r[2] || "", regiao: r[3] || "", lista: r[4] || "", status: String(r[5] || "").trim(), recebido: r[6] || "", obs: r[7] || "" })).filter(b => b.nome);
    let k = 2 + cfg.procurados.length;
    data.capas = {}; data.capasRows = {}; data.precos = {}; data.precosRows = {};
    if (tabsNow.includes(CAPAS_TAB)) {
      (vals[k++] || []).slice(1).forEach((r, i) => { const [key, tipo, origem, valor, ref] = padRow(r, 5); if (!key || !tipo) return;
        data.capasRows[key + "|" + tipo] = i + 2; if (valor) (data.capas[key] ||= {})[tipo] = { origem, valor, ref }; });
    }
    if (tabsNow.includes(PRECOS_TAB)) {
      (vals[k++] || []).slice(1).forEach((r, i) => { const [key, em, json] = padRow(r, 3); if (!key) return; data.precosRows[key] = i + 2; if (json) data.precos[key] = { em, json }; });
    }
    data.consoles = [...new Set(data.games.map(g => g.console.toUpperCase()).filter(Boolean))].sort();
    data.loaded = true;
    const n = pending().length;
    setSheet(`${data.title}: ${data.games.length} na coleção · ${data.wanted.length} procurados · ${n} comprando`, "ok");
    $("tab-buy").textContent = `Compras (${n})`; $("tab-col").textContent = `Coleção (${data.games.length})`;
  } catch (e) { setSheet(errMsg(e), "bad"); }
  finally { loading = false; renderBuying(); fillConsoleFilter(); renderCollection(); }
}
const pending = () => data.buying.filter(b => b.status.toUpperCase() !== "RECEBIDO");
$("reload").addEventListener("click", () => loadData());

/* ---------- capas ---------- */
const gkey = (g) => norm(g.console) + "|" + norm(g.nome);
const libIdx = {};
function libretro(repo) {
  if (!repo) return Promise.resolve(null);
  return libIdx[repo] ||= fetch("data/libretro/" + repo + ".json").then(r => r.ok ? r.json() : null).then(d => d && { ...d, base: d.boxarts.map(swords) }).catch(() => null);
}
const libUrl = (repo, sha, name) => `https://raw.githubusercontent.com/libretro-thumbnails/${repo}/${sha}/Named_Boxarts/${encodeURIComponent(name)}.png`;
const REG_PREF = (r) => { const n = norm(r);
  if (/^(BR|BRA|BRASIL|BRAZIL)/.test(n)) return ["Brazil", "USA", "World", "Europe", "Japan"];
  if (/^(JP|JAP|JPN|JAPAO|JAPAN|NTSCJ)/.test(n)) return ["Japan", "Asia", "World", "USA"];
  if (/^(EU|EUR|PAL|EUROPA|UK)/.test(n)) return ["Europe", "World", "USA"];
  if (/^(ES|ESP|SPAIN)/.test(n)) return ["Spain", "Europe", "World"];
  return ["USA", "World", "Europe", "Japan"]; };
const BAD = /\((Beta|Proto|Demo|Sample|Pirate|Unl|Hack|Aftermarket|Kiosk)/i;
function libRank(idx, nome, regiao, limit) {
  const q = swords(nome), pref = REG_PREF(regiao);
  const sc = []; idx.base.forEach((b, i) => { const s = simW(q, b); if (s >= 0.5) sc.push([s, i]); });
  sc.sort((a, b) => b[0] - a[0]);
  const top = sc.slice(0, 60).map(([s, i]) => { const nm = idx.boxarts[i]; const r0 = (nm.match(/\(([^)]*)\)/) || [])[1] || "";
    const pr = pref.findIndex(p => r0 === p || r0.split(", ").includes(p)); return { s, nm, pr: pr < 0 ? 9 : pr, bad: BAD.test(nm) }; });
  top.sort((a, b) => (Math.round(b.s * 50) - Math.round(a.s * 50)) || (a.bad - b.bad) || (a.pr - b.pr) || (a.nm.length - b.nm.length));
  return top.slice(0, limit);
}
const autoCache = {};
async function autoCover(g) {
  const k = gkey(g);
  if (k in autoCache) return autoCache[k];
  const repo = platformOf(g.console), idx = await libretro(repo);
  let res = null;
  if (idx) { const best = libRank(idx, g.nome, g.regiao, 1)[0]; if (best && best.s >= 0.8) res = { url: libUrl(repo, idx.sha, best.nm), ref: "libretro-thumbnails: " + best.nm }; }
  return (autoCache[k] = res);
}
// miniatura via images.weserv.nl (serviço gratuito de redimensionamento) para economizar dados
const thumb = (url, w = 240) => LS.get("thumbs", true) && /^https:\/\//.test(url) ? `https://images.weserv.nl/?url=${encodeURIComponent(url)}&w=${w}&output=jpg&q=75` : url;
function setImg(img, url, w) {
  if (!url) return;
  if (url.startsWith("drive:")) { driveImg(url.slice(6)).then(u => { if (u) img.src = u; }); return; }
  img.src = thumb(url, w);
  img.onerror = () => { img.onerror = null; if (img.src !== url) img.src = url; };
}
// imagem escolhida por você (CAPAS) vale mais que a automática
async function imageOf(g, tipo) {
  const c = (data.capas[gkey(g)] || {})[tipo];
  if (c) return { url: c.origem === "drive" ? "drive:" + c.valor : c.valor, ref: c.ref || (c.origem === "drive" ? "Foto sua" : c.valor), mine: true };
  if (tipo === "capa") { const a = await autoCover(g); if (a) return { url: a.url, ref: a.ref, mine: false }; }
  return null;
}
async function setCapa(g, tipo, origem, valor, ref) {
  await ensureTab(CAPAS_TAB, ["CHAVE", "TIPO", "ORIGEM", "VALOR", "REF", "DATA"]);
  const key = gkey(g), row = data.capasRows[key + "|" + tipo], vals = [[key, tipo, origem, valor, ref, valor ? today() : ""]];
  if (row) await putValues(`${qt(CAPAS_TAB)}!A${row}:F${row}`, vals);
  else { const r = await appendValues(`${qt(CAPAS_TAB)}!A1:F1`, vals); const m = /![A-Z]+(\d+)/.exec((r.updates || {}).updatedRange || ""); if (m) data.capasRows[key + "|" + tipo] = Number(m[1]); }
  if (valor) (data.capas[key] ||= {})[tipo] = { origem, valor, ref }; else if (data.capas[key]) delete data.capas[key][tipo];
}

/* ---------- Coleção ---------- */
function fillConsoleFilter() {
  const sel = $("cfilter"), cur = sel.value; sel.textContent = "";
  sel.append(h("option", { value: "", text: "Todos" }), ...data.consoles.map(c => h("option", { value: c, text: `${c} (${data.games.filter(g => g.console.toUpperCase() === c).length})` })));
  sel.value = data.consoles.includes(cur) ? cur : "";
}
function coverNode(g) {
  const box = h("div", { class: "cover" }, h("div", { class: "noimg", text: g.console }));
  imageOf(g, "capa").then(im => { if (!im) return; const img = h("img", { class: "box", alt: "Capa de " + g.nome, loading: "lazy", decoding: "async" }); setImg(img, im.url, 240); box.querySelector(".noimg")?.remove(); box.prepend(img); });
  imageOf(g, "midia").then(im => { if (!im) return; const img = h("img", { class: "mine", alt: "Cartucho", loading: "lazy" }); setImg(img, im.url, 120); box.append(img); });
  return box;
}
function renderCollection() {
  const list = $("clist"); list.textContent = "";
  if (!data.loaded) { $("cmeta").textContent = "A planilha ainda não carregou."; return; }
  const q = norm($("csearch").value), f = $("cfilter").value;
  const games = data.games.filter(g => (!f || g.console.toUpperCase() === f) && (!q || norm(g.nome).includes(q) || norm(g.console).includes(q)));
  $("cmeta").textContent = `${games.length} de ${data.games.length} itens`;
  if (!games.length) { list.append(h("p", { class: "empty", text: q ? "Nenhum jogo encontrado com essa busca." : "Nenhum jogo neste filtro." })); return; }
  const groups = {}; games.forEach(g => (groups[g.console.toUpperCase()] ||= []).push(g));
  Object.keys(groups).sort().forEach(c => {
    const items = groups[c].slice().sort((a, b) => a.nome.localeCompare(b.nome, "pt-BR"));
    const repo = platformOf(c);
    const grid = h("div", { class: "cgrid" }, items.map(g => {
      const b = h("button", { class: "card", type: "button", "aria-label": `${g.nome}, ${g.console}` }, coverNode(g), h("span", { class: "nm", text: g.nome }), h("span", { class: "rg", text: g.regiao || "—" }));
      b.addEventListener("click", () => openGame(g)); return b;
    }));
    list.append(h("section", { class: "cgroup" }, h("h2", {}, repo ? `${c} · ${PLAT_LABEL(repo)}` : c, h("small", { text: String(items.length) })), grid));
  });
}
$("csearch").addEventListener("input", () => renderCollection());
$("cfilter").addEventListener("change", () => renderCollection());

let lastFocus = null;
function closeSheet() { const s = $("sheet"); s.hidden = true; s.textContent = ""; lastFocus?.focus?.(); }
$("sheet").addEventListener("click", (e) => { if (e.target === $("sheet")) closeSheet(); });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !$("sheet").hidden) closeSheet(); });

function picFrame(g, tipo, title) {
  const frame = h("div", { class: "frame" }, h("span", { text: "Sem imagem" }));
  const t = h("h3", { text: title }), ref = h("span", { class: "hint ref" });
  imageOf(g, tipo).then(im => { if (!im) return; frame.textContent = ""; const img = h("img", { alt: title + " de " + g.nome }); setImg(img, im.url, 480); frame.append(img);
    t.textContent = title + (im.mine ? " (escolhida por você)" : " (automática)"); ref.textContent = im.ref || ""; });
  return h("div", { class: "pic" }, t, frame, ref);
}
function photoRow(g, tipo, label, msg, tools) {
  const own = (data.capas[gkey(g)] || {})[tipo];
  const cam = h("label", { class: "chip" }, "Fotografar", h("input", { class: "vh", type: "file", accept: "image/*", capture: "environment" }));
  const gal = h("label", { class: "chip" }, "Galeria", h("input", { class: "vh", type: "file", accept: "image/*" }));
  const find = h("button", { class: "chip", type: "button", text: "Buscar imagem" });
  const back = own ? h("button", { class: "chip", type: "button", text: "Voltar à automática" }) : null;
  for (const l of [cam, gal]) l.querySelector("input").addEventListener("change", async (e) => {
    const f = e.target.files && e.target.files[0]; e.target.value = "";
    if (f && await savePhoto(g, f, msg, tipo)) openGame(g, "Foto salva no seu Google Drive.");
  });
  find.addEventListener("click", () => openPicker(g, tipo, tools, msg));
  back?.addEventListener("click", async () => { try { await setCapa(g, tipo, "", "", ""); openGame(g, "Voltou à imagem automática."); } catch (e) { msg.textContent = errMsg(e); } });
  return h("div", { class: "gallery" }, h("span", { text: label }), cam, gal, find, back);
}
async function savePhoto(g, file, msg, tipo) {
  msg.classList.remove("err"); msg.textContent = "Enviando a foto para o seu Google Drive…";
  try {
    const blob = await shrink(file, 1200);
    const id = await driveUpload(blob, `${g.console} - ${g.nome} - ${tipo}.jpg`);
    await setCapa(g, tipo, "drive", id, "Foto sua");
    renderCollection(); return true;
  } catch (e) { msg.classList.add("err"); msg.textContent = errMsg(e); return false; }
}
function openGame(g, note) {
  lastFocus = $("sheet").hidden ? document.activeElement : lastFocus;
  const s = $("sheet"); s.textContent = "";
  const msg = h("p", { class: "status", role: "status", text: note || "" });
  const x = h("button", { class: "x", type: "button", "aria-label": "Fechar", text: "×" }); x.addEventListener("click", closeSheet);
  const tools = h("div", {});
  const editBtn = h("button", { class: "ghost", type: "button", text: "Editar dados na planilha" });
  editBtn.addEventListener("click", () => { tools.textContent = ""; tools.append(editForm(g)); tools.scrollIntoView({ block: "nearest" }); });
  const priceBox = h("section", {});
  const flags = g.flags.filter(([, v]) => v);
  s.append(h("div", { class: "sheet-in", role: "dialog", "aria-modal": "true", "aria-label": g.nome },
    h("div", { class: "sheet-head" }, h("div", {}, h("p", { class: "eyebrow", text: `${g.console} · ${g.regiao || "sem região"} · linha ${g.row}` }), h("h2", { text: g.nome })), x),
    h("div", { class: "pics" }, picFrame(g, "capa", "Caixa"), picFrame(g, "midia", "Cartucho/disco")),
    flags.length ? h("div", { class: "flags" }, flags.map(([k, v]) => h("span", { class: "flag " + (/^S$/i.test(v) ? "on" : /^N$/i.test(v) ? "off" : ""), text: k + (/^[SN]$/i.test(v) ? "" : ": " + v) }))) : null,
    photoRow(g, "capa", "Caixa:", msg, tools), photoRow(g, "midia", "Cartucho/disco:", msg, tools),
    msg, h("div", { class: "actions" }, editBtn), tools, priceBox));
  s.hidden = false; x.focus();
  showSavedPrice(g, priceBox);
}

/* Busca manual de imagens: libretro (pelo índice) e Wikipédia (ao vivo), por semelhança de nome */
async function wikiCands(q, plat) {
  const url = "https://en.wikipedia.org/w/api.php?origin=*&format=json&action=query&generator=search&gsrlimit=8&prop=pageimages&piprop=thumbnail&pithumbsize=500&pilicense=any&gsrsearch=" + encodeURIComponent(`${q} ${plat} video game`);
  try {
    const d = await (await fetch(url)).json();
    return Object.values((d.query || {}).pages || {}).filter(p => p.thumbnail && !/^List of /i.test(p.title) && !/\.svg/i.test(p.thumbnail.source))
      .sort((a, b) => a.index - b.index).map(p => ({ n: p.title, f: "Wikipédia", url: p.thumbnail.source, u: "https://en.wikipedia.org/wiki/" + encodeURIComponent(p.title.replace(/ /g, "_")) }));
  } catch { return []; }
}
function openPicker(g, tipo, tools, msg) {
  tools.textContent = "";
  const q = h("input", { type: "search", value: g.nome.replace(/\*LACRADO\*/gi, "").trim(), "aria-label": "Nome para buscar" });
  const plat = h("select", { "aria-label": "Plataforma" }, h("option", { value: "", text: "Plataforma: nenhuma" }), LIBRETRO.map(p => h("option", { value: p.repo, text: p.nome })));
  plat.value = platformOf(g.console);
  const grid = h("div", { class: "pgrid" }), st = h("p", { class: "status", role: "status" });
  const go = h("button", { class: "ghost", type: "button", text: "Buscar" });
  const close = h("button", { class: "ghost", type: "button", text: "Fechar busca" }); close.addEventListener("click", () => { tools.textContent = ""; });
  const pane = h("div", { class: "panel" }, h("h3", { text: tipo === "capa" ? "Buscar imagem da caixa" : "Buscar imagem do cartucho/disco" }),
    h("p", { class: "hint", text: "Caixas do libretro-thumbnails e imagens da Wikipédia, das mais parecidas com o nome para as menos parecidas." }),
    h("div", { class: "row" }, q, plat), h("div", { class: "actions" }, go), st, grid, h("div", { class: "actions" }, close));
  tools.append(pane); pane.scrollIntoView({ block: "nearest" });
  const run = async () => {
    grid.textContent = ""; st.textContent = "Buscando…";
    const idx = await libretro(plat.value);
    const lib = idx ? libRank(idx, q.value, g.regiao, 12).map(c => ({ n: c.nm, f: "libretro", url: libUrl(plat.value, idx.sha, c.nm), u: libUrl(plat.value, idx.sha, c.nm) })) : [];
    const wiki = await wikiCands(q.value, plat.value ? PLAT_LABEL(plat.value) : "");
    const all = [...lib, ...wiki];
    st.textContent = all.length ? `${all.length} sugestões. Toque numa imagem para usá-la.` : "Nada encontrado. Tente outro nome ou outra plataforma.";
    all.forEach(m => {
      const img = h("img", { alt: m.n, loading: "lazy" }); setImg(img, m.url, 200);
      const card = h("button", { class: "pcand", type: "button" }, h("div", { class: "frame" }, img), h("span", { text: m.n }), h("small", { text: m.f }));
      img.addEventListener("error", () => { if (img.src === m.url) card.remove(); });
      card.addEventListener("click", () => confirmPick(g, tipo, m, tools, msg));
      grid.append(card);
    });
  };
  go.addEventListener("click", run); q.addEventListener("keydown", (e) => { if (e.key === "Enter") run(); });
  run();
}
function confirmPick(g, tipo, m, tools, msg) {
  tools.textContent = "";
  const use = h("button", { class: "primary", type: "button", text: tipo === "capa" ? "Usar como caixa" : "Usar como cartucho/disco" });
  const back = h("button", { class: "ghost", type: "button", text: "Voltar às sugestões" });
  const st = h("p", { class: "status", role: "status" });
  const img = h("img", { alt: m.n }); setImg(img, m.url, 440);
  tools.append(h("div", { class: "panel" }, h("h3", { text: "Usar esta imagem?" }), h("div", { class: "pick" }, img),
    h("p", { class: "hint", text: `${m.n} · ${m.f}` }), h("a", { href: m.u, target: "_blank", rel: "noopener", class: "hint", text: "Ver na fonte" }), h("div", { class: "actions" }, use, back), st));
  back.addEventListener("click", () => openPicker(g, tipo, tools, msg));
  use.addEventListener("click", async () => {
    use.disabled = true; st.textContent = "Gravando na aba CAPAS…";
    try { await setCapa(g, tipo, "url", m.url, `${m.f}: ${m.n}`); renderCollection(); openGame(g, "Imagem trocada."); }
    catch (e) { st.classList.add("err"); st.textContent = errMsg(e); use.disabled = false; }
  });
}

/* Editar dados na planilha: mostra antes → depois e só grava com aceite */
function editForm(g) {
  const c = cfg.colecao, p = "ed" + g.row + "-", raw = g.raw, n = raw.length;
  const label = (k) => data.head[k] || "Coluna " + colLetter(k);
  const msg = h("p", { class: "status", role: "status" });
  const review = h("button", { class: "primary", type: "submit", text: "Revisar alteração" });
  const cancel = h("button", { class: "ghost", type: "button", text: "Cancelar" });
  const confirmBox = h("div", {});
  const dl = h("datalist", { id: p + "sn" }, h("option", { value: "S" }), h("option", { value: "N" }));
  const cdl = h("datalist", { id: p + "cons" }, data.consoles.map(x => h("option", { value: x })));
  const inputs = Array.from({ length: n }, (_, k) => h("input", { id: p + k, value: raw[k], list: k === c.console ? p + "cons" : (k === c.nome || k === c.regiao) ? null : p + "sn", autocomplete: "off" }));
  const main = [c.console, c.nome, c.regiao].filter(k => k >= 0);
  const form = h("form", { class: "edit" }, dl, cdl,
    h("h3", { class: "eyebrow", text: `Editar linha ${g.row} da aba ${c.aba}` }),
    ...main.map(k => field(p + k, label(k), inputs[k])),
    h("div", { class: "grid3" }, inputs.map((el, k) => main.includes(k) ? null : field(p + k, label(k), el))),
    h("p", { class: "hint", text: "Nada é gravado agora. No próximo passo você vê exatamente o que muda e precisa confirmar." }),
    h("div", { class: "actions" }, review, cancel), msg, confirmBox);
  cancel.addEventListener("click", () => form.remove());
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const vals = inputs.map(el => String(el.value || "").trim());
    const diffs = vals.map((v, k) => v !== raw[k] ? k : -1).filter(k => k >= 0);
    msg.classList.remove("err"); confirmBox.textContent = "";
    if (!vals[c.nome]) { msg.classList.add("err"); msg.textContent = "O nome não pode ficar vazio."; return; }
    if (!diffs.length) { msg.textContent = "Nada mudou."; return; }
    msg.textContent = ""; inputs.forEach(el => el.disabled = true); review.disabled = true;
    const ok = h("input", { type: "checkbox", id: p + "ok" });
    const save = h("button", { class: "primary danger-btn", type: "button", text: "Gravar alteração", disabled: true });
    const back = h("button", { class: "ghost", type: "button", text: "Voltar e corrigir" });
    const st = h("p", { class: "status", role: "status" });
    ok.addEventListener("change", () => { save.disabled = !ok.checked; });
    back.addEventListener("click", () => { confirmBox.textContent = ""; inputs.forEach(el => el.disabled = false); review.disabled = false; });
    const renamed = diffs.includes(c.console) || diffs.includes(c.nome);
    confirmBox.append(h("div", { class: "danger", role: "alertdialog", "aria-labelledby": p + "dt" },
      h("h3", { id: p + "dt", text: "Atenção: você vai alterar dados que já estão na planilha" }),
      h("p", { text: `Linha ${g.row} da aba ${c.aba} (${g.console} · ${g.nome}). ${diffs.length === 1 ? "Esta célula será substituída" : `Estas ${diffs.length} células serão substituídas`}:` }),
      h("ul", { class: "diff" }, diffs.map(k => h("li", {}, h("b", { text: `${colLetter(k)}${g.row} · ${label(k)}: ` }), h("del", { text: raw[k] || "(vazio)" }), " → ", h("ins", { text: vals[k] || "(vazio)" })))),
      renamed ? h("p", { class: "hint", text: "Mudar console ou nome: as imagens escolhidas e o último preço acompanham o jogo." }) : null,
      h("p", { class: "hint", text: "Antes de gravar, o app confere se a linha continua igual. Para desfazer depois, use Arquivo → Histórico de versões no Google Sheets." }),
      h("label", { class: "check", for: p + "ok" }, ok, `Confirmo que quero alterar a linha ${g.row} da aba ${c.aba}`),
      h("div", { class: "actions" }, save, back), st));
    save.addEventListener("click", async () => {
      save.disabled = true; back.disabled = true; ok.disabled = true;
      const written = [], rng = `${qt(c.aba)}!A${g.row}:${colLetter(n - 1)}${g.row}`;
      try {
        st.textContent = "Conferindo a linha na planilha…";
        const cur = padRow((await getValues(rng))[0], n);
        if (cur.some((v, k) => v !== raw[k])) throw { code: "_moved" };
        for (const k of diffs) { st.textContent = `Gravando ${colLetter(k)}${g.row}…`; await putValues(`${qt(c.aba)}!${colLetter(k)}${g.row}`, [[vals[k]]]); written.push(k); }
        const after = padRow((await getValues(rng))[0], n), wrong = diffs.filter(k => after[k] !== vals[k]);
        if (renamed) await moveKeys(g, { console: vals[c.console], nome: vals[c.nome] });
        await loadData();
        const ng = data.games.find(x => x.row === g.row) || g;
        openGame(ng, wrong.length ? `Gravado, mas a planilha mostra outro valor em ${wrong.map(k => colLetter(k) + g.row).join(", ")}. Confira na planilha.` : `Alteração gravada na linha ${g.row}.`);
      } catch (err) {
        st.classList.add("err");
        st.textContent = err && err.code === "_moved" ? "A linha mudou na planilha desde a leitura. Nada foi gravado. Toque em Recarregar e edite de novo."
          : errMsg(err) + (written.length ? ` Já gravadas: ${written.map(k => colLetter(k) + g.row).join(", ")}.` : " Nada foi gravado.");
        back.disabled = false;
      }
    });
  });
  return form;
}
async function moveKeys(g, ng) {
  const oldK = gkey(g), newK = gkey(ng); if (oldK === newK) return;
  try {
    for (const tipo of ["capa", "midia"]) { const row = data.capasRows[oldK + "|" + tipo]; if (row) await putValues(`${qt(CAPAS_TAB)}!A${row}`, [[newK]]); }
    const pr = data.precosRows[oldK]; if (pr) await putValues(`${qt(PRECOS_TAB)}!A${pr}`, [[newK]]);
  } catch {}
}

/* ---------- Compras ---------- */
function consoleInput(id, value) { return [h("input", { id, value: value || "", list: id + "-list", autocomplete: "off" }), h("datalist", { id: id + "-list" }, data.consoles.map(c => h("option", { value: c })))]; }
function buyForm(r, want) {
  const p = "b-", msg = h("p", { class: "status", role: "status" });
  const btn = h("button", { class: "primary", type: "submit", text: "Incluir na lista de compras" });
  const form = h("form", { class: "edit" },
    h("div", { class: "grid2" }, field(p + "console", "Console", ...consoleInput(p + "console", r.console_planilha || (want && want.console) || "")), field(p + "regiao", "Região", h("input", { id: p + "regiao", value: r.regiao || "" }))),
    field(p + "nome", "Nome", h("input", { id: p + "nome", value: (want && want.nome) || r.titulo || "" })),
    field(p + "obs", "Observação (preço, vendedor…)", h("input", { id: p + "obs", value: "" })),
    want && cfg.procurados[want.wi].status >= 0 ? h("p", { class: "hint", text: `A linha ${want.row} da aba ${want.tab} será marcada como COMPRADO.` }) : null,
    h("div", { class: "actions" }, btn), msg);
  form.addEventListener("submit", async (e) => {
    e.preventDefault(); btn.disabled = true; msg.classList.remove("err");
    try {
      await ensureTab(cfg.compras.aba, BUY_HEAD);
      msg.textContent = "Incluindo na lista de compras…";
      await appendValues(`${qt(cfg.compras.aba)}!A1:H1`, [[today(), $(p + "console").value.trim(), $(p + "nome").value.trim(), $(p + "regiao").value.trim(), want ? want.tab : "", "COMPRADO", "", $(p + "obs").value.trim()]]);
      if (want && cfg.procurados[want.wi].status >= 0) {
        const w = cfg.procurados[want.wi], cell = `${qt(w.aba)}!${colLetter(w.status)}${want.row}`, nameCell = `${qt(w.aba)}!${colLetter(w.nome)}${want.row}`;
        const cur = await getValues(nameCell);
        if (norm((cur[0] || [])[0]) === norm(want.nome)) { await putValues(cell, [["COMPRADO"]]); msg.textContent = `Incluído. Linha ${want.row} da aba ${want.tab} marcada como COMPRADO.`; }
        else msg.textContent = `Incluído na lista de compras. A aba ${want.tab} mudou desde a leitura, então não marquei COMPRADO lá.`;
      } else msg.textContent = "Incluído na lista de compras.";
      btn.textContent = "Incluído"; setTimeout(loadData, 800);
    } catch (err) { btn.disabled = false; msg.classList.add("err"); msg.textContent = errMsg(err); }
  });
  return form;
}
function renderBuying() {
  const box = $("buylist"); box.textContent = "";
  if (!data.loaded) { box.append(h("p", { class: "empty", text: "A planilha ainda não carregou." })); return; }
  const pend = pending();
  if (!pend.length) { box.append(h("p", { class: "empty", text: "Nenhum jogo aguardando chegada." })); return; }
  pend.slice().reverse().forEach(b => {
    const open = h("button", { class: "ghost", type: "button", text: "Recebi" });
    const item = h("article", { class: "buyitem" }, h("div", { class: "top" }, h("h3", { text: b.nome }), h("span", { class: "pill media", text: b.status || "sem status" })),
      h("div", { class: "meta", text: [b.console, b.regiao, b.lista ? "lista " + b.lista : "", b.data ? "desde " + b.data : ""].filter(Boolean).join(" · ") }),
      b.obs ? h("div", { class: "hint", text: b.obs }) : null, h("div", { class: "actions" }, open));
    open.addEventListener("click", () => { open.parentElement.remove(); item.append(receiveForm(b)); });
    box.append(item);
  });
}
function receiveForm(b) {
  const c = cfg.colecao, n = c.ultima + 1, p = "r" + b.row + "-", msg = h("p", { class: "status", role: "status" });
  const btn = h("button", { class: "primary", type: "submit", text: "Transferir para a coleção" });
  const pre = Array(n).fill(""); pre[c.console] = b.console; pre[c.nome] = b.nome; if (c.regiao >= 0) pre[c.regiao] = b.regiao;
  const dl = h("datalist", { id: p + "sn" }, h("option", { value: "S" }), h("option", { value: "N" }));
  const inputs = pre.map((v, k) => h("input", { id: p + k, value: v, list: [c.console, c.nome, c.regiao].includes(k) ? null : p + "sn" }));
  const label = (k) => data.head[k] || "Coluna " + colLetter(k);
  const main = [c.console, c.nome, c.regiao].filter(k => k >= 0);
  const form = h("form", { class: "edit" }, dl, ...main.map(k => field(p + k, label(k), inputs[k])),
    h("div", { class: "grid3" }, inputs.map((el, k) => main.includes(k) ? null : field(p + k, label(k), el))),
    h("p", { class: "hint", text: `Vira uma linha nova no fim da aba ${c.aba}. Nenhuma linha existente é alterada.` }), h("div", { class: "actions" }, btn), msg);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const vals = inputs.map(el => el.value.trim());
    if (!vals[c.nome]) { msg.classList.add("err"); msg.textContent = "Preencha o nome."; return; }
    btn.disabled = true; msg.classList.remove("err"); msg.textContent = "Conferindo a lista de compras…";
    try {
      const cur = await getValues(`${qt(cfg.compras.aba)}!A${b.row}:H${b.row}`);
      if (!cur[0] || norm(cur[0][2]) !== norm(b.nome)) throw { code: "_moved" };
      if (String(cur[0][5] || "").trim().toUpperCase() === "RECEBIDO") throw { code: "_done" };
      msg.textContent = "Incluindo na coleção…";
      await appendValues(`${qt(c.aba)}!A1:${colLetter(n - 1)}1`, [vals]);
      await putValues(`${qt(cfg.compras.aba)}!F${b.row}:G${b.row}`, [["RECEBIDO", today()]]);
      msg.textContent = "Transferido para a coleção e marcado como RECEBIDO."; btn.textContent = "Transferido"; setTimeout(loadData, 800);
    } catch (err) {
      btn.disabled = false; msg.classList.add("err");
      msg.textContent = err && err.code === "_moved" ? "Essa linha da lista de compras mudou. Toque em Recarregar e tente de novo." : err && err.code === "_done" ? "Este jogo já está marcado como RECEBIDO." : errMsg(err);
    }
  });
  return form;
}

/* ---------- Gemini ---------- */
const GEM_MSG = { nokey: "Cadastre a sua chave gratuita do Gemini em Ajustes.", badkey: "A chave do Gemini foi recusada. Confira em Ajustes.", model: "O modelo escolhido não existe mais. Escolha outro em Ajustes → Listar modelos.",
  rate: "Limite gratuito do Gemini atingido por agora. Tente mais tarde.", json: "A resposta veio num formato inesperado. Tente de novo.", empty: "O Gemini não respondeu. Tente outra foto." };
const gemMsg = (e) => (e && GEM_MSG[e.code]) || ("Falha no Gemini" + (e && e.message ? ": " + e.message : "."));
async function gemini(parts, { json = true, search = false, signal } = {}) {
  const key = LS.get("geminiKey", ""); if (!key) throw { code: "nokey" };
  const model = LS.get("geminiModel", DEFAULT_MODEL);
  const body = { contents: [{ role: "user", parts }], generationConfig: { temperature: 0.2 } };
  if (json && !search) body.generationConfig.responseMimeType = "application/json";
  if (search) body.tools = [{ google_search: {} }];
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, { method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key }, body: JSON.stringify(body), signal });
  if (!r.ok) { let m = ""; try { m = (await r.json()).error.message; } catch {}
    throw { code: r.status === 429 ? "rate" : (r.status === 400 || r.status === 403) && /key/i.test(m) ? "badkey" : r.status === 404 ? "model" : "gemini", status: r.status, message: m }; }
  const d = await r.json(), cand = d.candidates && d.candidates[0];
  const text = ((cand && cand.content && cand.content.parts) || []).map(p => p.text || "").join("");
  if (!text) throw { code: "empty", message: (cand && cand.finishReason) || (d.promptFeedback && d.promptFeedback.blockReason) };
  return { text, meta: cand.groundingMetadata };
}
function parseJSON(t) {
  try { return JSON.parse(t); } catch {}
  const m = t.match(/```(?:json)?\s*([\s\S]*?)```/); if (m) { try { return JSON.parse(m[1]); } catch {} }
  const i = t.indexOf("{"), j = t.lastIndexOf("}"); if (i >= 0 && j > i) { try { return JSON.parse(t.slice(i, j + 1)); } catch {} }
  throw { code: "json" };
}

/* ---------- Identificar ---------- */
const files = [null, null], setters = [];
let ctl = null, lastIdentified = null;
function setStatus(msg, err) { const s = $("status"); s.textContent = msg || ""; s.classList.toggle("err", !!err); }
function refresh() { $("go").disabled = !(files[0] || files[1]) || !!ctl; }
function bindShot(i) {
  const input = $("foto" + (i + 1)), box = $("shot" + (i + 1)), original = box.querySelector("span").cloneNode(true);
  input.addEventListener("change", () => { const f = input.files && input.files[0]; if (f) setters[i](f); });
  const gal = $("gal" + (i + 1)); gal.addEventListener("change", () => { const f = gal.files && gal.files[0]; if (f) setters[i](f); gal.value = ""; });
  setters[i] = (f) => {
    files[i] = f; box.querySelectorAll("img,.clear,span").forEach(n => n.remove());
    const x = h("button", { type: "button", class: "clear", "aria-label": "Remover foto", text: "×" });
    x.addEventListener("click", (e) => { e.preventDefault(); e.stopPropagation(); files[i] = null; input.value = ""; box.querySelectorAll("img,.clear").forEach(n => n.remove()); box.classList.remove("filled"); box.appendChild(original.cloneNode(true)); refresh(); });
    box.append(h("img", { alt: i === 0 ? "Foto da frente" : "Foto do verso", src: URL.createObjectURL(f) }), x); box.classList.add("filled");
    setStatus(LS.get("geminiKey", "") ? "Foto pronta. Toque em Identificar." : GEM_MSG.nokey, !LS.get("geminiKey", "")); refresh();
  };
}
bindShot(0); bindShot(1);
document.addEventListener("paste", (e) => { const it = Array.from((e.clipboardData && e.clipboardData.items) || []).find(x => x.type && x.type.startsWith("image/")); const f = it && it.getAsFile(); if (!f) return; e.preventDefault(); setters[files[0] ? 1 : 0](f); });

function buildPrompt() {
  const games = data.games.map(g => `${g.id}|${g.console}|${g.nome}|${g.regiao}`).join("\n");
  const wanted = data.wanted.map(w => `${w.id}|${w.tab}|${w.console}|${w.nome}|${w.status}`).join("\n");
  const buying = data.buying.map(b => `${b.id}|${b.console}|${b.nome}|${b.regiao}|${b.status}`).join("\n");
  return `Você é um especialista em colecionismo de videogames, incluindo lançamentos brasileiros (Tec Toy, Playtronic, CCE, Dactar, Polivox, Philips Odyssey).
As imagens são fotos de celular de UM item: jogo, console ou acessório. Se houver duas fotos, a segunda é o verso ou outro ângulo do MESMO item.
Identifique com precisão: título, plataforma, região, edição. Use formato do cartucho, códigos de produto (SHVC-, SNS-, HVC-, SCUS-, SLUS-, SLES-, CUSA- etc.), idioma, logos.
Depois compare com as listas do colecionador. Os nomes foram digitados à mão: podem ter erros de grafia, abreviações (MD = Mega Drive, MS = Master System, SFC = Super Famicom, GC = GameCube, X360, XONE...) ou títulos alternativos. Conte como correspondência o mesmo jogo na mesma plataforma; não confunda jogos diferentes da mesma série. SFC/SNES e FAMICOM/NES contam como a mesma família para "já tenho", mas explique a diferença de região em "observacao_match".
CONSOLES usados na planilha (use um destes códigos em console_planilha, ou crie um no mesmo estilo): ${data.consoles.join(", ")}

COLEÇÃO (jogos que ele JÁ TEM) — id|console|nome|região:
${games || "(vazia)"}

PROCURADOS — id|aba|console|nome|status:
${wanted || "(vazia)"}

LISTA DE COMPRAS — id|console|nome|região|status:
${buying || "(vazia)"}

Responda SOMENTE com JSON:
{"identificado": boolean, "tipo": "jogo"|"console"|"acessório"|"outro", "titulo": string|null, "plataforma": string|null, "console_planilha": string|null,
 "fabricante": string|null, "regiao": string|null, "ano": number|null, "modelo_codigo": string|null, "formato": string|null, "estado_aparente": string|null,
 "confianca": "alta"|"média"|"baixa", "pistas": [string], "alternativas": [{"titulo": string, "plataforma": string}], "dica_nova_foto": string|null,
 "ja_tenho": [ids G...], "na_compra": [ids B...], "procurado": [ids W...], "observacao_match": string|null}
Use listas vazias quando não houver correspondência. Textos em português do Brasil.`;
}
$("go").addEventListener("click", async () => {
  const imgs = files.filter(Boolean); if (!imgs.length) return;
  ctl = new AbortController(); refresh(); $("stop").hidden = false; $("verdict").textContent = ""; $("price").textContent = "";
  setStatus("Analisando a foto e conferindo a planilha… leva de 5 a 40 segundos.");
  try {
    const parts = [{ text: buildPrompt() }];
    for (const f of imgs) { const b = await shrink(f); parts.push({ inline_data: { mime_type: "image/jpeg", data: await blobB64(b) } }); }
    const r = parseJSON((await gemini(parts, { signal: ctl.signal })).text);
    renderResult(r); renderVerdict(r); lastIdentified = r; setStatus("Pronto.");
    if (r.identificado !== false && r.titulo) lookupPrice({ titulo: r.titulo, plataforma: r.plataforma, regiao: r.regiao }, $("price"), null);
  } catch (e) { setStatus(e && e.name === "AbortError" ? "Cancelado." : gemMsg(e), !(e && e.name === "AbortError")); }
  finally { ctl = null; $("stop").hidden = true; refresh(); }
});
$("stop").addEventListener("click", () => ctl?.abort());
function renderResult(r) {
  const out = $("result"); out.textContent = "";
  const conf = String(r.confianca || "baixa").normalize("NFD").replace(/[̀-ͯ]/g, "");
  const dl = h("dl");
  [["Fabricante", r.fabricante], ["Ano", r.ano], ["Código", r.modelo_codigo], ["Estado", r.estado_aparente]].forEach(([k, v]) => { if (v !== null && v !== undefined && v !== "") dl.append(h("dt", { text: k }), h("dd", { text: String(v) })); });
  const list = (title, items) => items && items.length ? h("div", { class: "block" }, h("h3", { text: title }), h("ul", {}, items.map(t => h("li", { text: t })))) : null;
  out.append(h("article", { class: "label" }, h("div", { class: "label-top" }, h("span", { text: (r.tipo || "item") + " · " + (r.formato || "") }), h("span", { class: "pill " + conf, text: "confiança " + (r.confianca || "?") })),
    h("div", { class: "label-body" }, h("h2", { class: "title", text: r.identificado === false ? "Não foi possível identificar" : (r.titulo || "Sem título") }),
      h("p", { class: "sub", text: [r.plataforma, r.regiao].filter(Boolean).join(" · ") }), dl, list("Por que", r.pistas),
      list("Pode ser também", (r.alternativas || []).map(a => [a.titulo, a.plataforma].filter(Boolean).join(" · "))),
      r.dica_nova_foto ? h("p", { class: "tip", text: "Para confirmar: " + r.dica_nova_foto }) : null)));
}
const byId = (arr, ids) => (Array.isArray(ids) ? ids : []).map(id => arr.find(x => x.id === String(id))).filter(Boolean);
function renderVerdict(r) {
  const out = $("verdict"); out.textContent = "";
  if (r.identificado === false) return;
  const have = byId(data.games, r.ja_tenho), buying = byId(data.buying, r.na_compra), wanted = byId(data.wanted, r.procurado);
  const note = r.observacao_match ? h("p", { class: "hint", text: r.observacao_match }) : null;
  let box;
  if (have.length) {
    const g = have[0], msg = h("p", { class: "status", role: "status" });
    const use = h("button", { class: "ghost", type: "button", text: "Usar esta foto como foto do meu cartucho" });
    use.addEventListener("click", async () => { use.disabled = true; if (await savePhoto(g, files[0] || files[1], msg, "midia")) { use.textContent = "Foto salva"; msg.textContent = "Foto salva no seu Google Drive."; } else use.disabled = false; });
    box = h("div", { class: "verdict have" }, h("h2", { text: "Você já tem este jogo" }),
      h("ul", { class: "rows" }, have.map(x => h("li", {}, `${x.console} · ${x.nome} · ${x.regiao || "sem região"}`, h("small", { text: `${cfg.colecao.aba}, linha ${x.row}` })))),
      note, h("div", { class: "actions" }, use), msg, h("details", {}, h("summary", { text: "Quero outra cópia mesmo assim" }), buyForm(r, wanted[0])));
  } else if (buying.length) {
    box = h("div", { class: "verdict buying" }, h("h2", { text: "Já está na lista de compras" }), h("ul", { class: "rows" }, buying.map(b => h("li", {}, `${b.console} · ${b.nome} · ${b.regiao}`, h("small", { text: `${b.status || "sem status"} · incluído em ${b.data}` })))), note);
  } else if (wanted.length) {
    box = h("div", { class: "verdict want" }, h("h2", { text: "Você não tem, e está na sua lista de procurados" }),
      h("ul", { class: "rows" }, wanted.map(x => h("li", {}, `${x.tab} · ${x.nome}`, h("small", { text: [x.info, x.status ? "Status: " + x.status : "", "linha " + x.row].filter(Boolean).join(" · ") })))),
      note, h("p", { text: "Quer comprar? Inclua na lista de compras." }), buyForm(r, wanted[0]));
  } else {
    box = h("div", { class: "verdict new" }, h("h2", { text: "Você não tem este jogo" }), h("p", { class: "hint", text: "Ele não está na coleção nem nas listas de procurados." }), note, buyForm(r, null));
  }
  out.append(box);
}

/* ---------- Preço ---------- */
const BR_STORES = [
  { loja: "Mercado Livre", d: ["mercadolivre.com.br"] }, { loja: "Shopee", d: ["shopee.com.br"] }, { loja: "OLX", d: ["olx.com.br"] }, { loja: "Enjoei", d: ["enjoei.com.br"] },
  { loja: "Varejistas", d: ["amazon.com.br", "magazineluiza.com.br", "americanas.com.br", "kabum.com.br", "casasbahia.com.br"] }, { loja: "Lojas de games", d: null }
];
async function tavily(query, domains, signal) {
  const r = await fetch("https://api.tavily.com/search", { method: "POST", signal, headers: { "Content-Type": "application/json", Authorization: "Bearer " + LS.get("tavilyKey", "") },
    body: JSON.stringify({ query, search_depth: "basic", max_results: 8, ...(domains ? { include_domains: domains } : {}) }) });
  if (!r.ok) throw { code: "tavily", status: r.status };
  return ((await r.json()).results || []).map(x => `URL: ${x.url}\nTÍTULO: ${x.title || ""}\n${String(x.content || "").slice(0, 1200)}`).join("\n---\n") || "(nenhum resultado)";
}
const PRICE_RULES = (name, plat, reg) => `Hoje é ${today()}. Item de coleção: "${name}" — plataforma ${plat || "?"}, região ${reg || "?"}.
Regras:
- Mundo: PriceCharting da versão certa (Super Famicom ≠ Super Nintendo ≠ PAL; Famicom ≠ NES): loose (só cartucho/disco), CIB (completo), novo (lacrado), em dólares; e a mediana das vendas concluídas do eBay, se houver.
- Brasil: liste CADA anúncio individual com preço em reais deste mesmo jogo nesta mesma plataforma (até 40), de Mercado Livre, Shopee, OLX, Enjoei, Amazon, Magalu, Americanas, KaBuM!, Casas Bahia e lojas de games.
  tipo = "original", "repro" (repro, réplica, paralelo, fan made, mídia gravada), "outro" (outro jogo, console, acessório, coletânea, outra plataforma) ou "duvida";
  condicao = "solto", "completo", "lacrado" ou "?". Use o preço total à vista. Não invente anúncios nem valores: só o que aparece nas fontes.
- Cotação: o dólar comercial mais recente.
Responda só com JSON:
{"mundo":{"loose_usd":número|null,"cib_usd":número|null,"novo_usd":número|null,"ebay_usd":número|null,"url":string|null,"observacao":string|null},
 "cotacao":{"usd_brl":número|null,"fonte":string|null,"data":string|null},
 "anuncios":[{"loja":string,"titulo":string,"url":string,"preco_brl":número,"tipo":"original"|"repro"|"outro"|"duvida","condicao":"solto"|"completo"|"lacrado"|"?"}],
 "observacao":string|null}`;
function summarize(ads) {
  const ok = (ads || []).filter(a => a && typeof a.preco_brl === "number" && a.preco_brl > 0 && a.tipo === "original");
  const m0 = median(ok.map(a => a.preco_brl));
  const used = m0 ? ok.filter(a => a.preco_brl >= m0 / 3 && a.preco_brl <= m0 * 3) : [];
  const stat = (xs) => xs.length ? { n: xs.length, med: median(xs), min: Math.min(...xs), max: Math.max(...xs) } : null;
  const by = (f) => { const o = {}; used.forEach(a => (o[f(a)] ||= []).push(a.preco_brl)); return Object.fromEntries(Object.entries(o).map(([k, v]) => [k, stat(v)])); };
  return { geral: stat(used.map(a => a.preco_brl)), cond: by(a => a.condicao || "?"), loja: by(a => a.loja || "?"), usados: used, descartados: (ads || []).length - used.length };
}
function priceShell(target, ...kids) { target.textContent = ""; const box = h("div", { class: "price" }, h("h2", { text: "Preço atual" }), ...kids); target.append(box); return box; }
const priceCtls = new WeakMap();
async function lookupPrice(r, target, key) {
  priceCtls.get(target)?.abort(); const c = new AbortController(); priceCtls.set(target, c);
  const useTav = !!LS.get("tavilyKey", ""), name = r.titulo, plat = r.plataforma || "";
  const msg = h("p", { class: "status", role: "status", text: useTav ? "Pesquisando nas lojas pelo Tavily…" : "Pesquisando nas lojas pela busca do Google no Gemini…" });
  const stop = h("button", { class: "ghost", type: "button", text: "Parar" }); stop.addEventListener("click", () => c.abort());
  priceShell(target, msg, h("div", { class: "actions" }, stop));
  try {
    let p, fontes = [];
    if (useTav) {
      const jobs = [tavily(`${name} ${plat} pricecharting`, ["pricecharting.com", "ebay.com"], c.signal), tavily("cotação dólar comercial hoje", null, c.signal),
        ...BR_STORES.map(s => tavily(`${name} ${plat} ${s.d ? "" : "original loja games"}`.trim(), s.d, c.signal))];
      const txt = (await Promise.allSettled(jobs)).map(x => x.status === "fulfilled" ? x.value : "(busca falhou)");
      msg.textContent = "Lendo os anúncios…";
      p = parseJSON((await gemini([{ text: PRICE_RULES(name, plat, r.regiao) + `\n\nTRECHOS — PRICECHARTING / EBAY:\n${txt[0]}\n\nTRECHOS — DÓLAR:\n${txt[1]}\n\n` + BR_STORES.map((s, i) => `TRECHOS — ${s.loja.toUpperCase()}:\n${txt[i + 2]}`).join("\n\n") }], { signal: c.signal })).text);
    } else {
      const g = await gemini([{ text: PRICE_RULES(name, plat, r.regiao) + "\nPesquise na web (busque cada loja: site:mercadolivre.com.br, site:shopee.com.br, site:olx.com.br, site:enjoei.com.br, varejistas, pricecharting.com e ebay.com) antes de responder." }], { search: true, signal: c.signal });
      p = parseJSON(g.text);
      fontes = ((g.meta && g.meta.groundingChunks) || []).map(x => x.web).filter(Boolean).slice(0, 8);
    }
    const result = { ...p, consulta: new Date().toISOString(), fontes };
    renderPrice(result, target, r, key);
    if (key) savePrice(key, result).catch(() => {});
  } catch (e) {
    if (e && e.name === "AbortError") { target.textContent = ""; return; }
    const retry = h("button", { class: "ghost", type: "button", text: "Buscar preço" }); retry.addEventListener("click", () => lookupPrice(r, target, key));
    priceShell(target, h("p", { class: "status err", text: e && e.code === "tavily" ? "O Tavily recusou a busca (chave ou limite). Confira em Ajustes." : gemMsg(e) }), h("div", { class: "actions" }, retry));
  }
}
async function savePrice(key, result) {
  await ensureTab(PRECOS_TAB, ["CHAVE", "CONSULTA", "DADOS"]);
  const json = JSON.stringify(result).slice(0, 45000), row = data.precosRows[key], vals = [[key, result.consulta, json]];
  if (row) await putValues(`${qt(PRECOS_TAB)}!A${row}:C${row}`, vals);
  else { const r = await appendValues(`${qt(PRECOS_TAB)}!A1:C1`, vals); const m = /![A-Z]+(\d+)/.exec((r.updates || {}).updatedRange || ""); if (m) data.precosRows[key] = Number(m[1]); }
  data.precos[key] = { em: result.consulta, json };
}
const COND = { solto: "Solto", completo: "Completo", lacrado: "Lacrado", "?": "Não informado" };
function renderPrice(p, target, r, key) {
  const m = p.mundo || {}, c = p.cotacao || {}, s = summarize(p.anuncios);
  const rate = typeof c.usd_brl === "number" ? c.usd_brl : null;
  const conv = (v) => rate && typeof v === "number" ? brl(v * rate) : "";
  const prow = (label, v) => h("div", { class: "prow" }, h("span", { text: label }), h("div", {}, h("b", { text: conv(v) || usd(v) }), conv(v) ? h("small", { text: " " + usd(v) }) : null));
  const srow = (label, st) => h("div", { class: "prow" }, h("span", { text: `${label}${st ? " · " + st.n : ""}` }), h("div", {}, h("b", { text: st ? brl(st.med) : "—" }), st && st.n > 1 ? h("small", { text: ` ${brl(st.min)}–${brl(st.max)}` }) : null));
  const world = h("div", { class: "pbox" }, h("h3", { text: "Mundo · PriceCharting / eBay" }), prow("Solto", m.loose_usd), prow("Completo (CIB)", m.cib_usd), prow("Lacrado", m.novo_usd), typeof m.ebay_usd === "number" ? prow("eBay (vendidos)", m.ebay_usd) : null);
  const br = h("div", { class: "pbox" }, h("h3", { text: "Brasil · mediana dos anúncios" }), srow("Geral", s.geral), ...["solto", "completo", "lacrado", "?"].filter(k => s.cond[k]).map(k => srow(COND[k], s.cond[k])),
    s.geral ? null : h("p", { class: "hint", text: "Nenhum anúncio de jogo original com preço nos resultados." }));
  const lojas = Object.entries(s.loja).sort((a, b) => b[1].n - a[1].n);
  const ads = s.usados.slice().sort((a, b) => a.preco_brl - b.preco_brl).slice(0, 12).map(a => h("li", {}, h("a", { href: a.url, target: "_blank", rel: "noopener", text: `${brl(a.preco_brl)} · ${a.loja || "?"} · ${COND[a.condicao] || "?"}` }), h("small", { text: " " + (a.titulo || "") })));
  const links = [...(m.url ? [{ uri: m.url, title: "PriceCharting" }] : []), ...(p.fontes || [])].map(f => h("li", {}, h("a", { href: f.uri, target: "_blank", rel: "noopener", text: f.title || f.uri })));
  const again = h("button", { class: "ghost", type: "button", text: "Atualizar preço" }); again.addEventListener("click", () => lookupPrice(r, target, key));
  priceShell(target, h("div", { class: "cols" }, br, world),
    lojas.length ? h("div", { class: "pbox" }, h("h3", { text: "Por loja" }), ...lojas.map(([k, st]) => srow(k, st))) : null,
    h("p", { class: "hint", text: (rate ? `Dólar: ${brl(rate)}${c.fonte ? " (" + c.fonte + ")" : ""}. ` : "Cotação do dólar não encontrada. ") + `Consulta em ${new Date(p.consulta || Date.now()).toLocaleDateString("pt-BR")}. Mediana só de anúncios de jogos originais; ${s.descartados} anúncio(s) ficaram de fora (repro, outros itens ou fora da curva).` }),
    ads.length ? h("details", {}, h("summary", { text: `Anúncios usados no cálculo (${s.usados.length})` }), h("ul", { class: "sources" }, ads)) : null,
    links.length ? h("details", {}, h("summary", { text: "Fontes" }), h("ul", { class: "sources" }, links)) : null,
    p.observacao ? h("p", { class: "hint", text: p.observacao }) : null, h("div", { class: "actions" }, again));
}
function showSavedPrice(g, target) {
  const r = { titulo: g.nome, plataforma: platformOf(g.console) ? PLAT_LABEL(platformOf(g.console)) : g.console, regiao: g.regiao };
  const saved = data.precos[gkey(g)];
  if (saved) { try { renderPrice(JSON.parse(saved.json), target, r, gkey(g)); return; } catch {} }
  const btn = h("button", { class: "ghost", type: "button", text: "Buscar preço" }); btn.addEventListener("click", () => lookupPrice(r, target, gkey(g)));
  priceShell(target, h("p", { class: "hint", text: "Mercado Livre, Shopee, OLX, Enjoei, Amazon, Magalu, Americanas, KaBuM!, Casas Bahia, lojas de games, PriceCharting e eBay." }), h("div", { class: "actions" }, btn));
}

/* ---------- Configuração inicial (assistente) ---------- */
function showOnly(view) {
  for (const v of ["setup", "id", "col", "buy", "cfg"]) $("view-" + v).hidden = v !== view;
  $("tabs").hidden = view === "setup";
  for (const t of ["id", "col", "buy", "cfg"]) $("tab-" + t).setAttribute("aria-selected", String(view === t));
}
function setupPanel(...kids) { const v = $("view-setup"); v.textContent = ""; v.append(h("div", { class: "panel" }, ...kids)); showOnly("setup"); }

function screenNoClient() {
  setupPanel(h("h2", { text: "Instalação incompleta" }),
    h("p", { text: "Este app ainda não tem a identificação de login do Google (Client ID)." }),
    h("ol", { class: "steps" }, h("li", { text: "No seu repositório do GitHub, abra a aba Actions." }), h("li", { text: "Escolha “2. Configurar o login do Google” e toque em Run workflow." }),
      h("li", { text: "Cole o Client ID criado no Google Cloud e confirme." }), h("li", { text: "Espere uns 2 minutos e recarregue esta página." })),
    h("p", { class: "hint" }, "Passo a passo completo no ", h("a", { href: "https://github.com/VPRODC83/gamecatalog-instalador#readme", target: "_blank", rel: "noopener", text: "guia de instalação" }), "."));
}
function screenLogin(err) {
  const btn = h("button", { class: "primary", type: "button", text: "Entrar com Google" });
  const st = h("p", { class: "status" + (err ? " err" : ""), role: "status", text: err || "" });
  btn.addEventListener("click", async () => { btn.disabled = true; st.textContent = "Abrindo o login do Google…";
    try { await signIn(); afterLogin(); } catch (e) { btn.disabled = false; st.classList.add("err"); st.textContent = "Não foi possível entrar: " + (e.message || "tente de novo") + ". Se aparecer “acesso bloqueado”, confira se o seu e-mail está em Público-alvo → Usuários de teste no Google Cloud."; } });
  setupPanel(h("h2", { text: "Entrar" }), h("p", { text: "O app usa a sua conta Google para ler e gravar a sua planilha da coleção e guardar fotos numa pasta do seu Google Drive." }),
    h("p", { class: "hint", text: "Como você mesmo criou este app no seu Google Cloud, o Google pode avisar que ele “não foi verificado”. É esperado: toque em Avançado → Acessar." }), h("div", { class: "actions" }, btn), st);
}
async function afterLogin() {
  if (!sid()) return screenSheet();
  try { cfg = await loadCfg(); } catch (e) { return screenSheet(errMsg(e)); }
  if (!cfg) return screenMapping();
  startMain();
}
function screenSheet(err) {
  const url = h("input", { id: "su", placeholder: "https://docs.google.com/spreadsheets/d/…", autocomplete: "off" });
  const use = h("button", { class: "primary", type: "button", text: "Usar esta planilha" });
  const create = h("button", { class: "ghost", type: "button", text: "Criar uma planilha nova" });
  const st = h("p", { class: "status" + (err ? " err" : ""), role: "status", text: err || "" });
  use.addEventListener("click", async () => {
    const m = /\/d\/([A-Za-z0-9_-]{20,})/.exec(url.value) || /^([A-Za-z0-9_-]{20,})$/.exec(url.value.trim());
    if (!m) { st.classList.add("err"); st.textContent = "Cole o link completo da planilha."; return; }
    st.classList.remove("err"); st.textContent = "Abrindo a planilha…";
    try { await sheetMeta(m[1]); LS.set("sheetId", m[1]); afterLogin(); } catch (e) { st.classList.add("err"); st.textContent = errMsg(e); }
  });
  create.addEventListener("click", async () => {
    st.textContent = "Criando a planilha no seu Google Drive…";
    try {
      const d = await gapi(SHEETS, { method: "POST", body: JSON.stringify({ properties: { title: "Coleção de games" }, sheets: [{ properties: { title: "COLECAO" } }, { properties: { title: "PROCURADOS" } }, { properties: { title: "COMPRAS" } }] }) });
      LS.set("sheetId", d.spreadsheetId);
      await putValues("COLECAO!A1:H1", [["CONSOLE", "NOME", "REGIAO", "CIB", "CAIXA", "MANUAL", "CONDICAO", "OBS"]]);
      await putValues("PROCURADOS!A1:D1", [["CONSOLE", "NOME", "STATUS", "OBS"]]);
      await putValues("COMPRAS!A1:H1", [BUY_HEAD]);
      cfg = { v: 1, colecao: { aba: "COLECAO", console: 0, nome: 1, regiao: 2, ultima: 7 }, procurados: [{ aba: "PROCURADOS", nome: 1, status: 2, consoleCol: 0, console: "", info: 3 }], compras: { aba: "COMPRAS" }, plataformas: {} };
      await saveCfg(); startMain();
    } catch (e) { st.classList.add("err"); st.textContent = errMsg(e); }
  });
  setupPanel(h("h2", { text: "Sua planilha" }), h("p", { text: "Cole o link da planilha da sua coleção (Google Sheets) ou crie uma planilha nova já no formato do app." }),
    field("su", "Link da planilha", url), h("div", { class: "actions" }, use, create), st,
    h("p", { class: "hint", text: "A planilha continua sua e no seu Drive. O app só lê e grava nela quando você usa as funções." }));
}
async function screenMapping() {
  setupPanel(h("p", { class: "status", text: "Lendo as abas da planilha…" }));
  let meta; try { meta = await sheetMeta(); } catch (e) { return screenSheet(errMsg(e)); }
  const tabs = meta.sheets.map(s => s.properties.title).filter(t => ![CFG_TAB, CAPAS_TAB, PRECOS_TAB].includes(t));
  const heads = {}; (await batchGet(tabs.map(t => `${qt(t)}!A1:Z1`))).forEach((v, i) => { heads[tabs[i]] = padRow(v[0], Math.max(1, (v[0] || []).length)); });
  const colSel = (tab, guessRe, allowNone, id) => {
    const s = h("select", { id }, allowNone ? h("option", { value: "-1", text: "(nenhuma)" }) : null, (heads[tab] || []).map((hd, i) => h("option", { value: String(i), text: `${colLetter(i)} · ${hd || "(sem título)"}` })));
    const gi = (heads[tab] || []).findIndex(hd => guessRe.test(norm(hd))); s.value = gi >= 0 ? String(gi) : (allowNone ? "-1" : "0"); return s;
  };
  const tabSel = (id, guessRe) => { const s = h("select", { id }, tabs.map(t => h("option", { value: t, text: t }))); const gi = tabs.find(t => guessRe.test(norm(t))); if (gi) s.value = gi; return s; };
  const colTab = tabSel("m-col", /^(GAMES|COLECAO|JOGOS|COLLECTION)/);
  const colBox = h("div", {});
  const drawCol = () => { colBox.textContent = ""; const t = colTab.value;
    colBox.append(h("div", { class: "grid2" }, field("m-c", "Coluna do console", colSel(t, /^(CONSOLE|PLATAFORMA|SISTEMA|PLATFORM)/, false, "m-c")), field("m-n", "Coluna do nome", colSel(t, /^(NOME|JOGO|TITULO|NAME|GAME)/, false, "m-n"))),
      field("m-r", "Coluna da região", colSel(t, /^(REGIAO|REGION|REG)/, true, "m-r")),
      h("p", { class: "hint", text: `As outras colunas com título (${(heads[t] || []).filter(Boolean).length} no total) aparecem como detalhes do jogo (ex.: CIB, CAIXA, MANUAL).` })); };
  colTab.addEventListener("change", drawCol); drawCol();
  const wantBox = h("div", { style: "display:flex;flex-direction:column;gap:10px" });
  const wantRows = [];
  const addWant = (tab) => {
    const t = tabSel("m-w" + wantRows.length, /^$/); t.value = tab || tabs[0];
    const box = h("div", { class: "panel" });
    const rowObj = { t, box, sels: {} };
    const draw = () => { box.querySelectorAll(".dyn").forEach(n => n.remove()); const tb = t.value, i = wantRows.indexOf(rowObj);
      rowObj.sels = { n: colSel(tb, /^(NOME|JOGO|TITULO|NAME)/, false, `m-wn${i}`), s: colSel(tb, /^(STATUS|SITUACAO)/, true, `m-ws${i}`), c: colSel(tb, /^(CONSOLE|PLATAFORMA)/, true, `m-wc${i}`), i: colSel(tb, /^(OBS|INFO|NOTA)/, true, `m-wi${i}`) };
      rowObj.fixed = h("input", { id: `m-wf${i}`, value: /^(SFC|FC|NES|SNES|MD|MS|ODYSSEY|N64|PS\d?)$/.test(norm(tb)) ? tb : "", placeholder: "ex.: SFC" });
      box.append(h("div", { class: "grid2 dyn" }, field(`m-wn${i}`, "Coluna do nome", rowObj.sels.n), field(`m-ws${i}`, "Coluna do status (COMPRADO)", rowObj.sels.s)),
        h("div", { class: "grid2 dyn" }, field(`m-wc${i}`, "Coluna do console", rowObj.sels.c), field(`m-wf${i}`, "ou console fixo da aba", rowObj.fixed)),
        h("div", { class: "dyn" }, field(`m-wi${i}`, "Coluna de observação", rowObj.sels.i))); };
    const rm = h("button", { class: "link", type: "button", text: "remover" }); rm.addEventListener("click", () => { box.remove(); wantRows.splice(wantRows.indexOf(rowObj), 1); });
    box.append(h("div", { class: "row" }, field("m-w" + wantRows.length, "Aba de procurados", t), rm));
    t.addEventListener("change", draw); wantRows.push(rowObj); wantBox.append(box); draw();
  };
  tabs.filter(t => /^(SFC|FC|ODYSSEY|PROCURADOS|WANTED|WISHLIST|DESEJOS)$/.test(norm(t))).forEach(addWant);
  const addW = h("button", { class: "ghost", type: "button", text: "Adicionar aba de procurados" }); addW.addEventListener("click", () => addWant());
  const buyTab = h("input", { id: "m-b", value: tabs.find(t => /^(BUYING|COMPRAS)$/.test(norm(t))) || "COMPRAS" });
  const save = h("button", { class: "primary", type: "button", text: "Salvar e começar" });
  const st = h("p", { class: "status", role: "status" });
  save.addEventListener("click", async () => {
    const t = colTab.value, last = Math.max(0, (heads[t] || []).reduce((m, hd, i) => hd ? i : m, 0));
    const c = { aba: t, console: +$("m-c").value, nome: +$("m-n").value, regiao: +$("m-r").value, ultima: last };
    if (c.console === c.nome) { st.classList.add("err"); st.textContent = "Console e nome precisam ser colunas diferentes."; return; }
    cfg = { v: 1, colecao: c, procurados: wantRows.map(w => ({ aba: w.t.value, nome: +w.sels.n.value, status: +w.sels.s.value, consoleCol: +w.sels.c.value, console: w.fixed.value.trim(), info: +w.sels.i.value })),
      compras: { aba: buyTab.value.trim() || "COMPRAS" }, plataformas: (cfg && cfg.plataformas) || {}, pastaFotos: cfg && cfg.pastaFotos };
    st.classList.remove("err"); st.textContent = "Salvando a configuração na aba APP_CONFIG da planilha…";
    try { await saveCfg(); await ensureTab(cfg.compras.aba, BUY_HEAD); startMain(); } catch (e) { st.classList.add("err"); st.textContent = errMsg(e); }
  });
  setupPanel(h("h2", { text: "Como é a sua planilha" }), h("p", { class: "hint", text: "Diga ao app onde está cada informação. Isso fica salvo na própria planilha (aba APP_CONFIG), então vale para todos os seus aparelhos." }),
    h("h3", { text: "Coleção (jogos que você tem)" }), field("m-col", "Aba", colTab), colBox,
    h("h3", { text: "Procurados (opcional)" }), wantBox, h("div", { class: "actions" }, addW),
    h("h3", { text: "Lista de compras" }), field("m-b", "Aba (criada se não existir)", buyTab),
    h("div", { class: "actions" }, save), st);
}

/* ---------- Ajustes ---------- */
function renderCfg() {
  const v = $("view-cfg"); v.textContent = "";
  const gk = h("input", { id: "k-g", type: "password", value: LS.get("geminiKey", ""), autocomplete: "off", placeholder: "AIza…" });
  const gm = h("input", { id: "k-m", value: LS.get("geminiModel", DEFAULT_MODEL), list: "k-ml", autocomplete: "off" });
  const gml = h("datalist", { id: "k-ml" });
  const tk = h("input", { id: "k-t", type: "password", value: LS.get("tavilyKey", ""), autocomplete: "off", placeholder: "tvly-…" });
  const th = h("input", { type: "checkbox", id: "k-th" }); th.checked = LS.get("thumbs", true);
  const st = h("p", { class: "status", role: "status" });
  const saveK = h("button", { class: "primary", type: "button", text: "Salvar chaves" });
  const test = h("button", { class: "ghost", type: "button", text: "Testar Gemini" });
  const list = h("button", { class: "ghost", type: "button", text: "Listar modelos" });
  saveK.addEventListener("click", () => { LS.set("geminiKey", gk.value.trim()); LS.set("geminiModel", gm.value.trim() || DEFAULT_MODEL); LS.set("tavilyKey", tk.value.trim()); LS.set("thumbs", th.checked); st.classList.remove("err"); st.textContent = "Salvo neste aparelho."; });
  test.addEventListener("click", async () => { saveK.click(); st.textContent = "Testando…"; try { const r = await gemini([{ text: "Responda só com JSON {\"ok\": true}" }]); st.textContent = "Gemini funcionando (" + LS.get("geminiModel", DEFAULT_MODEL) + "): " + r.text.slice(0, 40); } catch (e) { st.classList.add("err"); st.textContent = gemMsg(e); } });
  list.addEventListener("click", async () => { saveK.click(); st.textContent = "Buscando modelos…";
    try { const r = await fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", { headers: { "x-goog-api-key": gk.value.trim() } }); const d = await r.json();
      const ms = (d.models || []).filter(m => (m.supportedGenerationMethods || []).includes("generateContent")).map(m => m.name.replace(/^models\//, ""));
      gml.textContent = ""; ms.forEach(m => gml.append(h("option", { value: m }))); st.textContent = ms.length ? `${ms.length} modelos disponíveis. Toque no campo Modelo para escolher (os “flash” são os gratuitos mais rápidos).` : "Nenhum modelo retornado. Confira a chave.";
    } catch { st.classList.add("err"); st.textContent = "Não consegui listar os modelos."; } });
  const plats = h("div", { class: "maptable" });
  data.consoles.forEach(c => { const s = h("select", { "aria-label": "Plataforma de " + c }, h("option", { value: "", text: "(sem capas automáticas)" }), LIBRETRO.map(p => h("option", { value: p.repo, text: p.nome })));
    s.value = platformOf(c);
    s.addEventListener("change", async () => { cfg.plataformas = { ...(cfg.plataformas || {}), [norm(c)]: s.value }; Object.keys(autoCache).forEach(k => delete autoCache[k]); try { await saveCfg(); renderCollection(); } catch (e) { st.textContent = errMsg(e); } });
    plats.append(h("span", { text: c }), s); });
  const remap = h("button", { class: "ghost", type: "button", text: "Refazer o mapeamento da planilha" }); remap.addEventListener("click", () => screenMapping());
  const other = h("button", { class: "ghost", type: "button", text: "Usar outra planilha" }); other.addEventListener("click", () => { LS.set("sheetId", ""); cfg = null; screenSheet(); });
  const out = h("button", { class: "ghost", type: "button", text: "Sair da conta Google" }); out.addEventListener("click", () => { try { google.accounts.oauth2.revoke(app.token, () => {}); } catch {} app.token = null; screenLogin(); });
  v.append(h("div", { style: "display:flex;flex-direction:column;gap:14px" },
    h("div", { class: "panel" }, h("h2", { text: "Chaves gratuitas" }),
      h("p", { class: "hint" }, "Gemini (identificar e ler anúncios): crie em ", h("a", { href: "https://aistudio.google.com/apikey", target: "_blank", rel: "noopener", text: "aistudio.google.com/apikey" }), ". No plano gratuito o Google pode usar o que você envia para melhorar os produtos dele; não envie nada sigiloso."),
      field("k-g", "Chave do Gemini", gk), field("k-m", "Modelo", gm, gml),
      h("p", { class: "hint" }, "Tavily (opcional, busca de preços mais completa, 1.000 buscas/mês grátis): ", h("a", { href: "https://app.tavily.com", target: "_blank", rel: "noopener", text: "app.tavily.com" }), ". Sem ela, o app usa a busca do Google dentro do Gemini."),
      field("k-t", "Chave do Tavily", tk),
      h("label", { class: "row hint" }, th, "Economizar dados: miniaturas das capas via images.weserv.nl"),
      h("div", { class: "actions" }, saveK, test, list), st,
      h("p", { class: "hint", text: "As chaves ficam só neste aparelho (não vão para a planilha nem para o GitHub)." })),
    h("div", { class: "panel" }, h("h2", { text: "Plataformas das capas automáticas" }), h("p", { class: "hint", text: "Para cada console da sua planilha, o acervo do libretro-thumbnails usado nas capas." }), plats),
    h("div", { class: "panel" }, h("h2", { text: "Planilha e conta" }), h("p", { class: "hint" }, "Planilha: ", h("a", { href: `https://docs.google.com/spreadsheets/d/${sid()}/edit`, target: "_blank", rel: "noopener", text: data.title || "abrir" })),
      h("div", { class: "actions" }, remap, other, out)),
    h("p", { class: "hint", text: `Versão ${app.version || "?"}. Atualizações chegam sozinhas pelo seu repositório do GitHub.` })));
}

/* ---------- abas e início ---------- */
function showTab(t) { showOnly(t); if (t === "cfg") renderCfg(); }
for (const t of ["id", "col", "buy", "cfg"]) $("tab-" + t).addEventListener("click", () => showTab(t));
$("relogin").addEventListener("click", async () => { try { await signIn(); loadData(); } catch {} });
function startMain() { showTab("id"); setStatus(LS.get("geminiKey", "") ? "Tire ou escolha uma foto para começar." : "Para identificar por foto, cadastre a chave gratuita do Gemini em Ajustes.", !LS.get("geminiKey", "")); loadData(); }

(async function boot() {
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  try { const c = await (await fetch("config.json", { cache: "no-store" })).json(); app.clientId = c.google_client_id || ""; } catch {}
  try { const v = await (await fetch("version.json", { cache: "no-store" })).json(); app.version = v.versao || ""; } catch {}
  try { LIBRETRO = await (await fetch("data/libretro/index.json")).json(); } catch { LIBRETRO = []; }
  if (!app.clientId || /^COLE_/.test(app.clientId)) return screenNoClient();
  try { await loadGis(); initAuth(); } catch { return setupPanel(h("p", { class: "status err", text: "Não consegui carregar o login do Google. Confira a internet e recarregue." })); }
  screenLogin();
})();
