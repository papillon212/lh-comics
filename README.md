# LH Comics

NAS(SMB)·FTP·WebDAV·기기 폴더에 있는 만화를 압축을 풀지 않고 바로 보는 뷰어.
압축 파일 안의 압축 파일(중첩)까지 지원한다.

| 구성 | 위치 | 설명 |
|---|---|---|
| core | `core/` | 소스 접속(SMB2/3, FTP/FTPS, WebDAV, 로컬), 압축(zip/cbz, rar/cbr(RAR4), 7z/cb7), 중첩 압축, 자연 정렬 |
| Android 앱 | `android/` | Jetpack Compose. 광고(AdMob) + 광고 제거 1회 결제 |
| 서버 | `server/` | Ktor. NAS 도커에서 실행, core 재사용, PWA 서빙, 읽은 위치 동기화 |
| iOS용 PWA | `pwa/` | Vite + TypeScript. 아이폰 Safari "홈 화면에 추가" |

## Android 앱

```bash
./gradlew :android:assembleDebug        # android/build/outputs/apk/debug/
./gradlew :android:assembleRelease      # 서명 설정 필요
```

출시 전 바꿀 것 (`android/build.gradle.kts`):
- `admobAppId`, `ADMOB_BANNER_ID`, `ADMOB_INTERSTITIAL_ID` → AdMob 콘솔의 실제 ID (지금은 구글 테스트 ID)
- `REMOVE_ADS_PRODUCT_ID` → Play Console에 같은 ID로 인앱 상품 등록
- `applicationId`, 앱 이름/아이콘

광고 정책: 읽는 화면에는 광고 없음. 목록 하단 배너 + 책을 닫을 때 전면 광고(3권마다, 최소 5분 간격).

## 서버 + PWA (도커)

```bash
# docker-compose.yml 의 볼륨 경로와 LHC_PASSWORD 를 바꾼 뒤
docker compose up -d
# 아이폰 Safari에서 http://NAS주소:8080 → 공유 → 홈 화면에 추가
```

환경 변수: `LHC_PASSWORD`, `LHC_LIBRARIES`("이름=/경로,..."), `LHC_DATA`(/data), `LHC_PORT`(8080).
`/data/sources.json`에 SMB/FTP/WebDAV 소스를 추가로 넣을 수 있다.

집 밖에서 쓰려면 Tailscale(무료 HTTPS 인증서) 또는 시놀로지 DDNS + 리버스 프록시(HTTPS)를 권장한다.

로컬 개발:
```bash
./gradlew :server:installDist
LHC_PASSWORD=test LHC_LIBRARIES="샘플=/path/to/comics" LHC_DATA=/tmp/lhc-data LHC_WEB=$PWD/pwa/dist \
  LHC_PORT=8080 server/build/install/lh-comics-server/bin/lh-comics-server
cd pwa && npm install && npm run dev   # /api 는 8080으로 프록시
```

## 테스트

```bash
./gradlew :core:test :server:test
# 실제 NAS 확인(계정은 환경 변수로만 전달)
LHC_SMB_HOST=... LHC_SMB_SHARE=... LHC_SMB_USER=... LHC_SMB_PASS=... \
  ./gradlew :core:test --tests '*SmbIntegrationTest*' -i
```

## 알려진 제한 / 다음 할 일
- RAR5 미지원(junrar 한계) → 네이티브 libarchive 도입 검토
- 매우 긴 웹툰 이미지는 4096px로 축소 표시(타일 디코딩 미구현)
- 두 쪽 펼침 보기, 이미지 필터, SFTP 미구현
- Android 앱의 서버 진도 동기화 미연동(서버 API는 준비됨)
- 저장소 비밀번호는 앱 전용 저장소에 평문 저장 → Android Keystore 암호화 필요
