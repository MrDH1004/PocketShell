# mpta Android 클라이언트

계약: `../docs/DESIGN.md` §2·§4·§5·§8. 패키지 `com.mpta.client`, minSdk 26, compile/targetSdk 35.

## 빌드
```
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot
gradlew.bat test assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. `local.properties`(sdk.dir)는 커밋하지 않는다.

## 버전
| 도구 | 버전 | 근거 |
|---|---|---|
| Gradle | 8.10.2 | AGP 8.7 최소 Gradle 8.9 (developer.android.com/build/releases/about-agp) |
| AGP | 8.7.2 | API 35 최소 AGP 8.6.0 (같은 문서 "Minimum versions of tools for Android API level") |
| Kotlin | 2.1.20 | KGP 2.1.20: Gradle 7.6.3–8.12.1, AGP 7.3.1–8.7.2 (kotlinlang.org/docs/gradle-configure-project.html) |
| JDK | 17 | AGP 8.x 요구 |

## 모듈
- `terminal-emulator`, `terminal-view`: Termux(Apache-2.0)에서 vendoring. 변경 내역은 각 모듈의 `NOTICE`.
  JNI/pty 제거, `RemoteTerminalSession`(네트워크 백엔드)으로 대체. truecolor·WcWidth는 Termux 코드 그대로.
- `app`: 페어링(LAN 검색/주소 입력 + 6자리 PIN + 인증서 지문 확인), 세션 목록(실행 중/외부/최근), 터미널 화면(특수키 줄).

## 한글 IME
Termux 기본 `InputType.TYPE_NULL`에서는 Gboard 등 한글 IME가 조합을 하지 못한다(자모 단위 키 이벤트로 떨어짐).
`TerminalView.onCreateInputConnection`에서 `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE | TYPE_TEXT_FLAG_NO_SUGGESTIONS`를 쓴다.
- 조합 중 텍스트는 `BaseInputConnection`의 Editable에만 머물고 터미널로 보내지 않는다.
- `commitText`/`finishComposingText` 때 확정 텍스트를 code point → UTF-8로 전송한다(`ImeTextDecoder`).
- `MULTI_LINE`이라 Enter가 `"\n"`로 오고 `\r`로 바뀐다. 에디터 액션으로 오는 IME는 `performEditorAction`에서 `\r` 전송.
- `VISIBLE_PASSWORD`는 쓰지 않는다(Gboard가 비밀번호 칸에서 영문 자판을 강제).
- 한계: 조합 중인 마지막 음절은 확정(다음 음절 입력, 스페이스, Enter) 전까지 터미널 화면에 보이지 않는다.

## 연결
- 서버 인증서는 DER SHA-256 지문으로만 신뢰(`FingerprintTrustManager` + `FingerprintHostnameVerifier`).
- hosts는 페어링 payload 순서(LAN → Tailscale)로 시도, 연결 단계 실패 시 다음 host.
- 재연결: 1,2,4,8,10,10…초. `onResume`과 기본 네트워크 변경 시 즉시. 연결될 때마다 emulator를 초기화한 뒤 서버 재생을 받는다.
- 앱이 백그라운드(onStop)로 가면 WebSocket을 닫는다(PTY 크기 정책에서 빠짐). 돌아오면 즉시 재연결.
- 토큰은 앱 전용 SharedPreferences(`allowBackup=false`)에 저장. androidx security-crypto는 deprecated라 쓰지 않음.
