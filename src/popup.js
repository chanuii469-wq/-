const $ = (id) => document.getElementById(id);
const send = (msg) => chrome.runtime.sendMessage(msg);

let tab;

async function refresh() {
  const st = await send({ type: "getPopupState", tabId: tab.id, url: tab.url });
  const protectedHere = st.enabled && !st.siteAllowed;

  $("enabled").checked = st.enabled;
  $("host").textContent = st.host || "이 페이지는 지원되지 않습니다";
  $("siteToggle").checked = !st.siteAllowed;
  $("siteToggle").disabled = !st.enabled || !st.host || (st.siteAllowed && !st.siteWhitelisted);
  $("pick").disabled = !protectedHere || !st.host;

  document.body.classList.toggle("off", !protectedHere);
  $("statusText").textContent = !st.enabled
    ? "보호 꺼짐"
    : !st.host ? "보호 대상 아님"
    : st.siteAllowed ? "이 사이트에서 꺼짐" : "보호 중";

  $("network").textContent = st.network;
  $("cosmetic").textContent = st.cosmetic;
  $("total").textContent = st.hasDebugCounter ? st.totalBlocked.toLocaleString() : "–";
  $("total").title = st.hasDebugCounter ? "" : "누적 집계는 개발자 모드로 설치한 경우에만 지원됩니다";
}

async function reloadTab() {
  await new Promise((r) => setTimeout(r, 150)); // 규칙 반영 대기
  chrome.tabs.reload(tab.id);
}

$("enabled").addEventListener("change", async (e) => {
  await send({ type: "setEnabled", value: e.target.checked });
  await refresh();
  reloadTab();
});

$("siteToggle").addEventListener("change", async (e) => {
  const host = $("host").textContent;
  await send({ type: "setSiteAllowed", host, value: !e.target.checked });
  await refresh();
  reloadTab();
});

$("pick").addEventListener("click", async () => {
  try {
    await chrome.tabs.sendMessage(tab.id, { type: "startPicker" });
  } catch {}
  window.close();
});

$("options").addEventListener("click", () => chrome.runtime.openOptionsPage());

(async () => {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  await refresh();
  // 팝업이 열려 있고 보이는 동안만 갱신
  setInterval(() => document.visibilityState === "visible" && refresh(), 1500);
})();
