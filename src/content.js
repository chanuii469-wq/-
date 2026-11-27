// 요소 숨김(코스메틱 필터) + 요소 선택기
(() => {
  if (window.__adlullLoaded) return;
  window.__adlullLoaded = true;

  // 일반적인 광고 영역 선택자
  const GENERIC_SELECTORS = [
    "ins.adsbygoogle",
    "iframe[src*='doubleclick.net']",
    "iframe[src*='googlesyndication.com']",
    "iframe[id^='google_ads_iframe']",
    "div[id^='div-gpt-ad']",
    "div[id^='google_ads_']",
    "[data-ad-slot]",
    "[data-ad-client]",
    "[data-google-query-id]",
    "a[href*='doubleclick.net/']",
    "a[href*='googleadservices.com/']",
    ".adsbygoogle",
    ".ad-banner",
    ".ad-container",
    ".ad-wrapper",
    ".ad-slot",
    ".ad-unit",
    ".ads-area",
    ".advertisement",
    ".banner-ad",
    ".sponsored-content",
    ".taboola",
    "[id^='taboola-']",
    ".OUTBRAIN",
    "[data-widget-id][class*='outbrain']",
    "div[id^='dable_placeholder']",
    "div[id^='mobon_']",
  ];

  let styleEl = null;
  let selectors = [];
  let countTimer = null;

  function buildCss(list) {
    // 선택자 하나가 잘못되면 규칙 전체가 무시되므로 하나씩 분리
    return list
      .filter(isValid)
      .map((s) => `${s}{display:none!important;visibility:hidden!important}`)
      .join("\n");
  }

  function isValid(sel) {
    try {
      document.createDocumentFragment().querySelector(sel);
      return true;
    } catch {
      return false;
    }
  }

  function injectStyle(css) {
    if (!styleEl) {
      styleEl = document.createElement("style");
      styleEl.id = "adlull-cosmetic";
    }
    styleEl.textContent = css;
    const parent = document.head || document.documentElement;
    if (parent && !styleEl.isConnected) parent.appendChild(styleEl);
  }

  let lastCount = -1;

  function countHidden() {
    countTimer = null;
    // 선택자를 하나로 합쳐 DOM을 한 번만 탐색 (선택자는 init에서 이미 검증됨)
    let n = 0;
    try {
      n = selectors.length ? document.querySelectorAll(selectors.join(",")).length : 0;
    } catch {}
    if (n === lastCount) return; // 바뀌지 않았으면 메시지 생략
    lastCount = n;
    chrome.runtime.sendMessage({ type: "cosmeticCount", count: n }).catch(() => {});
  }

  // 디바운스 대신 스로틀: DOM이 계속 바뀌는 페이지에서도 최대 1초에 한 번만 계산
  function scheduleCount() {
    if (countTimer) return;
    countTimer = setTimeout(countHidden, 1000);
  }

  async function init() {
    let cfg;
    try {
      cfg = await chrome.runtime.sendMessage({ type: "getContentConfig" });
    } catch {
      return;
    }
    if (!cfg || !cfg.active) return;

    selectors = [...GENERIC_SELECTORS, ...(cfg.userSelectors || [])].filter(isValid);
    injectStyle(buildCss(selectors));

    // 페이지가 <head>를 갈아끼워도 스타일이 유지되도록 감시
    const observer = new MutationObserver((mutations) => {
      if (styleEl && !styleEl.isConnected) injectStyle(styleEl.textContent);
      // 노드가 추가된 경우에만 다시 계산
      if (mutations.some((m) => m.addedNodes.length)) scheduleCount();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });

    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", scheduleCount, { once: true });
    } else {
      scheduleCount();
    }
  }

  // ---------------- 요소 선택기 ----------------
  let picker = null;

  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, "\\$&");
  }

  function selectorFor(el) {
    if (el.id && !/\d{3,}/.test(el.id)) return `#${cssEscape(el.id)}`;
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === 1 && cur !== document.body && parts.length < 4) {
      let part = cur.tagName.toLowerCase();
      const classes = [...cur.classList].filter((c) => !/\d{3,}/.test(c)).slice(0, 2);
      if (classes.length) part += classes.map((c) => "." + cssEscape(c)).join("");
      const parent = cur.parentElement;
      if (parent && !classes.length) {
        const same = [...parent.children].filter((c) => c.tagName === cur.tagName);
        if (same.length > 1) part += `:nth-of-type(${same.indexOf(cur) + 1})`;
      }
      parts.unshift(part);
      if (cur.id && !/\d{3,}/.test(cur.id)) {
        parts[0] = `#${cssEscape(cur.id)}`;
        break;
      }
      cur = parent;
    }
    return parts.join(" > ");
  }

  function startPicker() {
    if (picker) return;
    const box = document.createElement("div");
    box.style.cssText =
      "position:fixed;z-index:2147483647;pointer-events:none;background:rgba(229,57,53,.25);" +
      "outline:2px solid #e53935;transition:all .05s;display:none";
    const tip = document.createElement("div");
    tip.style.cssText =
      "position:fixed;z-index:2147483647;left:50%;top:12px;transform:translateX(-50%);" +
      "background:#222;color:#fff;font:13px/1.4 system-ui,sans-serif;padding:8px 14px;border-radius:8px;" +
      "box-shadow:0 4px 16px rgba(0,0,0,.3);pointer-events:none;max-width:90vw";
    tip.textContent = "숨길 요소를 클릭하세요 · Esc로 취소";
    document.documentElement.append(box, tip);
    let target = null;

    const onMove = (e) => {
      const el = document.elementFromPoint(e.clientX, e.clientY);
      if (!el || el === box || el === tip) return;
      target = el;
      const r = el.getBoundingClientRect();
      Object.assign(box.style, {
        display: "block", left: r.left + "px", top: r.top + "px",
        width: r.width + "px", height: r.height + "px",
      });
      tip.textContent = `${selectorFor(el)}  ·  클릭하여 숨기기 / Esc 취소`;
    };
    const onClick = async (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (!target) return;
      const sel = selectorFor(target);
      stopPicker();
      const line = `${location.hostname}##${sel}`;
      await chrome.runtime.sendMessage({ type: "addUserFilter", line });
      selectors.push(sel);
      injectStyle(buildCss(selectors));
      scheduleCount();
    };
    const onKey = (e) => {
      if (e.key === "Escape") stopPicker();
    };

    document.addEventListener("mousemove", onMove, true);
    document.addEventListener("click", onClick, true);
    document.addEventListener("keydown", onKey, true);
    picker = () => {
      document.removeEventListener("mousemove", onMove, true);
      document.removeEventListener("click", onClick, true);
      document.removeEventListener("keydown", onKey, true);
      box.remove();
      tip.remove();
    };
  }

  function stopPicker() {
    if (picker) picker();
    picker = null;
  }

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.type === "startPicker" && window === window.top) startPicker();
  });

  init();
})();
