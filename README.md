<p align="center"><img src="assets/pocketshell-1024.png" width="128" alt="PocketShell"></p>

# PocketShell (포켓쉘)

PC(Windows)의 터미널 세션을 Android 폰에서 같은 색상(24bit)·TUI 그대로 보고 입력하는 도구.
PC 호스트가 ConPTY로 셸을 소유하고 원본 VT 스트림을 폰(네이티브 Termux 엔진)과 PC(Windows Terminal 탭)에 동시에 보냄.
PC에서 직접 띄운 터미널과 Claude/GPT(codex)가 돌고 있는 콘솔도 폰에서 열 수 있음.

## 구성
| 경로 | 내용 |
|---|---|
| `host/src/PocketShell.Tray` | 트레이 앱 `PocketShell.exe` — 서버를 백그라운드로 실행·중지, 휴대폰 연결(PIN), 로그인 시 자동 실행 |
| `host/src/Mpta` | 서버 + CLI `psh.exe` (`psh serve`, `psh attach` …) |
| `android/` | Kotlin Android 앱 `com.pocketshell.app` (Termux terminal-emulator/view 기반, Apache-2.0) |
| `qa/Mpta.QA` | E2E QA 드라이버 |
| `assets/` | 아이콘 (`scripts/make_icons.py`로 생성) |
| `docs/DESIGN.md` | 프로토콜/구조 계약 |

## 빠른 시작
1. PC: [Releases](../../releases)에서 `pocketshell-host-win-x64-*.zip`을 받아 압축 해제 (.NET 8 Desktop + ASP.NET Core 런타임 필요)
2. `PocketShell.exe` 실행 → 트레이 아이콘이 생기고 서버가 백그라운드에서 실행됨 (포트 7380, LAN·Tailscale·루프백에만 바인딩, TLS). 처음 실행하면 Windows 로그인 시 자동 실행이 켜짐
3. 트레이 아이콘 더블클릭 또는 메뉴 **휴대폰 연결 (PIN)** → 6자리 PIN과 인증서 지문 표시
4. 폰: `pocketshell-android-*.apk` 설치 → PIN 연결에서 이 PC 선택(또는 IP 입력) → PIN 입력 → 지문 확인
5. 폰에서 세션 목록 선택 또는 새 세션 생성 → PC에는 Windows Terminal 탭이 자동으로 열림

트레이 메뉴: 서버 시작/중지, 휴대폰 연결 (PIN), 데이터·로그 폴더 열기, Windows 시작 시 자동 실행, 종료(서버도 중지).
서버가 예기치 않게 꺼지면 트레이가 다시 띄움(5분에 최대 3회).

PC CLI: `psh list`, `psh new`, `psh attach <id>` (분리: `Ctrl+]` 후 `d`), `psh pair --pin`, `psh devices`, `psh revoke <id>`

접속 순서: 폰에 Tailscale이 켜져 있으면 Tailscale 주소를 먼저, 아니면 같은 Wi-Fi의 LAN 주소를 먼저 시도함.

## 빌드
```
dotnet build host/Mpta.sln && dotnet test host/Mpta.sln
cd android && gradlew.bat test assembleRelease   # JDK 17, Android SDK 35
dotnet run --project qa/Mpta.QA -c Release       # E2E QA
powershell -File scripts/release.ps1 -Version x.y.z   # 릴리스 (GitHub Releases → 자동 업데이트)
```

## 보안
- 원격 셸이므로 같은 LAN 또는 Tailscale 안에서만 사용. 0.0.0.0 바인딩 없음.
- 페어링: 일회용 PIN(5분, 5회 실패 시 무효) 또는 QR → 기기 토큰(서버에는 SHA-256만 저장), PC에서 `psh revoke`로 취소.
- 클라이언트는 서버 자체서명 인증서를 SHA-256 지문으로 핀닝.
- 데이터: `%APPDATA%\PocketShell` (인증서, 토큰, 세션 기록, 로그).
