# AdShield 🛡️

AdGuard 같은 크롬(크로미움) 광고 차단 확장 프로그램입니다. Manifest V3 기반이라 Chrome, Edge, Whale, Brave 등에서 동작합니다.

## 기능

- **네트워크 차단**: 광고 네트워크(구글 애드센스, 크리테오, 타불라, 데이블, 모비온 등)와 추적기(구글 애널리틱스, 핫자 등) 요청 차단
- **요소 숨김**: 광고 자리(`ins.adsbygoogle`, `div-gpt-ad` 등)를 페이지에서 숨김
- **요소 선택기**: 팝업에서 🎯 버튼을 누르고 거슬리는 요소를 클릭하면 그 사이트에서 계속 숨김
- **사이트별 켜기/끄기**: 광고 차단 때문에 깨지는 사이트만 예외 처리
- **사용자 필터**: AdGuard/uBlock 문법 일부 지원
  ```
  ||ads.example.com^          도메인 차단
  @@||example.com^            해당 사이트 차단 해제
  ##.ad-box                   모든 사이트에서 요소 숨김
  example.com##.sidebar-ad    특정 사이트에서만 요소 숨김
  ! 주석
  ```
- **통계**: 아이콘 배지에 탭별 차단 수, 팝업에 차단 요청 / 숨긴 광고 / 누적 차단 수

## 설치 방법

1. 이 저장소를 내려받습니다 (Code → Download ZIP 후 압축 해제).
2. 크롬 주소창에 `chrome://extensions` 입력
3. 오른쪽 위 **개발자 모드** 켜기
4. **압축해제된 확장 프로그램을 로드합니다** → 이 폴더 선택

## 필터 목록 수정

차단할 도메인은 `filters/ads.txt`, `filters/trackers.txt`에 한 줄에 하나씩 적고 규칙을 다시 만듭니다.

```bash
python3 tools/build_rules.py
```

그다음 `chrome://extensions`에서 새로고침 버튼을 누르면 반영됩니다.

## 구조

```
manifest.json         확장 설정
filters/*.txt         차단 도메인 목록 (원본)
rules/*.json          빌드된 declarativeNetRequest 규칙
src/background.js     설정 반영, 사이트 예외, 통계
src/content.js        요소 숨김 + 요소 선택기
src/filters.js        사용자 필터 파서
src/popup.*           팝업 UI
src/options.*         설정 페이지
tools/                규칙/아이콘 생성 스크립트
```

## 한계

- 유튜브 영상 광고처럼 같은 도메인에서 나오는 광고는 막지 못합니다.
- 누적 차단 수는 개발자 모드(압축 해제)로 설치했을 때만 집계됩니다.
- 기본 필터 목록은 작게 시작했습니다. 필요한 도메인을 `filters/`에 추가해 쓰세요.
