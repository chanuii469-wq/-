// 사용자 필터 파서 (AdGuard/ABP 형식의 일부를 지원)
//   ||ads.example.com^        네트워크 차단
//   @@||example.com^          해당 사이트에서 차단 해제
//   ##.ad-banner              모든 사이트에서 요소 숨김
//   example.com##.sidebar-ad  특정 사이트에서 요소 숨김
//   ! 또는 # 으로 시작하는 줄  주석

export function parseUserFilters(text) {
  const result = { block: [], allow: [], cosmetic: [], errors: [] };
  const lines = (text || "").split(/\r?\n/);
  lines.forEach((raw, idx) => {
    const line = raw.trim();
    if (!line || line.startsWith("!") || (line.startsWith("#") && !line.startsWith("##"))) return;

    const cosmeticAt = line.indexOf("##");
    if (cosmeticAt !== -1) {
      const domains = line.slice(0, cosmeticAt).split(",").map((d) => d.trim().toLowerCase()).filter(Boolean);
      const selector = line.slice(cosmeticAt + 2).trim();
      if (!selector || !isValidSelector(selector)) {
        result.errors.push({ line: idx + 1, text: line, reason: "잘못된 CSS 선택자" });
        return;
      }
      result.cosmetic.push({ domains, selector });
      return;
    }

    const allow = line.startsWith("@@");
    const body = allow ? line.slice(2) : line;
    const m = body.match(/^\|\|([a-z0-9.-]+\.[a-z]{2,})\^?$/i);
    if (!m) {
      result.errors.push({ line: idx + 1, text: line, reason: "지원하지 않는 형식" });
      return;
    }
    (allow ? result.allow : result.block).push(m[1].toLowerCase());
  });
  return result;
}

function isValidSelector(selector) {
  // 서비스 워커에는 document가 없으므로 가능할 때만 검사
  if (typeof document === "undefined") return !/[{}]/.test(selector);
  try {
    document.createDocumentFragment().querySelector(selector);
    return true;
  } catch {
    return false;
  }
}

export function hostMatches(host, domain) {
  return host === domain || host.endsWith("." + domain);
}
