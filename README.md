# MyPCToAndroid (mpta)

PC(Windows)의 터미널 세션을 Android 폰에서 같은 색상(24bit)·TUI 그대로 보고 입력하는 도구.
PC 호스트가 ConPTY로 셸을 소유하고 원본 VT 스트림을 폰(네이티브 Termux 엔진)과 PC(Windows Terminal 탭)에 동시에 보낸다.

> 상태: **v0.1.0-alpha** — PC 측 E2E QA 통과, Android는 단위 테스트만 통과(실기기 검증 전).

## 구성
| 경로 | 내용 |
|---|---|
| `host/` | .NET 8 호스트 + CLI (`mpta.exe`) |
| `android/` | Kotlin Android 앱 (Termux terminal-emulator/view 기반, Apache-2.0) |
| `qa/Mpta.QA` | E2E QA 드라이버 |
| `docs/DESIGN.md` | 프로토콜/구조 계약 |

## 빠른 시작
1. PC: [Releases](../../releases)에서 `mpta-host-win-x64-*.zip` 받아 압축 해제 (.NET 8 Desktop/ASP.NET Core 런타임 필요)
2. `mpta serve` 실행 (포트 7380, LAN·Tailscale·루프백에만 바인딩, TLS)
3. 다른 콘솔에서 `mpta pair` → QR 표시
4. 폰: `mpta-android-*.apk` 설치 → QR 스캔(또는 payload 붙여넣기)으로 페어링
5. 폰에서 세션 목록(실행 중/외부/최근) 선택 또는 새 세션 생성 → PC에는 Windows Terminal 탭이 자동으로 열림

PC CLI: `mpta list`, `mpta new`, `mpta attach <id>` (분리: `Ctrl+]` 후 `d`), `mpta devices`, `mpta revoke <id>`

## 빌드
```
dotnet build host/Mpta.sln && dotnet test host/Mpta.sln
cd android && gradlew.bat test assembleDebug     # JDK 17, Android SDK 35
dotnet run --project qa/Mpta.QA -c Release       # E2E QA
```

## 보안
- 원격 셸이므로 같은 LAN 또는 Tailscale 안에서만 사용. 0.0.0.0 바인딩 없음.
- 페어링: 일회용 코드(5분) → 기기 토큰(서버에는 SHA-256만 저장), PC에서 `mpta revoke`로 취소.
- 클라이언트는 서버 자체서명 인증서를 SHA-256 지문으로 핀닝.
