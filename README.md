# AdShield 🛡️

광고 차단기 두 가지가 들어 있습니다.

- [`android/`](android/) — **안드로이드 앱**: 로컬 VPN으로 모든 앱의 광고·추적기를 차단
- 저장소 루트 — **크롬 확장 프로그램**

---

## 📱 안드로이드 앱

루팅 없이 로컬 VPN으로 기기의 DNS 요청을 가로채서, 광고·추적 도메인이면 차단하고 나머지는 실제 DNS 서버로 넘깁니다. 인터넷 트래픽 자체는 VPN을 거치지 않아 속도와 배터리에 거의 영향이 없습니다.

#### 기능
- **모든 앱 광고 차단**: 게임·앱 광고 SDK(AdMob, Unity Ads, AppLovin, 카울리 등), 웹 광고, 추적기
- **필터 구독**: AdGuard DNS 필터, StevenBlack hosts, OISD, Peter Lowe 목록 + URL로 직접 추가 · 매일 자동 업데이트
- **DNS 요청 기록**: 어떤 앱이 어떤 도메인에 접속하는지 실시간 확인(Android 10 이상), 눌러서 바로 차단/허용
- **통계**: 차단 수, 차단율, 광고를 가장 많이 요청한 앱, 가장 많이 차단된 도메인
- **앱별 제외**: 은행·결제 앱처럼 문제가 생기는 앱은 차단에서 제외
- **암호화 DNS(DoH)**: Cloudflare / Google / Quad9
- **사용자 규칙**: `||도메인^` 차단, `@@||도메인^` 허용
- **빠른 설정 타일**, **부팅 시 자동 시작**, **상시 VPN** 지원

### 설치
1. `AdShield-1.0.0.apk`를 휴대폰에 옮기거나, GitHub **Actions → Android APK → AdShield-apk** 에서 내려받습니다.
2. 파일을 열고 "출처를 알 수 없는 앱 설치"를 허용해 설치합니다.
3. 앱에서 방패를 누르고 VPN 연결 요청을 허용합니다.

### 꼭 확인할 것
- **비공개 DNS(Private DNS)를 꺼야** 합니다. 설정 › 연결 › 기타 연결 설정 › 비공개 DNS → "사용 안 함" (지정 모드면 앱이 경고를 띄웁니다)
- 다른 VPN과 동시에 쓸 수 없습니다(안드로이드 제한).
- 유튜브 영상 광고처럼 콘텐츠와 같은 서버에서 나오는 광고는 DNS 방식으로 막을 수 없습니다.

### 직접 빌드
```bash
cd android
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest
```
Android SDK(API 35)와 JDK 17이 필요합니다.

---

## 🧩 크롬 확장 프로그램

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

### 설치 방법

1. 이 저장소를 내려받습니다 (Code → Download ZIP 후 압축 해제).
2. 크롬 주소창에 `chrome://extensions` 입력
3. 오른쪽 위 **개발자 모드** 켜기
4. **압축해제된 확장 프로그램을 로드합니다** → 이 폴더 선택

### 필터 목록 수정

차단할 도메인은 `filters/ads.txt`, `filters/trackers.txt`에 한 줄에 하나씩 적고 규칙을 다시 만듭니다.

```bash
python3 tools/build_rules.py
```

그다음 `chrome://extensions`에서 새로고침 버튼을 누르면 반영됩니다.

### 구조

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

### 한계

- 유튜브 영상 광고처럼 같은 도메인에서 나오는 광고는 막지 못합니다.
- 누적 차단 수는 개발자 모드(압축 해제)로 설치했을 때만 집계됩니다.
- 기본 필터 목록은 작게 시작했습니다. 필요한 도메인을 `filters/`에 추가해 쓰세요.
