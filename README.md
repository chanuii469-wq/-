# Adlull 🛡️

광고 차단기 두 가지가 들어 있습니다.

> ⚠️ **개인용입니다.** 공개 배포하지 않습니다. 저장소는 비공개로 유지하고, APK는 본인 기기에만 설치하세요.
> 공개 배포 전에 남은 일: 특허청(KIPRIS) 상표 검색으로 'Adlull' 최종 확인. (이름 변경, 필터 목록 정리, 라이선스 고지, 정식 서명 키, [개인정보 처리방침](PRIVACY.md)은 완료)

- [`android/`](android/) — **안드로이드 앱**: 로컬 VPN으로 모든 앱의 광고·추적기를 차단
- 저장소 루트 — **크롬 확장 프로그램**

---

## 📱 안드로이드 앱

루팅 없이 로컬 VPN으로 기기의 DNS 요청을 가로채서, 광고·추적 도메인이면 차단하고 나머지는 실제 DNS 서버로 넘깁니다. 인터넷 트래픽 자체는 VPN을 거치지 않아 속도와 배터리에 거의 영향이 없습니다.

#### 기능
- **모든 앱 광고 차단**: 게임·앱 광고 SDK(AdMob, Unity Ads, AppLovin, 카울리 등), 웹 광고, 추적기
- **필터 구독**: AdGuard DNS 필터(GPL-3.0), StevenBlack hosts(MIT) + URL로 직접 추가 · 매일 자동 업데이트. 외부 목록은 앱에 포함하지 않고 켜면 배포처에서 직접 내려받음
- **빠르고 가벼움**: 차단 목록을 64비트 해시 배열로 저장(30만 개 기준 메모리 약 33MB → 약 2~4MB, 조회 1µs 미만), 컴파일된 목록을 디스크에 캐시해 시작 시 다시 해석하지 않음, DNS 응답 캐시(최대 2,048개, TTL 준수), IPv6 전용 망 대비 보조 DNS
- **개인정보 처리방침**: [PRIVACY.md](PRIVACY.md), 앱 설정 › 정보에서도 확인
- **오픈소스 라이선스**: 설정 › 정보에서 사용한 라이브러리와 필터 목록의 저작권·라이선스 확인
- **DNS 요청 기록**: 어떤 앱이 어떤 도메인에 접속하는지 실시간 확인(Android 10 이상), 눌러서 바로 차단/허용
- **통계**: 차단 수, 차단율, 광고를 가장 많이 요청한 앱, 가장 많이 차단된 도메인
- **앱별 세부 설정**: 앱마다 Adlull 적용 여부, 광고 차단, 추적기 차단, 요청 기록을 따로 켜고 끄기 (적용 여부 외의 항목은 Android 10 이상). 앱별 요청·차단 수와 최근 요청 확인
- **규칙별 켜기/끄기**: 사용자 규칙을 목록으로 보고 한 줄씩 스위치로 적용/해제, 모두 켜기/끄기, 텍스트 일괄 편집
- **암호화 DNS(DoH)**: Cloudflare / Google / Quad9
- **사용자 규칙**: `||도메인^` 차단, `@@||도메인^` 허용, `!- ` 로 시작하면 꺼진 규칙
- **빠른 설정 타일**, **부팅 시 자동 시작**, **상시 VPN** 지원

### 설치
1. `Adlull-1.3.0.apk`를 휴대폰에 옮기거나, GitHub **Actions → Android APK → Adlull-apk** 에서 내려받습니다.
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

### 서명 키
릴리스 APK는 정식 서명 키로 서명합니다. 키 파일은 **저장소에 올리지 않습니다** (`.gitignore` 처리).

- **직접 빌드할 때**: `adshield-release.jks`와 `keystore.properties`를 `android/` 폴더에 넣고 빌드
- **GitHub Actions로 빌드할 때**: 저장소 Settings › Secrets and variables › Actions에 아래 4개를 등록
  - `ADLULL_KEYSTORE_BASE64`: `base64 -w0 adshield-release.jks` 출력값
  - `ADLULL_STORE_PASSWORD`, `ADLULL_KEY_PASSWORD`: `keystore.properties`의 비밀번호
  - `ADLULL_KEY_ALIAS`: `adshield`
- 키가 없으면 디버그 키로 서명됩니다(이 경우 기존 앱 위에 덮어 설치 불가).
- **키를 잃어버리면 같은 앱으로 업데이트할 수 없습니다.** 안전한 곳에 백업해 두세요.

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
