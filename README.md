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

트레이 메뉴: 서버 시작/중지, 휴대폰 연결 (PIN), 프로젝트 폴더, 데이터·로그 폴더 열기, Windows 시작 시 자동 실행, 종료(서버도 중지).
**프로젝트 폴더…**: 폰에서 새 프로젝트를 만들 상위 폴더를 추가·삭제(여러 개 가능, `%APPDATA%\PocketShell\projects.json`). 이 PC에서 찾은 AI CLI(claude, codex)도 표시됨.
서버가 예기치 않게 꺼지면 트레이가 다시 띄움(5분에 최대 3회).

PC CLI: `psh list`, `psh new`, `psh attach <id>` (분리: `Ctrl+]` 후 `d`), `psh pair`, `psh devices`, `psh revoke <id>`, `psh update [--check]`

## 폰 앱 사용법

**세션 목록**
- **실행 중**: 폰이나 `psh new`로 만든 PocketShell 세션.
- **외부**: PC에서 직접 띄운 터미널(Windows Terminal, 탐색기, VS Code·Cursor·JetBrains 등)과 Claude/GPT(codex)가 돌고 있는 콘솔만 표시. 항목에는 `Claude`/`GPT` 표시와 폴더만 나옴.
- **최근**: 끝난 세션 기록. 누르면 같은 폴더·셸로 다시 시작.
- 외부 항목 오른쪽 아이콘(화면+플러스): 그 콘솔의 폴더에서 새 터미널 열기 (실행 중인 프로그램을 옮기지는 않음).
- `+` 버튼 → **새 프로젝트**: 프로젝트 폴더(PC 트레이에서 지정)·이름·AI(Claude / GPT(Codex), PC에 설치된 것만)를 고르면 `<폴더>\<이름>`을 만들고(이미 있으면 그대로 사용) 그 폴더에서 claude/codex를 실행한 세션이 열림.
- `+` 버튼 → **새 터미널**: 새 세션(pwsh/powershell/cmd/bash). 세션을 길게 누르면 삭제.

**터미널 화면**
- 상단 바 아이콘: 원래 크기로 맞춤(네 모서리) · 키보드 열기/닫기.
- 외부 콘솔은 PC 창 크기를 바꿀 수 없어서 폰이 글자 크기를 맞춤.
  - 키보드가 없으면 PC 화면 전체가 들어가게 맞춤.
  - 키보드가 올라오면 폭에 맞추고 입력창 부근(아래쪽)을 보여줌.
- 손가락 확대·축소 가능. 원래 크기 아이콘을 누르면 다시 맞춤.
- 위로 스크롤하면 PC 화면 위로 밀려난 지난 내용을 볼 수 있음. 상태줄에 "기록 N줄 위"가 표시되고, 글자를 입력하거나 끝까지 내리면 실시간 화면으로 돌아옴.
  - Windows Terminal 안의 콘솔은 지난 내용을 콘솔에 남기지 않음. 그래서 PC 서버가 Claude/GPT 콘솔을 백그라운드로 계속 읽어 밀려난 줄을 모아둠(콘솔당 최대 3000줄).
  - 기록은 PC 서버가 실행 중인 동안만 쌓임. 일반 셸은 폰이 열고 있을 때만 쌓임.
- 한글 입력은 조합 중인 글자도 바로 PC에 보임.
- 상단 바 이미지 아이콘: 폰 사진·스크린샷을 골라 Claude Code에 첨부. PC 클립보드에 이미지를 넣고 Alt+V를 보내는 방식이라 PC에서 Alt+V를 누른 것과 같음(`[Image #N]`). PC 클립보드 내용은 그 이미지로 바뀜. 긴 변 2560px 넘는 사진은 줄여서 보냄.
- 아래 키 바(좌우로 밀기): ESC, TAB, ENTER, CTRL, 방향키, HOME/END, PGUP/PGDN 등.

**접속**
- 폰에 Tailscale이 켜져 있으면 Tailscale 주소를 먼저, 아니면 같은 Wi-Fi의 LAN 주소를 먼저 시도함. 성공한 주소는 네트워크가 바뀔 때까지 먼저 씀.
- 연결이 끊기면 자동으로 다시 붙음.

**업데이트**
- PC 호스트와 폰 앱 모두 GitHub Releases에서 새 버전을 확인함(PC는 6시간마다). 폰 세션 목록 위에 업데이트 상태가 표시됨.

## 빌드
```
dotnet build host/Mpta.sln && dotnet test host/Mpta.sln
cd android && gradlew.bat test assembleRelease   # JDK 17, Android SDK 35
dotnet run --project qa/Mpta.QA -c Release       # E2E QA
powershell -File scripts/release.ps1 -Version x.y.z   # 릴리스 (GitHub Releases → 자동 업데이트)
```

## 보안
- 원격 셸이므로 같은 LAN 또는 Tailscale 안에서만 사용. 0.0.0.0 바인딩 없음.
- 페어링: 일회용 PIN(5분, 5회 실패 시 무효) + 인증서 지문 확인 → 기기 토큰(서버에는 SHA-256만 저장), PC에서 `psh revoke`로 취소.
- 클라이언트는 서버 자체서명 인증서를 SHA-256 지문으로 핀닝.
- 데이터: `%APPDATA%\PocketShell` (인증서, 토큰, 세션 기록, 로그).
