"use strict";

const $ = (s) => document.querySelector(s);
const els = {
  body: document.body,
  cam: $("#cam"),
  feedRoom: $("#feedRoom"),
  detections: $("#detections"),
  guidancePhrase: $("#guidancePhrase"),
  metaZone: $("#metaZone"),
  metaConf: $("#metaConf"),
  pathbar: $("#pathbar"),
  tickerList: $("#tickerList"),
  radar: $("#radar"),
  radarCtx: $("#radar").getContext("2d"),
  scanBtn: $("#scanBtn"),
  dockScanBtn: $("#dockScanBtn"),
  radarWrap: $("#radarWrap"),
  voiceBtn: $("#voiceBtn"),
  panelBtn: $("#panelBtn"),
  sheet: $("#sheet"),
  sheetClose: $("#sheetClose"),
  sheetVoice: $("#sheetVoice"),
  sheetVoiceVal: $("#sheetVoiceVal"),
  sheetVoiceHint: $("#sheetVoiceHint"),
  rateSlider: $("#rateSlider"),
  rateVal: $("#rateVal"),
  modeSeg: $("#modeSeg"),
  modeHint: $("#modeHint"),
  threadMinus: $("#threadMinus"),
  threadPlus: $("#threadPlus"),
  threadVal: $("#threadVal"),
  classList: $("#classList"),
  fpsVal: $("#fpsVal"),
  infVal: $("#infVal"),
  teleFrame: $("#teleFrame"),
  teleInf: $("#teleInf"),
  teleScene: $("#teleScene"),
  beginBtn: $("#beginBtn"),
  demoBtn: $("#demoBtn"),
  focus: $("#focus"),
  toast: $("#toast"),
  appsBtn: $("#appsBtn"),
  appsSheet: $("#appsSheet"),
  appsSheetClose: $("#appsSheetClose"),
  appsGrid: $("#appsGrid"),
  appSheet: $("#appSheet"),
  appSheetBack: $("#appSheetBack"),
  appSheetClose: $("#appSheetClose"),
  appSheetTitle: $("#appSheetTitle"),
  appModeSeg: $("#appModeSeg"),
  appBody: $("#appBody"),
  findChip: $("#findChip"),
  findChipName: $("#findChipName"),
  findStop: $("#findStop"),
  ocrBody: $("#ocrBody"),
  ocrClose: $("#ocrClose"),
  ocrRespeak: $("#ocrRespeak"),
  descSkel: $("#descSkel"),
  descText: $("#descText"),
  descCard: $("#descCard"),
};

const CLASSES = [
  "Person", "Chair", "Table", "Desk", "Bench", "Door", "Window",
  "Mechanical fan", "Laptop", "Mobile phone", "Book", "Whiteboard", "Backpack",
];

const SPOKEN = { "Mechanical fan": "fan", "Mobile phone": "phone" };

const PEOPLE = new Set(["Person"]);
const URGENCY_RANK = { ahead: 1, close: 2, critical: 3 };
const URGENCY_COLOR = { ahead: "#3ee6ff", close: "#ffb454", critical: "#ff5a4d" };
const ZONE_ORDER = ["left", "center", "right"];
const DIR_WORD = { left: "left", center: "ahead", right: "right" };
const MODE_HINT = {
  balanced: "Announces the one object that matters, then stays quiet",
  minimal: "Speaks only when something blocks your path",
  assist: "More frequent scene-reads for unfamiliar rooms",
};

const APPS = {
  find:     { title: "Find",         modes: ["Nearest", "Largest"] },
  text:     { title: "Read text",    modes: ["Board", "Full view"] },
  describe: { title: "Describe",     modes: ["Brief", "Detailed"] },
};

const FINDS = [
  { cls: "Person", w: "person" },
  { cls: "Chair", w: "chair" },
  { cls: "Door", w: "door" },
  { cls: "Desk", w: "desk" },
  { cls: "Backpack", w: "backpack" },
  { cls: "Whiteboard", w: "whiteboard" },
  { cls: "Mechanical fan", w: "fan" },
];

const BOARD = {
  "Board":     ["CLASS 4 · ALGEBRA", "solve for x — p.42"],
  "Full view": ["CLASS 4 · ALGEBRA", "solve for x — p.42", "hw due friday", "exam wed 10 am"],
};
const BOARD_SPOKEN = {
  "CLASS 4 · ALGEBRA": "class four, algebra",
  "solve for x — p.42": "solve for x, page forty-two",
  "hw due friday": "homework due friday",
  "exam wed 10 am": "exam wednesday, ten a.m.",
};

const state = {
  phase: "focus",
  camera: "pending",
  voice: true,
  rate: 0.9,
  mode: "balanced",
  threads: 4,
  targets: [],
  boxEls: new Map(),
  lastAnnounced: null,
  lastSpokenAt: 0,
  lastInf: 0,
  initAt: 0,
  demo: false,
  app: null,
  appMode: {},
  find: null,
  ocrT: 0,
  descT: 0,
};

const COOLDOWN = 3200;
const COOLDOWN_URGENT = 1400;
const SCENE_CADENCE = { balanced: 30000, assist: 14000, minimal: 0 };

const SCENE = [
  { label: "Whiteboard",  x: .5,  y: .15, w: .34, h: .22, conf: .96, sub: "boards" },
  { label: "Person",      x: .46, y: .6,  w: .16, h: .32, conf: .92, sub: "people", mover: true },
  { label: "Desk",        x: .27, y: .5,  w: .22, h: .2,  conf: .87, sub: "furniture" },
  { label: "Chair",       x: .16, y: .66, w: .13, h: .24, conf: .83, sub: "furniture" },
  { label: "Door",        x: .85, y: .56, w: .09, h: .34, conf: .9,  sub: "architecture" },
  { label: "Backpack",    x: .7,  y: .78, w: .09, h: .15, conf: .8,  sub: "objects" },
  { label: "Mechanical fan", x: .15, y: .14, w: .16, h: .16, conf: .9, sub: "devices" },
];

function zoneOf(x, w) {
  const c = x + w / 2;
  if (c < 0.32) return "left";
  if (c > 0.68) return "right";
  return "center";
}

function zoneWord(z) {
  return { left: "on your left", center: "ahead", right: "on your right" }[z];
}

function buildPhrase(t) {
  const obj = SPOKEN[t.label] || t.label.toLowerCase();
  const zw = zoneWord(zoneOf(t.x, t.w));
  if (t.urgency === "critical") return `stop — ${obj} ${zw}`;
  if (t.urgency === "close") return `careful, ${obj} ${zw}`;
  return `${obj} ${zw}`;
}

function urgencyRank(u) { return URGENCY_RANK[u] || 0; }

function pickDominant() {
  let best = null;
  const score = (t) =>
    urgencyRank(t.urgency) * 100 +
    (PEOPLE.has(t.label) ? 12 : 0) +
    t.y * 24 +
    t.w * t.h;
  for (const t of state.targets) {
    if (!t.show) continue;
    if (!best || score(t) > score(best)) best = t;
  }
  return best;
}

function zoneWorst() {
  const worst = { left: "none", center: "none", right: "none" };
  for (const t of state.targets) {
    if (!t.show) continue;
    const z = zoneOf(t.x, t.w);
    if (urgencyRank(worst[z]) < urgencyRank(t.urgency)) worst[z] = t.urgency;
  }
  return worst;
}

function clearZones() {
  const w = zoneWorst();
  return ZONE_ORDER.filter((z) => w[z] === "none" || w[z] === "ahead");
}

function speak(text) {
  if (!state.voice || !("speechSynthesis" in window)) return;
  const u = new SpeechSynthesisUtterance(text);
  u.rate = state.rate;
  speechSynthesis.cancel();
  speechSynthesis.speak(u);
}

function addTicker(text, urgency) {
  const li = document.createElement("li");
  const u = URGENCY_COLOR[urgency] || URGENCY_COLOR.ahead;
  const t = new Date();
  const hh = String(t.getHours()).padStart(2, "0");
  const mm = String(t.getMinutes()).padStart(2, "0");
  const ss = String(t.getSeconds()).padStart(2, "0");
  li.style.setProperty("--u", u);
  li.innerHTML = `<span class="tk-time">${hh}:${mm}:${ss}</span><span class="tk-dot"></span><span>${text}</span>`;
  const list = els.tickerList;
  list.prepend(li);
  while (list.children.length > 4) list.lastChild.remove();
}

const distToFeet = (t) => Math.hypot(t.x + t.w / 2 - 0.46, t.y + t.h / 2 - 0.9);
const metersOf = (t) => Math.max(1, Math.round((1 - t.h) * 6 + 0.5));

function pickFindTarget() {
  if (!state.find || !state.find.active) return null;
  const cands = state.targets.filter((t) => t.show && t.conf >= 0.55 && t.label === state.find.cls);
  if (!cands.length) return null;
  if (state.appMode.find === "Largest") return cands.reduce((a, b) => (b.w * b.h > a.w * a.h ? b : a));
  return cands.reduce((a, b) => (distToFeet(b) < distToFeet(a) ? b : a));
}

function plainPhrase(p, u) {
  els.body.dataset.urgency = u;
  els.guidancePhrase.textContent = p;
  els.metaZone.textContent = "center";
  els.metaConf.textContent = "searching";
  els.pathbar.querySelectorAll(".pb-seg").forEach((seg) => {
    seg.classList.remove("is-active");
    seg.classList.add("is-clear");
  });
}

function announceFind(t, force) {
  state.find.found = t;
  const z = zoneOf(t.x, t.w);
  const m = metersOf(t);
  const phrase = `found ${state.find.w} — ${zoneWord(z)}, about ${m} meters`;
  els.body.dataset.urgency = t.urgency;
  els.guidancePhrase.textContent = phrase;
  els.metaZone.textContent = z === "center" ? "ahead" : z;
  els.metaConf.textContent = Math.round(t.conf * 100) + "%";
  els.pathbar.querySelectorAll(".pb-seg").forEach((seg) => {
    seg.classList.remove("is-clear");
    seg.classList.toggle("is-active", seg.dataset.zone === z);
  });
  const key = t.id + ":" + m;
  if (!force && key === state.find.lastKey) return;
  state.find.lastKey = key;
  speak(phrase);
  addTicker(phrase, t.urgency);
  state.lastSpokenAt = performance.now();
}

function startFind(f) {
  if (state.phase !== "live") return;
  closeAside(els.appSheet);
  state.find = { cls: f.cls, w: f.w, active: true, found: null, lastKey: "" };
  els.body.dataset.find = f.w;
  els.findChipName.textContent = f.w;
  state.lastSpokenAt = -Infinity;
  state.lastAnnounced = null;
  const t = pickFindTarget();
  if (t) announceFind(t, true);
  else plainPhrase("searching for " + f.w + "…", "ahead");
  showToast("find · " + f.w);
}

function stopFind() {
  if (!state.find) return;
  state.find.active = false;
  state.find.found = null;
  state.find = null;
  delete els.body.dataset.find;
  state.lastSpokenAt = -Infinity;
  state.lastAnnounced = null;
  showToast("find mode off");
}

function evaluateGuidance(force) {
  const dom = pickDominant();
  const pops = zoneWorst();
  const clear = clearZones();

  els.pathbar.querySelectorAll(".pb-seg").forEach((seg) => {
    seg.classList.toggle("is-clear", clear.includes(seg.dataset.zone));
  });

  if (state.find && state.find.active) {
    const t = pickFindTarget();
    if (t) announceFind(t, false);
    else {
      state.find.found = null;
      plainPhrase("can't see the " + state.find.w + " right now", "ahead");
    }
    return;
  }

  if (!dom) {
    els.body.dataset.urgency = "ahead";
    els.guidancePhrase.textContent = "Room clear";
    els.metaZone.textContent = "center";
    els.metaConf.textContent = "nothing near";
    return;
  }

  const z = zoneOf(dom.x, dom.w);
  const phrase = buildPhrase(dom);
  const urgent = dom.urgency === "critical";

  els.body.dataset.urgency = dom.urgency;
  els.guidancePhrase.textContent = phrase;
  els.metaZone.textContent = z === "center" ? "ahead" : z;
  els.metaConf.textContent = Math.round(dom.conf * 100) + "%";
  els.pathbar.querySelectorAll(".pb-seg").forEach((seg) => {
    seg.classList.toggle("is-active", seg.dataset.zone === z);
  });

  state.targets.forEach((t) => {
    const bx = state.boxEls.get(t.id);
    if (bx) bx.el.toggleAttribute("data-focus", t === dom);
  });

  if (state.phase !== "live") return;

  const now = performance.now();
  const changed =
    !state.lastAnnounced ||
    state.lastAnnounced.id !== dom.id ||
    state.lastAnnounced.urgency !== dom.urgency ||
    state.lastAnnounced.zone !== z;
  const escalated = state.lastAnnounced && urgencyRank(dom.urgency) > urgencyRank(state.lastAnnounced.urgency);

  if (!changed && !escalated) return;
  if (state.mode === "minimal" && dom.urgency !== "critical") return;
  const cd = urgent ? COOLDOWN_URGENT : COOLDOWN;
  if (now - state.lastSpokenAt < cd) {
    state.lastAnnounced = { id: dom.id, urgency: dom.urgency, zone: z };
    return;
  }

  speak(phrase);
  addTicker(phrase, dom.urgency);
  state.lastSpokenAt = now;
  state.lastAnnounced = { id: dom.id, urgency: dom.urgency, zone: z };
  els.guidancePhrase.setAttribute("data-lived", String(now));
}

function countByCategory() {
  const counts = {};
  for (const t of state.targets) {
    if (!t.show) continue;
    const c = PEOPLE.has(t.label) ? "people"
      : t.sub === "furniture" ? "furniture"
      : t.sub === "boards" ? "boards"
      : t.sub === "devices" ? "devices"
      : "objects";
    counts[c] = (counts[c] || 0) + 1;
  }
  return counts;
}

function scanScene() {
  if (state.find && state.find.active) return;
  const counts = countByCategory();
  const plural = (n, w) => (n > 1 ? `${n} ${w}` : `one ${w.replace(/s$/, "")}`);
  const parts = [];
  if (counts.people) parts.push(plural(counts.people, "people"));
  if (counts.furniture) parts.push(plural(counts.furniture, "chairs and desks"));
  if (counts.boards) parts.push(plural(counts.boards, "boards"));
  if (counts.devices) parts.push(plural(counts.devices, "devices"));
  if (counts.objects) parts.push(plural(counts.objects, "objects"));

  const clear = clearZones();
  const prefer = clear.find((z) => zoneWorst()[z] === "none") || clear[0] || "center";
  const dirWord = DIR_WORD[prefer];
  const phrase = parts.length
    ? `scene — ${parts.join(", ")}, clear path ${dirWord}`
    : `room clear, path ${dirWord}`;

  els.guidancePhrase.textContent = phrase;
  els.metaZone.textContent = dirWord;
  speak(phrase);
  addTicker(phrase, "ahead");
}

function stepInference() {
  const now = performance.now();
  for (const t of state.targets) {
    if (t.mover) {
      const speed = 0.00016 * (now - state.initAt);
      t.x = Math.max(0.05, Math.min(0.8, t.x + Math.sin(speed * 0.6) * 0.00042));
      t.y = Math.max(0.34, Math.min(0.92, t.y + Math.sin(speed * 0.4 + 2) * 0.0005));
      const d = Math.hypot(t.x - 0.46, t.y - 0.9);
      t.urgency = d < 0.2 ? "critical" : d < 0.34 ? "close" : "ahead";
      t.conf = Math.max(0.6, Math.min(0.98, t.conf + (Math.random() - 0.5) * 0.02));
    } else if (t.sceneBreathe) {
      t.conf = Math.max(0.55, Math.min(0.99, t.conf + (Math.random() - 0.5) * 0.012));
    }
  }

  for (const t of state.targets) {
    if (t.dropUntil && performance.now() > t.dropUntil) {
      t.dropUntil = 0;
      t.show = true;
    }
  }
  const reduceCand = state.targets.filter((t) => !t.sceneLock && t.show);
  if (reduceCand.length > 4 && Math.random() < 0.02) {
    const drop = reduceCand[Math.floor(Math.random() * reduceCand.length)];
    drop.show = false;
    drop.dropUntil = performance.now() + 4000 + Math.random() * 6000;
  }

  state.targets.forEach((t) => {
    const show = t.show && t.conf >= 0.55;
    const c = t.x + t.w / 2;
    const d = Math.hypot(t.x - 0.46, t.y - 0.9);
    if (!t.mover) t.urgency = d < 0.2 ? "critical" : d < 0.34 ? "close" : "ahead";
    const bx = ensureBox(t);
    bx.el.style.left = t.x * 100 + "%";
    bx.el.style.top = t.y * 100 + "%";
    bx.el.style.width = t.w * 100 + "%";
    bx.el.style.height = t.h * 100 + "%";
    bx.el.style.setProperty("--u", URGENCY_COLOR[t.urgency]);
    const prevShow = bx.el.dataset.show;
    bx.el.dataset.show = show ? "1" : "0";
    bx.el.toggleAttribute("data-just", show && prevShow === "1");
    bx.el.toggleAttribute("data-find", !!(state.find && state.find.active && state.find.found === t));
    if (bx.el.children.length === 0) buildBoxDom(bx, t, c);
    const nameEl = bx.el.querySelector(".bx-name");
    nameEl.textContent = SPOKEN[t.label] || t.label.toLowerCase();
    bx.conf.innerHTML = ` <b>${Math.round(t.conf * 100)}</b>`;
    bx.zone.textContent = zoneOf(t.x, t.w);
  });

  state.lastInf = 18 + Math.random() * 34;
  evaluateGuidance();
}

function buildBoxDom(bx, t, c) {
  const el = bx.el;
  el.innerHTML =
    `<div class="bx-box"></div>` +
    `<div class="bx-tag"><span class="bx-dot"></span><span class="bx-name">${SPOKEN[t.label] || t.label.toLowerCase()}</span><span class="bx-conf"> <b>70</b></span><span class="bx-zone"></span></div>`;
  const parts = el.querySelector(".bx-tag");
  parts.style.left = c > 0.7 ? "auto" : "2px";
  parts.style.right = c > 0.7 ? "2px" : "auto";
  bx.conf = el.querySelector(".bx-conf");
  bx.zone = el.querySelector(".bx-zone");
}

function ensureBox(t) {
  if (state.boxEls.has(t.id)) return state.boxEls.get(t.id);
  const el = document.createElement("div");
  el.className = "bx";
  el.dataset.id = t.id;
  els.detections.appendChild(el);
  const entry = { el, conf: null, zone: null };
  state.boxEls.set(t.id, entry);
  return entry;
}

function renderRadar(time) {
  const s = els.radar;
  const dpr = window.devicePixelRatio || 1;
  const px = s.width / dpr;
  const ctx = els.radarCtx;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, px, px);

  const R = px / 2 - 3;
  const cx = px / 2, cy = px / 2;

  ctx.lineWidth = 1;
  ctx.strokeStyle = "rgba(255,255,255,.08)";
  ctx.beginPath();
  ctx.arc(cx, cy, R * 0.5, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(cx, cy, R, 0, Math.PI * 2);
  ctx.stroke();

  ctx.strokeStyle = "rgba(255,255,255,.05)";
  ctx.beginPath();
  ctx.moveTo(cx - R, cy); ctx.lineTo(cx + R, cy);
  ctx.moveTo(cx, cy - R); ctx.lineTo(cx, cy + R);
  ctx.stroke();

  const sweep = (time / 1000) * 0.9;
  for (let i = 0; i < 3; i++) {
    const a = sweep + (i * Math.PI * 2) / 3;
    ctx.strokeStyle = `rgba(62,230,255,${i === 0 ? 0.16 : 0.05})`;
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.sin(a) * R, cy - Math.cos(a) * R);
    ctx.stroke();
  }

  ZONE_ORDER.forEach((z, i) => {
    const ang = (i - 1) * (65 * Math.PI) / 180;
    const lx = cx + Math.sin(ang) * R * 0.82;
    const ly = cy - Math.cos(ang) * R * 0.82;
    ctx.fillStyle = "rgba(174,188,201,.3)";
    ctx.font = "500 8px 'IBM Plex Mono', monospace";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(z[0].toUpperCase(), lx, ly);
  });

  for (const t of state.targets) {
    if (!t.show) continue;
    const c = t.x + t.w / 2;
    const ang = (c - 0.5) * (130 * Math.PI) / 180;
    const depth = Math.min(0.9, Math.max(0.38, (t.y - 0.1) * 0.85 + 0.22));
    const lx = cx + Math.sin(ang) * R * depth;
    const ly = cy - Math.cos(ang) * R * depth;
    const color = URGENCY_COLOR[t.urgency];
    const pulse = 1 + Math.sin(time / 420 + t.id) * 0.15;
    ctx.beginPath();
    ctx.arc(lx, ly, 3 * pulse, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.shadowColor = color;
    ctx.shadowBlur = 8;
    ctx.fill();
    ctx.shadowBlur = 0;
  }
}

function renderClassList() {
  els.classList.innerHTML = "";
  for (const c of CLASSES) {
    const li = document.createElement("li");
    li.textContent = c;
    els.classList.appendChild(li);
  }
}

function setCamera(resolved) {
  state.camera = resolved;
  els.body.dataset.camera = resolved;
}

function startLive() {
  state.phase = "live";
  state.initAt = performance.now();
  els.body.dataset.phase = "live";
  els.guidancePhrase.textContent = "Scanning the room…";
  els.metaConf.textContent = "locating";

  renderClassList();
  state.targets = SCENE.map((s, i) => ({
    id: i + 1,
    label: s.label,
    x: s.x, y: s.y, w: s.w, h: s.h,
    conf: s.conf,
    sub: s.sub,
    mover: !!s.mover,
    sceneLock: !!s.sceneLock,
    sceneBreathe: !s.mover,
    show: true,
    urgency: "ahead",
  }));

  state.lastSpokenAt = -Infinity;
  state.lastAnnounced = null;
  els.tickerList.innerHTML = "";
  evaluateGuidance(true);

  const cadence = SCENE_CADENCE[state.mode];
  els.teleScene.textContent = cadence ? `auto · ${cadence / 1000} s` : "manual only";
  if (cadence) setInterval(scanScene, cadence);

  let fps = 0, fpsT = performance.now();
  const loop = (time) => {
    requestAnimationFrame(loop);
    if (time - fpsT > 1000) {
      els.fpsVal.textContent = String(Math.min(99, Math.round(fps * 10) / 10)).padStart(2, "0");
      els.infVal.textContent = String(Math.round(state.lastInf)).padStart(2, "0");
      els.teleFrame.textContent = String(Math.round(fps)) + "/s";
      els.teleInf.textContent = Math.round(state.lastInf) + " ms";
      fps = 0;
      fpsT = time;
    }
    fps++;
    renderRadar(time);
  };
  requestAnimationFrame(loop);

  setInterval(stepInference, 120);
}

async function begin(useCam) {
  if (state.phase !== "focus") return;
  setCamera("none");
  startLive();
  tryCam(useCam);
}

async function tryCam(useCam) {
  if (!useCam) { showToast("simulated scene · no camera needed"); return; }
  els.body.dataset.camera = "pending";
  let cam = false;
  try {
    const stream = await Promise.race([
      navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment", width: { ideal: 1280 } }, audio: false }),
      new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), 2500)),
    ]);
    els.cam.srcObject = stream;
    cam = true;
  } catch {
    cam = false;
  }
  setCamera(cam ? "live" : "none");
  if (!cam) showToast("camera unavailable · running simulated classroom");
}

let toastTimer = null;
function showToast(msg) {
  els.toast.textContent = msg;
  els.toast.classList.add("is-show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove("is-show"), 3400);
}

function setVoice(on) {
  state.voice = on;
  els.body.dataset.voice = on ? "on" : "off";
  els.voiceBtn.setAttribute("aria-pressed", String(on));
  els.sheetVoice.checked = on;
  els.sheetVoiceVal.textContent = on ? "ON" : "OFF";
  els.sheetVoiceHint.textContent = on ? "Speaks the best path aloud" : "Guidance on-screen only";
}

function setRate(v) {
  state.rate = v;
  els.rateVal.textContent = v.toFixed(2) + "×";
}

function setMode(m) {
  state.mode = m;
  els.body.dataset.mode = m;
  els.modeSeg.querySelectorAll(".seg-btn").forEach((b) => {
    const on = b.dataset.mode === m;
    b.classList.toggle("is-active", on);
    b.setAttribute("aria-checked", String(on));
  });
  els.modeHint.textContent = MODE_HINT[m];
  const cadence = SCENE_CADENCE[m];
  els.teleScene.textContent = cadence ? `auto · ${cadence / 1000} s` : "manual only";
}

els.beginBtn.addEventListener("click", () => begin(true));
els.demoBtn.addEventListener("click", () => begin(false));

/* Scale the phone on the presentation stage to fit the window.
   zoom is layout-aware, so the caption stays below the device. */
function fitDevice() {
  const dev = document.querySelector(".device");
  if (!dev) return;
  if (innerWidth < 560) { dev.style.zoom = "1"; return; }
  const s = Math.max(0.2, Math.min(1, (innerWidth - 88) / 390, (innerHeight - 196) / 844));
  dev.style.zoom = s.toFixed(3);
}
window.addEventListener("resize", fitDevice);
window.addEventListener("orientationchange", fitDevice);
fitDevice();

els.scanBtn.addEventListener("click", () => { if (state.phase === "live") scanScene(); });
els.dockScanBtn.addEventListener("click", () => { if (state.phase === "live") scanScene(); });
els.radarWrap.addEventListener("click", () => { if (state.phase === "live") scanScene(); });

els.voiceBtn.addEventListener("click", () => setVoice(!state.voice));

els.panelBtn.addEventListener("click", () => {
  els.sheet.classList.add("is-open");
  els.sheet.setAttribute("aria-hidden", "false");
});
els.sheetClose.addEventListener("click", closeSheet);
els.sheet.addEventListener("click", (e) => { if (e.target === els.sheet) closeSheet(); });
function closeSheet() {
  els.sheet.classList.remove("is-open");
  els.sheet.setAttribute("aria-hidden", "true");
}

/* ---------- apps tray + app detail ---------- */
function openAside(node) {
  node.classList.add("is-open");
  node.setAttribute("aria-hidden", "false");
}
function closeAside(node) {
  node.classList.remove("is-open");
  node.setAttribute("aria-hidden", "true");
}

els.appsBtn.addEventListener("click", () => {
  const open = els.appsSheet.classList.contains("is-open");
  if (open) closeAside(els.appsSheet);
  else { closeAside(els.appSheet); closeSheet(); openAside(els.appsSheet); }
});
els.appsSheetClose.addEventListener("click", () => closeAside(els.appsSheet));
els.appsSheet.addEventListener("click", (e) => { if (e.target === els.appsSheet) closeAside(els.appsSheet); });

els.appsGrid.addEventListener("click", (e) => {
  const tile = e.target.closest(".app-tile");
  if (!tile) return;
  closeAside(els.appsSheet);
  openApp(tile.dataset.app);
});
els.appSheetBack.addEventListener("click", () => { closeAside(els.appSheet); openAside(els.appsSheet); });
els.appSheetClose.addEventListener("click", () => closeAside(els.appSheet));
els.appSheet.addEventListener("click", (e) => { if (e.target === els.appSheet) closeAside(els.appSheet); });

function openApp(key) {
  state.app = key;
  els.appSheetTitle.textContent = APPS[key].title;
  if (!state.appMode[key]) state.appMode[key] = APPS[key].modes[0];
  renderAppModeSeg();
  renderAppBody();
  openAside(els.appSheet);
}

els.appModeSeg.addEventListener("click", (e) => {
  const b = e.target.closest(".seg-btn");
  if (!b || !state.app) return;
  state.appMode[state.app] = b.dataset.mode;
  renderAppModeSeg();
  renderAppBody();
});

function renderAppModeSeg() {
  const seg = els.appModeSeg;
  const modes = APPS[state.app].modes;
  const cur = state.appMode[state.app];
  seg.innerHTML = "";
  modes.forEach((m) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "seg-btn" + (m === cur ? " is-active" : "");
    b.dataset.mode = m;
    b.setAttribute("role", "radio");
    b.setAttribute("aria-checked", String(m === cur));
    b.textContent = m;
    seg.appendChild(b);
  });
}

function renderAppBody() {
  const body = els.appBody;
  body.innerHTML = "";
  const hint = document.createElement("p");
  hint.className = "app-hint";
  if (state.app === "find") {
    const grid = document.createElement("div");
    grid.className = "picker-grid";
    FINDS.forEach((f) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "picker-chip";
      b.textContent = f.w;
      b.addEventListener("click", () => startFind(f));
      grid.appendChild(b);
    });
    body.appendChild(grid);
    hint.textContent = "The chosen object is highlighted and spoken with direction + distance. Tap catch-pill to stop.";
    body.appendChild(hint);
  } else {
    const run = document.createElement("button");
    run.type = "button";
    run.className = "glass-btn glass-btn--primary btn-run";
    run.textContent = state.app === "text" ? "Scan board" : "Describe room";
    run.addEventListener("click", state.app === "text" ? startOcr : startDescribe);
    body.appendChild(run);
    hint.textContent = state.app === "text"
      ? "Reads visible text aloud — sweeps the classroom board."
      : "One-sentence on-device caption of what the camera sees.";
    body.appendChild(hint);
  }
}

/* ---------- OCR (read text) ---------- */
function startOcr() {
  if (state.phase !== "live") return;
  closeAside(els.appSheet);
  const mode = state.appMode.text || "Board";
  els.body.dataset.ocr = "1";
  els.ocrBody.classList.add("is-scan");
  els.ocrBody.innerHTML = "";
  clearTimeout(state.ocrT);
  state.ocrT = setTimeout(() => {
    els.ocrBody.classList.remove("is-scan");
    const lines = BOARD[mode] || BOARD["Board"];
    lines.forEach((line, i) => {
      const d = document.createElement("div");
      d.className = "ocr-line" + (i > 0 ? " is-note" : "");
      d.textContent = line;
      els.ocrBody.appendChild(d);
    });
    const first = lines[0];
    speak("text detected — " + (BOARD_SPOKEN[first] || first.toLowerCase().replace(/[·]/g, " ")));
    addTicker("text — " + first, "ahead");
    state.ocrT = setTimeout(dismissOcr, 14000);
  }, 1900);
}
function dismissOcr() {
  clearTimeout(state.ocrT);
  delete els.body.dataset.ocr;
  els.ocrBody.classList.remove("is-scan");
}
els.ocrClose.addEventListener("click", dismissOcr);
els.ocrRespeak.addEventListener("click", () => {
  const mode = state.appMode.text || "Board";
  const first = (BOARD[mode] || BOARD["Board"])[0];
  speak("text detected — " + (BOARD_SPOKEN[first] || first.toLowerCase().replace(/[·]/g, " ")));
});

/* ---------- describe scene ---------- */
function startDescribe() {
  if (state.phase !== "live") return;
  closeAside(els.appSheet);
  const mode = state.appMode.describe || "Brief";
  els.body.dataset.desc = "1";
  els.descSkel.style.display = "block";
  els.descText.textContent = "";
  clearTimeout(state.descT);
  state.descT = setTimeout(() => {
    els.descSkel.style.display = "none";
    const c = buildCaption(mode);
    els.descText.textContent = c;
    speak(c);
    addTicker("describe — " + (c.length > 56 ? c.slice(0, 56) + "…" : c), "ahead");
    state.descT = setTimeout(dismissDesc, 15000);
  }, 1700);
}
function dismissDesc() {
  clearTimeout(state.descT);
  delete els.body.dataset.desc;
}
els.descCard.addEventListener("click", dismissDesc);

function buildCaption(mode) {
  const counts = countByCategory();
  const parts = [];
  if (counts.people) parts.push(counts.people === 1 ? "one person" : counts.people + " people");
  if (counts.boards) parts.push(counts.boards === 1 ? "one whiteboard" : counts.boards + " whiteboards");
  if (counts.furniture) parts.push(counts.furniture === 1 ? "one desk or chair" : counts.furniture + " desks and chairs");
  if (counts.devices) parts.push("a fan");
  const clear = clearZones();
  const prefer = clear.find((z) => zoneWorst()[z] === "none") || clear[0] || "center";
  const head = parts.length ? parts.join(", ") : "an empty room";
  return mode === "Brief"
    ? `A classroom with ${head}.`
    : `A classroom with ${head}. The path ${zoneWord(prefer)} is clear.`;
}

els.findStop.addEventListener("click", stopFind);

els.sheetVoice.addEventListener("change", (e) => setVoice(e.target.checked));

els.rateSlider.addEventListener("input", (e) => setRate(parseFloat(e.target.value)));

els.modeSeg.addEventListener("click", (e) => {
  const btn = e.target.closest(".seg-btn");
  if (btn) setMode(btn.dataset.mode);
});

function setThreads(n) {
  state.threads = Math.max(2, Math.min(4, n));
  els.threadVal.textContent = String(state.threads);
}
els.threadMinus.addEventListener("click", () => setThreads(state.threads - 1));
els.threadPlus.addEventListener("click", () => setThreads(state.threads + 1));

state.demo = new URLSearchParams(location.search).get("demo") === "1";
setVoice(true);
setRate(0.9);
setMode("balanced");
setThreads(4);

window.addEventListener("error", (ev) => {
  const n = document.createElement("div");
  n.id = "jserr";
  n.style.display = "none";
  n.textContent = (ev.message || "") + " @ " + (ev.filename || "").split("/").pop() + ":" + ev.lineno;
  document.body.appendChild(n);
});

if (state.demo) begin(false);