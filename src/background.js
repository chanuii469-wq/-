import { parseUserFilters, hostMatches } from "./filters.js";

const DEFAULTS = {
  enabled: true,
  whitelist: [],      // 차단을 끈 사이트 호스트 목록
  userFilters: "",    // 사용자 필터 원문
  totalBlocked: 0,
};

const WHITELIST_ID_BASE = 100000;
const USER_BLOCK_ID_BASE = 200000;
const USER_ALLOW_ID_BASE = 300000;
const STATIC_RULESETS = ["ads", "trackers"];

const BLOCK_TYPES = [
  "script", "image", "sub_frame", "xmlhttprequest", "media",
  "font", "stylesheet", "ping", "websocket", "other",
];

async function getSettings() {
  const data = await chrome.storage.local.get(DEFAULTS);
  return { ...DEFAULTS, ...data };
}

function hostOf(url) {
  try {
    const u = new URL(url);
    return /^https?:$/.test(u.protocol) ? u.hostname.toLowerCase() : "";
  } catch {
    return "";
  }
}

// 설정 → 동적 규칙 / 정적 규칙셋 반영
async function applySettings() {
  const s = await getSettings();
  const parsed = parseUserFilters(s.userFilters);

  await chrome.declarativeNetRequest.updateEnabledRulesets(
    s.enabled ? { enableRulesetIds: STATIC_RULESETS } : { disableRulesetIds: STATIC_RULESETS }
  );

  const rules = [];
  if (s.enabled) {
    const allowSites = [...new Set([...s.whitelist, ...parsed.allow])];
    allowSites.forEach((host, i) => {
      rules.push({
        id: WHITELIST_ID_BASE + i,
        priority: 100,
        action: { type: "allowAllRequests" },
        condition: { requestDomains: [host], resourceTypes: ["main_frame", "sub_frame"] },
      });
    });
    parsed.block.forEach((domain, i) => {
      rules.push({
        id: USER_BLOCK_ID_BASE + i,
        priority: 2,
        action: { type: "block" },
        condition: { urlFilter: `||${domain}^`, resourceTypes: BLOCK_TYPES },
      });
    });
  }

  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: existing.map((r) => r.id),
    addRules: rules,
  });

  await chrome.declarativeNetRequest.setExtensionActionOptions({
    displayActionCountAsBadgeText: s.enabled,
  });
  await chrome.action.setBadgeBackgroundColor({ color: "#4338ca" });
  await chrome.action.setTitle({ title: s.enabled ? "Adlull (켜짐)" : "Adlull (꺼짐)" });
}

function isSiteAllowed(settings, parsed, host) {
  if (!host) return true;
  return [...settings.whitelist, ...parsed.allow].some((d) => hostMatches(host, d));
}

// ---- 탭별 통계 (요소 숨김 개수) ----
async function setCosmeticCount(tabId, frameId, count) {
  const key = `cosmetic:${tabId}`;
  const data = (await chrome.storage.session.get(key))[key] || {};
  data[frameId] = count;
  await chrome.storage.session.set({ [key]: data });
}

async function getCosmeticCount(tabId) {
  const key = `cosmetic:${tabId}`;
  const data = (await chrome.storage.session.get(key))[key] || {};
  return Object.values(data).reduce((a, b) => a + b, 0);
}

async function getNetworkCount(tabId) {
  try {
    const { rulesMatchedInfo } = await chrome.declarativeNetRequest.getMatchedRules({ tabId });
    return rulesMatchedInfo.filter((m) => m.rule.rulesetId !== "_dynamic" || m.rule.ruleId >= USER_BLOCK_ID_BASE && m.rule.ruleId < USER_ALLOW_ID_BASE).length;
  } catch {
    return 0;
  }
}

chrome.tabs.onRemoved.addListener((tabId) => chrome.storage.session.remove(`cosmetic:${tabId}`));
chrome.tabs.onUpdated.addListener((tabId, info) => {
  if (info.status === "loading" && info.url) chrome.storage.session.remove(`cosmetic:${tabId}`);
});

// 압축 해제된(개발자 모드) 확장에서만 동작: 누적 차단 수 집계
if (chrome.declarativeNetRequest.onRuleMatchedDebug) {
  let pending = 0;
  let timer = null;
  chrome.declarativeNetRequest.onRuleMatchedDebug.addListener((info) => {
    if (info.rule.rulesetId === "_dynamic" && info.rule.ruleId < USER_BLOCK_ID_BASE) return;
    pending++;
    if (!timer) {
      timer = setTimeout(async () => {
        const { totalBlocked = 0 } = await chrome.storage.local.get("totalBlocked");
        await chrome.storage.local.set({ totalBlocked: totalBlocked + pending });
        pending = 0;
        timer = null;
      }, 1000);
    }
  });
}

// ---- 메시지 처리 ----
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  handle(msg, sender).then(sendResponse, (e) => sendResponse({ error: String(e) }));
  return true;
});

async function handle(msg, sender) {
  const s = await getSettings();
  const parsed = parseUserFilters(s.userFilters);

  switch (msg.type) {
    case "getContentConfig": {
      const host = hostOf(sender.tab?.url || "");
      const active = s.enabled && !isSiteAllowed(s, parsed, host);
      const userSelectors = parsed.cosmetic
        .filter((c) => c.domains.length === 0 || c.domains.some((d) => hostMatches(host, d)))
        .map((c) => c.selector);
      return { active, userSelectors };
    }
    case "cosmeticCount":
      if (sender.tab) await setCosmeticCount(sender.tab.id, sender.frameId, msg.count);
      return { ok: true };
    case "getPopupState": {
      const host = hostOf(msg.url || "");
      return {
        host,
        enabled: s.enabled,
        siteAllowed: isSiteAllowed(s, parsed, host),
        siteWhitelisted: s.whitelist.includes(host),
        network: await getNetworkCount(msg.tabId),
        cosmetic: await getCosmeticCount(msg.tabId),
        totalBlocked: s.totalBlocked,
        hasDebugCounter: !!chrome.declarativeNetRequest.onRuleMatchedDebug,
      };
    }
    case "setEnabled":
      await chrome.storage.local.set({ enabled: !!msg.value });
      return { ok: true };
    case "setSiteAllowed": {
      const list = new Set(s.whitelist);
      msg.value ? list.add(msg.host) : list.delete(msg.host);
      await chrome.storage.local.set({ whitelist: [...list] });
      return { ok: true };
    }
    case "addUserFilter": {
      const text = s.userFilters.trimEnd();
      await chrome.storage.local.set({ userFilters: (text ? text + "\n" : "") + msg.line + "\n" });
      return { ok: true };
    }
    default:
      return { error: "unknown message" };
  }
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.enabled || changes.whitelist || changes.userFilters)) {
    applySettings();
  }
});

chrome.runtime.onInstalled.addListener(applySettings);
chrome.runtime.onStartup.addListener(applySettings);
