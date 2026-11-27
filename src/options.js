import { parseUserFilters } from "./filters.js";

const $ = (id) => document.getElementById(id);

async function load() {
  const { whitelist = [], userFilters = "", totalBlocked = 0 } =
    await chrome.storage.local.get(["whitelist", "userFilters", "totalBlocked"]);
  renderWhitelist(whitelist);
  if (document.activeElement !== $("filters")) $("filters").value = userFilters;
  $("total").textContent = totalBlocked.toLocaleString();
  showErrors(userFilters);
}

function renderWhitelist(list) {
  const ul = $("whitelist");
  ul.innerHTML = "";
  if (!list.length) {
    const li = document.createElement("li");
    li.textContent = "없음";
    li.style.color = "var(--muted)";
    ul.append(li);
    return;
  }
  for (const host of list) {
    const li = document.createElement("li");
    const span = document.createElement("span");
    span.textContent = host;
    const btn = document.createElement("button");
    btn.className = "remove";
    btn.textContent = "삭제";
    btn.onclick = async () => {
      const { whitelist = [] } = await chrome.storage.local.get("whitelist");
      await chrome.storage.local.set({ whitelist: whitelist.filter((h) => h !== host) });
    };
    li.append(span, btn);
    ul.append(li);
  }
}

function showErrors(text) {
  const { errors } = parseUserFilters(text);
  $("errors").innerHTML = "";
  for (const e of errors) {
    const li = document.createElement("li");
    li.textContent = `${e.line}번째 줄: ${e.reason} — ${e.text}`;
    $("errors").append(li);
  }
}

function normalizeHost(input) {
  let v = input.trim().toLowerCase();
  if (!v) return "";
  try {
    if (v.includes("://")) v = new URL(v).hostname;
  } catch {}
  return v.replace(/^www\./, "").replace(/\/.*$/, "");
}

$("addSite").addEventListener("submit", async (e) => {
  e.preventDefault();
  const host = normalizeHost($("siteInput").value);
  if (!/^[a-z0-9.-]+\.[a-z]{2,}$/.test(host)) return;
  const { whitelist = [] } = await chrome.storage.local.get("whitelist");
  if (!whitelist.includes(host)) whitelist.push(host);
  await chrome.storage.local.set({ whitelist });
  $("siteInput").value = "";
});

$("filters").addEventListener("input", (e) => showErrors(e.target.value));

$("save").addEventListener("click", async () => {
  await chrome.storage.local.set({ userFilters: $("filters").value });
  $("saved").textContent = "저장됨 ✓";
  setTimeout(() => ($("saved").textContent = ""), 1500);
});

$("resetStats").addEventListener("click", () => chrome.storage.local.set({ totalBlocked: 0 }));

chrome.storage.onChanged.addListener(load);
load();
