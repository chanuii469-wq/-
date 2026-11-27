"""필터 목록(도메인)을 declarativeNetRequest 규칙 JSON으로 변환합니다.

사용법: python3 tools/build_rules.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

RESOURCE_TYPES = [
    "script", "image", "sub_frame", "xmlhttprequest", "media",
    "font", "stylesheet", "ping", "websocket", "other",
]

LISTS = {
    "ads": ("filters/ads.txt", 1),
    "trackers": ("filters/trackers.txt", 10001),
}


def read_domains(path):
    out = []
    for line in (ROOT / path).read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if line and line not in out:
            out.append(line)
    return out


def main():
    for name, (src, start_id) in LISTS.items():
        rules = []
        for i, domain in enumerate(read_domains(src)):
            rules.append({
                "id": start_id + i,
                "priority": 1,
                "action": {"type": "block"},
                "condition": {
                    "urlFilter": f"||{domain}^",
                    "domainType": "thirdParty",
                    "resourceTypes": RESOURCE_TYPES,
                },
            })
        dest = ROOT / "rules" / f"{name}.json"
        dest.write_text(json.dumps(rules, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{dest.relative_to(ROOT)}: {len(rules)}개 규칙")


if __name__ == "__main__":
    main()
