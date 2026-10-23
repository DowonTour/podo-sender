# PodoSender (v0.1 — 검색 · 이름 표시 · 선택 연결)

기존 `podopop_client.apk` 와 같은 방식으로 PodoPop 전광판에 접속하는 안드로이드(Kotlin, Jetpack Compose) 앱입니다.
다음 단계(폰 갤러리의 사진/영상 전송)를 붙일 수 있도록 프로토콜·연결 계층을 UI 와 분리해 두었습니다.

## GitHub 로 APK 만들기 (PC 에 아무것도 설치하지 않음)
1. github.com 에 로그인(무료 계정) → 오른쪽 위 `+` → **New repository** → 이름 `podo-sender`, Public/Private 아무거나 → **Create repository**
2. 새 저장소 화면의 **uploading an existing file** 링크 → 이 폴더 **안의 파일과 폴더들**(`app`, `gradle`, `gradlew`, `settings.gradle.kts` …)을 모두 끌어다 놓기 → **Commit changes**
   (폴더째 올려도 동작하지만, 내용물을 바로 올리는 쪽이 깔끔합니다)
3. `.github` 폴더가 올라가지 않았다면(저장소 파일 목록에 없음): **Add file → Create new file** → 이름 칸에 `.github/workflows/build-apk.yml` 입력(슬래시를 치면 폴더가 만들어짐) → 이 폴더의 같은 이름 파일 내용을 붙여넣고 **Commit**
4. 위 커밋이 끝나면 **Actions** 탭에서 `Build APK` 가 자동으로 시작됩니다(처음에는 "I understand my workflows, go ahead and enable them" 버튼을 눌러야 할 수 있음). 5~10분 후 초록색 체크가 뜨면 성공
5. 저장소 첫 화면 오른쪽 **Releases → PodoSender build N** → Assets 의 `app-debug.apk` 를 **폰에서** 내려받아 설치
   (설치 때 "출처를 알 수 없는 앱 허용"과 Play 프로텍트의 "그래도 설치"가 나올 수 있습니다. 디버그 키로 서명되어서 나오는 정상 경고입니다)
6. 빨간 X 로 실패하면: 해당 실행 → **Build debug APK**(또는 실패한 단계)를 펼쳐 빨간 오류 몇 줄을 복사해서 알려 주세요.

## 빌드
1. Android Studio(Ladybug 이상)에서 이 폴더를 엽니다. (Gradle 8.9 wrapper 포함)
2. 버전 알림이 뜨면 AGP / Compose BOM 을 Studio 가 제안하는 최신 안정 버전으로 올려도 됩니다.
3. 실기기(블루투스 필요, 에뮬레이터 불가)에서 실행합니다.
4. 프로토콜 단위 테스트: `./gradlew :app:testDebugUnitTest`

## 동작
1. 실행 → 권한 요청(Android 12+: 근처 기기 / 11 이하: 위치) → 블루투스 켜기 요청 → 자동 검색
2. 목록 = 이미 페어링된 기기 + 주변 검색 결과. 이름이 있는 기기가 위쪽, 이름은 블루투스 이름으로 표시
3. 기기를 누르면 RFCOMM(SPP) 접속 → ACK(95) 전송 → 전광판이 응답(96)으로 알려주는 이름을 "연결됨" 카드에 표시
4. ACK 응답이 없으면(PodoPop 전광판이 아니면) 연결을 닫고 안내 메시지 표시

※ Android 11 이하는 기기 설정에서 **위치(GPS)가 켜져 있어야** 주변 검색 결과가 나옵니다.

## 구조
```
protocol/PodoProtocol.kt   순수 Kotlin: 명령 상수, 프레임 인코더, JSON 생성, 수신 JSON 분리기   ← 단위 테스트됨
bluetooth/PodoSession.kt   소켓 1개에 대한 세션: 수신 코루틴, 직렬화된 send(), handshake(), closeGracefully()
bluetooth/PodoConnector.kt RFCOMM 접속 + 핸드셰이크 (취소 시 소켓을 닫아 connect() 중단)
bluetooth/DeviceScanner.kt 페어링 목록 + discovery 를 Flow 로 제공
ui/                        ViewModel + Compose 화면 + 권한 흐름
```

## 프로토콜 (기존 앱 분석 결과)
- 전송 프레임: `[명령 int32 BE][본문 길이 int32 BE (바이트)][본문]`
- 제어 메시지 본문: JSON `{"code":<명령>,"msg":"..."}` (+ 추가 필드)
- 수신(전광판→폰)은 헤더 없이 JSON 만 옴 → 기존 앱은 "read 1번 = JSON 1개"로 가정하지만, 이 앱은 중괄호 깊이로 분리해서 합쳐지거나 쪼개져 와도 처리
- 연결 순서: `95 ACK 요청` → `96 응답(msg = 전광판 이름)`. 종료는 `99`
- 사진/영상(다음 단계) 순서: 폰이 JSON `30`(사진)/`60`(영상)으로 알림 → 전광판이 `32`/`62`(받을 준비) 응답 → 폰이 원본 바이트를 한 프레임으로 전송(`34` 사진, `64`/`65` 영상)

## 검증 상태
| 항목 | 결과 |
|---|---|
| 송신 프레임 바이트가 기존 앱과 동일 | 통과 — 기존 앱의 `sendMsg` 로직을 그대로 실행해 얻은 값과 비교(ACK, 한글 종료 메시지, 이미지, 70KB 길이 필드) |
| 수신 분리기(합쳐짐/쪼개짐/한글 중간 절단/문자열 속 중괄호/쓰레기 바이트) | 통과 (13개 단위 테스트) |
| 세션: 핸드셰이크, 타임아웃, 정상 종료(99), 전광판 측 종료, EOF, 동시 대용량 전송 | 통과 — 가짜 소켓으로 실행 |
| Compose UI, 권한 흐름, 실제 블루투스 검색/접속 | **미검증** — 이 작업 환경에 Android SDK 가 없어 빌드·실행하지 못했습니다. Android Studio 에서 처음 빌드할 때 오탈자 수준의 컴파일 오류가 날 수 있습니다. |
| 실제 PodoPop 전광판과의 통신 | **미검증** |

## 기존 앱 대비 의도적으로 뺀 것
- INTERNET, 저장소 전체 접근, APK 설치, 부팅 시 실행 권한 및 자체 업데이트(HTTP로 받은 APK 설치) — 모두 제거
- 디버그 서명/`debuggable`, 평문 HTTP, 전체 백업, 외부 저장소 로그 파일 — 없음
- 기존 앱의 "저장된 BLE 이름/주소와 다르면 연결 거부" 화이트리스트 — 아직 없음 (필요하면 `PodoConnector` 뒤에 추가)

## 호환성 메모
- 기존 앱처럼 **비보안(insecure) RFCOMM** 으로 접속합니다. 전광판이 페어링 없이 받도록 만들어져 있기 때문이며, 통신 내용은 암호화되지 않습니다.
- 기존 앱은 연결 직후 용도에 따라 `90/91/92/94` 를 이어서 보냅니다(노선 시작/영상 시작/연결 확인/문구 표시). 이 버전은 ACK 까지만 합니다.

## 다음 단계: 갤러리 사진/영상 전송
- 선택: Photo Picker(`ActivityResultContracts.PickVisualMedia`) — 저장소 권한 불필요
- 사진: 전송 전 리사이즈·JPEG 압축 → `30` 알림 → `32` 대기 → `34` 전송
- 영상: 파일 스트림을 읽어 `60` → `62` → `64/65` (RFCOMM 처리량이 낮으므로 진행률 표시와 취소 필요)
- **먼저 확인할 것(수신기 쪽 제약, 이 앱 코드만으로는 알 수 없음):**
  1. 전광판 해상도와 권장 이미지 크기 / 형식(JPEG? PNG?)
  2. 영상 컨테이너·코덱·최대 용량
  3. `30/60` 알림 JSON 에 수신기가 요구하는 필드(`rid`, `pid`, `size`, `regdate` 등)의 의미 — 기존 앱은 서버 ID 로 관리하므로 갤러리 파일용 값을 어떻게 채울지
  4. `COMMAND_*_FAIL`(33/38/68) 응답 시의 재시도 규칙
