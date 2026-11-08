# PocketShell (구 MyPCToAndroid / mpta, 코드명 pkshell) — 설계/계약 문서 (SSOT)

사양: `.omc/specs/deep-interview-pc-terminal-to-android.md` (AC-1~22). 이 문서는 구현 계약이다. 컴포넌트 간 인터페이스를 바꾸면 이 문서를 먼저 수정한다.

## 0. 저장소 구조
```
host/                      .NET 8 솔루션 (Pkshell.sln)
  src/Pkshell.Core/           세션/ConPTY/스크롤백/크기정책/인증/외부콘솔/프로토콜 (class lib, net8.0-windows)
  src/Pkshell/                단일 exe `psh.exe` (serve | list | new | attach | pair | devices | revoke | console-reader | console-info)
  tests/Pkshell.Tests/        xUnit 단위 테스트
qa/Pkshell.QA/                E2E QA 드라이버 (.NET 8 console) — 호스트를 실제로 띄워 AC 검증, 리포트 출력
android/                   Kotlin Android 앱 (Gradle, AGP), 모듈: app, terminal-emulator, terminal-view (Termux에서 vendoring, Apache-2.0)
docs/
```

## 1. 호스트 런타임
- 언어: C# / .NET 8 (`net8.0-windows`), ASP.NET Core Kestrel (minimal API + WebSockets).
- 데이터 디렉터리: `PKSHELL_HOME` 환경변수, 없으면 `%APPDATA%\PocketShell`. 구조:
  - `config.json` `{ "port": 7380 }`
  - `cert.pfx` 자체서명 인증서(최초 실행 시 생성, RSA 2048, CN=PocketShell, 10년). SHA-256 지문(DER, 소문자 hex, 구분자 없음)을 `cert.sha256`에 기록.
  - `local.token` 로컬 CLI용 토큰(최초 생성, 32바이트 base64url).
  - `devices.json` `[{id, name, tokenSha256, createdAt, revoked}]` (토큰 원문은 저장하지 않음)
  - `sessions/<id>/meta.json`, `sessions/<id>/output.vt` (원본 출력 바이트 append)
- 포트: 기본 7380, `--port` 또는 config로 변경. **HTTPS/WSS만** 제공.
- 바인딩: `127.0.0.1` + 사설 IPv4(10/8, 172.16/12, 192.168/16) + Tailscale CGNAT(100.64.0.0/10) 인터페이스 주소. `0.0.0.0` 바인딩 금지. `--bind <ip>` 반복 지정 시 그것만 사용(QA용).
- `psh serve` 는 포그라운드 실행(콘솔). 로그는 stdout + `logs/host.log`.

## 2. 인증
- 모든 `/api/*`(pair/claim 제외)와 `/ws/*`는 `Authorization: Bearer <token>` 필요. WebSocket은 헤더 대신 `?token=` 쿼리도 허용(하위 호환; Android 앱은 헤더만 사용 — URL에 토큰을 싣지 않음).
- 토큰 종류: (a) local.token — **루프백(127.0.0.1) 접속에서만** 유효, (b) 기기 토큰 — devices.json에 해시가 있고 revoked=false.
- 실패 시 HTTP 401. revoke 즉시 해당 기기의 열린 WS 연결도 종료(close code 4401).
- 페어링: 휴대폰은 PIN 방식만 사용(12.1절). QR 페어링은 제거됨.
  - code 모드(로컬 도구·QA 전용): `POST /api/pair/start` (local 토큰 필요, 본문 생략 또는 `mode`≠`"pin"`) → `{code, expiresAt}`. code: 일회용, 32바이트 base64url, 5분 만료.
  - `POST /api/pair/claim` (인증 없음) body `{code, deviceName}` → `{deviceId, token}`. 코드 재사용·만료 시 401.
- `GET /api/devices`, `DELETE /api/devices/{id}` (revoke) — local 토큰만 허용.
- 클라이언트(Android/CLI)는 서버 인증서를 **fp 지문으로 핀닝**(CA 검증 대신 지문 일치 검사).

## 3. 세션 모델
```
Session { id: string(12 hex), kind: "host"|"external"|"history"|"recent",
          title, shell: "cmd"|"pwsh"|"powershell"|"bash"|"wsl"|<exe>, cwd,
          state: "running"|"exited", createdAt, lastActivity (ISO8601 UTC),
          cols, rows, clients: [{id, type:"android"|"cli"|"qa", cols, rows}],
          pid (external), resumedFrom (nullable) }
```
- `host`: 호스트가 ConPTY(`CreatePseudoConsole`)로 띄운 세션. 클라이언트가 0명이어도 셸 종료 전까지 유지(AC-1).
- `history`: state=exited인 host 세션. 목록에 `kind:"history"`로 나옴. meta는 디스크에 남아 재부팅 후에도 유지(AC-4/11). 최근 50개 보관.
- `external`: 앱 밖 콘솔. id = `ext-<pid>`.
- `recent`: 닫힌 external 콘솔(콘솔 창 단위 = pid + 프로세스 시작 시각, 같은 폴더 창 2개면 2개, 최근 50개, `recent-externals.json`). id = `rec-<12 hex>`, state=exited.
  호스트가 20초마다 가벼운 프로세스 스냅샷(`ExternalConsoles.Peek`)으로, 그리고 `GET /api/sessions` 때마다 관찰한다.
  `POST /api/sessions/rec-…/resume` → 그 폴더·셸로 새 host 세션을 만들고 AI가 있었으면 `claude --continue` /
  `codex resume --last` 입력(Enter 포함) 후 항목 삭제. `DELETE`는 항목만 삭제.
- host 세션 셸 환경에서 CLAUDECODE / CLAUDE_CODE_* / CLAUDE_PID / CLAUDE_EFFORT / AI_AGENT 제거(호스트가 Claude Code 안에서 시작돼도 셸 안 claude가 child session으로 돌지 않게 — 그러면 대화 기록 저장이 꺼져 --continue 불가).
- host 세션에서 도는 AI CLI를 20초마다와 종료 시 meta.agent에 기록 → 업데이트·재시작으로 끝난 세션을 resume하면 `claude --continue` / `codex resume --last` 입력.
- 폰 '최근' 섹션 = history + recent, lastActivity 내림차순, 개수는 폰 메뉴 '최근 목록 개수'(기본 8, 0=숨김).
- 폰 터미널 상단: 연결 점(초록 = 연결됨) + 제목, 원래 크기로 맞춤 버튼, 프로젝트 파일(폴더) 버튼 + ⋮ 메뉴(정보: 전체 제목·연결 상태·크기 / 보기 설정(글꼴)). 보기 설정은 목록 메뉴에도 있다. 연결이 아닐 때(연결 중·끊김·재연결)는 제목 아래 한 줄로 상태 문구를 보여 색에만 의존하지 않고, 점의 설명(TalkBack)도 상태 문구.
- 오류 문구: "무엇이 실패 + 어떻게 복구" + 원인 한 줄(`ErrorText`). 파괴적 확인은 제목에 대상 이름, 버튼은 동사(종료하고 삭제 / 삭제 / 페어링 해제). 키 바 키캡은 TalkBack에 버튼 역할 + 읽을 이름(Page Up 등).
  글꼴 크기 6~32sp(기본 12). 핀치 = 갤러리처럼 화면 확대(1~5배, 글꼴·PC 크기는 그대로), 확대 중 한 손가락으로 이동(위/아래 끝을 넘겨 끌면 터미널 기록 스크롤), 원래 크기 버튼으로 1배.
  외부 콘솔: PC 창 크기를 따라가지 않는다 — 폰은 설정 글꼴 크기의 폰 격자로 그리고, PC 각 행을 폰 폭에 맞게 다시 배치한다(`core/Reflow`: 폭을 넘는 행은 먼저 가장 긴 채움 문자 연속(공백·─ 등)을 줄여 구분선·상자를 폰 폭에 맞추고, 그래도 넘치면 줄바꿈, 색 유지, 커서 위치 매핑). PC 창은 그대로. 기록 스크롤 단위 = 폰 줄. 확대는 핀치로만, 원래 크기 버튼 = 1배. 화면: 어두운 그래파이트 테마(values/colors.xml 토큰, 강조색 1개), 세션 카드에 상태 점(실행 중 초록)과 최근 활동 시간(외부 콘솔 = 제목·폴더·화면이 마지막으로 바뀐 때, ConsoleActivity), 키바는 키캡 모양.
  글꼴 = 시스템 monospace / D2Coding / JetBrains Mono(번들, OFL — `assets/licenses`). 외부 콘솔도 이 크기 그대로(폰 폭에 맞춰 줄바꿈).
- 프로젝트 파일(호스트 ≥ 0.2.20): 시작 폴더 = `GET /api/sessions/{id}`의 현재 cwd, 그 위로는 올라가지 않는다(폰 UI 제한).
  탐색기: 경로 이동 바(상위 폴더로 바로 이동), 이름 검색, 정렬(이름/최근 수정/크기, 폴더 먼저, 기억), 종류별 아이콘, 상대 시간, 폴더·파일 개수, 길게 누르면 작업 시트(보기/다운로드/열기/PC 경로·이름 복사). 뷰어: 줄 번호, 터미널 글꼴, 이미지 핀치 확대.
  - `GET /api/files?path=<절대경로>` → `{path, entries:[{name, dir, size, modified}]}` (폴더 먼저, 이름순)
  - `GET /api/files/content?path=` → 파일 바이트(range 지원). 폰: 텍스트(≤2MB, UTF-8→CP949) / 이미지 미리보기, 다운로드는 SAF CreateDocument.
  - `POST /api/files/upload?dir=&name=[&overwrite=1]` (본문 = 파일, ≤1GB, 임시파일 후 이동) → 있으면 409.
  - 페어링된 기기는 이미 셸 전체 권한이 있으므로 경로 샌드박스는 두지 않고 입력만 검증(절대경로, 순수 파일 이름).
- 셸 실행 명령:
  - cmd: `cmd.exe` + 환경변수 `PROMPT=$E]9;9;$P$E\$P$G` (OSC 9;9로 cwd 보고)
  - pwsh/powershell: `pwsh.exe -NoLogo -NoExit -Command <prompt 래핑 스크립트>` — 기존 prompt 함수 결과 앞에 `` `e]9;9;$PWD`e\ `` 를 출력
  - bash(Git Bash/WSL): `PROMPT_COMMAND`로 `printf '\e]7;file://%s%s\e\\' "$HOSTNAME" "$PWD"`
- cwd 추적: 출력 스트림에서 `OSC 9;9;<path>` 와 `OSC 7;file://host/<path>` 를 파싱해 meta.cwd 갱신(출력 바이트는 수정하지 않고 그대로 전달).
- 출력: ConPTY 출력 바이트를 **가공 없이** 모든 연결 클라이언트에 브로드캐스트 + output.vt에 append(AC-2). output.vt가 8MB를 넘으면 앞부분을 잘라 4MB로 유지(잘라낼 때 `\n` 경계 이후부터).
- 신규 클라이언트 attach 시: 먼저 output.vt 전체를 하나 이상의 바이너리 프레임으로 재생 → 이후 실시간 스트림(AC-3). 재생과 실시간 사이에 바이트 누락/중복이 없어야 함(세션 lock 하에 스냅샷+구독).
- 크기 정책(AC-6): PTY 크기 = 연결된 클라이언트들의 `min(cols)`, `min(rows)`. 클라이언트 0명이면 마지막 크기 유지. 변경 시 `ResizePseudoConsole` 후 모든 클라이언트에 `{"type":"size","cols","rows"}` 통지.
- 재개(AC-10): `POST /api/sessions/{historyId}/resume` → 새 host 세션 생성: 같은 shell, 같은 cwd(마지막 추적값), title 동일, `resumedFrom=historyId`. 새 세션 output.vt는 이전 output.vt 내용 + `\r\n\x1b[0m\x1b[2m── resumed <ISO> ──\x1b[0m\r\n` 으로 시작. 이전 history 항목은 목록에서 제거(새 세션이 대체).
- 종료: 셸 프로세스 종료 시 state=exited, 연결된 클라이언트에 `{"type":"exit","code":N}` 후 WS 유지(클라이언트가 닫음).

## 4. REST API (모두 JSON, camelCase)
| Method | Path | 설명 |
|---|---|---|
| GET | `/api/info` | `{name, version, fp, port, hosts}` |
| GET | `/api/sessions` | `{sessions:[Session...]}` — host(running) + external + history 모두 |
| GET | `/api/sessions/{id}` | Session |
| POST | `/api/sessions` | body `{shell?, cwd?, title?, cols?, rows?}` → Session (기본 shell=pwsh 있으면 pwsh 아니면 powershell, cwd=사용자 홈) |
| POST | `/api/sessions/{id}/resume` | history → 새 Session |
| DELETE | `/api/sessions/{id}` | 완전 삭제: running이면 종료를 기다린 뒤 기록까지 삭제(최근에도 안 남음), history면 삭제 |
| POST | `/api/external/{pid}/adopt` | 외부 콘솔의 cwd(가능하면)·셸 종류로 새 host 세션 생성 → Session (AC-14). cwd를 못 얻으면 사용자 홈 |
| POST | `/api/pair/start` | 2절 |
| POST | `/api/pair/claim` | 2절 |
| GET/DELETE | `/api/devices[/id]` | 2절 |
| GET | `/api/update` | 11.3절 |
| POST | `/api/update/check` | 11.3절 (즉시 확인) |
| POST | `/api/update/apply` | 11.3절 |

## 5. WebSocket 스트림
`wss://<host>:7380/ws/sessions/{id}?client=android|cli|qa&cols=N&rows=N[&openPcTab=0|1]`
- 서버→클라: **binary** = 원본 출력 바이트. **text** = JSON 제어: `{"type":"size","cols","rows"}`, `{"type":"exit","code"}`, `{"type":"hello","session":Session}`(연결 직후, 재생보다 먼저), `{"type":"replay-end"}`(재생 완료 표시).
- 클라→서버: **binary** = 입력 바이트(UTF-8/VT 그대로 PTY에 write). **text** = `{"type":"resize","cols","rows"}`.
- `cols`/`rows`(쿼리, resize 메시지, `POST /api/sessions` 본문)는 서버에서 1..1000으로 clamp. 쿼리의 0/누락은 "크기 미보고".
- 클라→서버 메시지 1개는 최대 1 MB. 초과 시 서버가 close code **1009**(message too big)로 종료.
- 서버 측 클라이언트별 출력 큐는 4096 청크로 제한. 소비가 밀려 큐가 넘치면 해당 소켓만 close code **4408**("slow consumer")로 종료 — 클라이언트는 재연결하여 replay로 복구(다른 클라이언트는 영향 없음).
- close code 요약: **4401** = 기기 revoke(재연결 중단), **4408** = slow consumer(재연결), **1009** = 메시지 과대, 1000 = 정상 종료.
- external 세션(`ext-<pid>`): 서버가 콘솔 버퍼를 ~200ms마다 폴링, 변경 시 **VT 프레임**(`\x1b[?25l\x1b[H` + 각 행 SGR 16색 + `\x1b[K` + 커서 위치 `\x1b[r;cH` + `\x1b[?25h`)으로 binary 전송. 클라이언트 입력 바이트는 KEY_EVENT로 변환해 `WriteConsoleInputW`(AC-13). 크기 정책 미적용.
- PC 탭 자동 열기(AC-21): `client=android` 연결이고 `openPcTab`≠0이며 해당 세션에 `cli` 클라이언트가 없고 진행 중인 열기 요청도 없으면 → `wt.exe -w 0 nt --title "<title>" "<psh.exe 경로>" attach <id> --port <서버포트> [--home <PKSHELL_HOME>]` 실행(`--home`은 서버에 PKSHELL_HOME이 설정된 경우만; WT 탭은 서버 환경변수를 상속하지 않으므로 필요). 15초 내 cli 클라이언트가 붙지 않으면 pending 해제. 포커스를 빼앗지 않도록 실행 전후 foreground 창을 기록하고 바뀌었으면 원래 창으로 복귀 시도. host 세션에만 적용.

## 6. 외부 콘솔(보조 모드)
- 열거: 프로세스 이름 `cmd.exe|powershell.exe|pwsh.exe` 중 부모가 위 셸이 아니고(최상위 셸), 호스트 세션의 자손이 아니며, pkshell 자신이 아닌 것. 그중 **사람이 띄운 것**(부모가 WindowsTerminal/OpenConsole/explorer/IDE 등 터미널 런처)이거나 **AI CLI 실행 중**(자손에 claude.exe→`agent:"claude"`, codex.exe→`agent:"gpt"`)인 것만 목록에 낸다 — MCP 서버·에이전트 도구 셸·네이티브 호스트 같은 백그라운드 셸 제외. `PKSHELL_EXTERNAL_ALL=1`이면 필터 없이 전부(QA용). 세션 JSON의 `agent`는 external에만 채워진다.
- 콘솔 API 호출은 **헬퍼 자식 프로세스**에서: `psh console-info <pid...>` → stdout JSON `[{pid,title,cols,rows}]`; `psh console-reader <pid>` → `FreeConsole`→`AttachConsole(pid)`→`CONOUT$`/`CONIN$` 열고, stdout(파이프)에 길이접두 프레임(4바이트 LE 길이 + [2바이트 LE cols + 2바이트 LE rows] + VT 프레임 바이트) 출력. 서버는 콘솔 창 크기가 바뀌면 프레임보다 먼저 `{"type":"size"}`를 방송하고, 새 클라이언트에는 hello 직후·replay 전에 보낸다(외부 콘솔은 클라이언트 크기로 리사이즈하지 않으므로 Android는 PC 행을 폰 폭에 맞게 다시 배치한다), stdin(파이프)으로 입력 바이트 수신(4바이트 LE 길이 + 바이트).
- 색 변환: Windows 속성 FG(B=1,G=2,R=4,I=8) → ANSI 인덱스 `(R?1:0)|(G?2:0)|(B?4:0)`, I면 90번대 아니면 30번대; BG 동일(40/100번대). `COMMON_LVB_UNDERSCORE`→`4`, `COMMON_LVB_REVERSE_VIDEO`→`7`.
- 입력 변환: 출력 가능 문자→유니코드 KEY_EVENT(down+up); `\r`→VK_RETURN; `\x7f`/`\b`→VK_BACK; `\t`→VK_TAB; 단독 `\x1b`→VK_ESCAPE; `\x1b[A/B/C/D`→화살표; `\x1b[H/F`→Home/End; `\x1b[5~/6~`→PgUp/PgDn; `\x1b[3~`→Delete; 0x01~0x1a→Ctrl+문자(VK 'A'+n-1, LEFT_CTRL_PRESSED, uChar=제어문자).

## 7. CLI (`psh.exe`)
- `psh serve [--port N] [--bind ip]... [--discovery-port N]` (`--discovery-port 0` = LAN 검색 끔, 12.2절)
- `psh list` 세션 표(종류/ID/제목/셸/폴더/상태/최근활동)
- `psh new [--shell pwsh] [--cwd D] [--title T] [--attach]` (기본: 생성 후 현재 콘솔에서 attach)
- `psh attach <id>` 현재 콘솔을 VT 입력/출력 모드로 전환(`ENABLE_VIRTUAL_TERMINAL_PROCESSING`, `ENABLE_VIRTUAL_TERMINAL_INPUT`, raw 입력), 콘솔 크기 보고(창 크기 변경 시 resize 전송), 출력 바이트 그대로 stdout에 쓰기. `client=cli`. 분리 단축키: `Ctrl+]` 후 `d`. 세션 exit 시 메시지 출력 후 종료.
- `psh pair` → PIN 발급(12.1절). `--pin`은 호환용으로 허용(효과 없음).
- `psh update [--check] [--force]` → 11.3절.
- `psh devices`, `psh revoke <id>`
- CLI는 `local.token`과 `cert.sha256`을 읽어 `wss://127.0.0.1:<port>`로 접속(핀닝).
- 클라이언트 verb 공통 옵션: `--port N`(우선순위: `--port` > 환경변수 `PKSHELL_PORT` > config.json port), `--home DIR`(> `PKSHELL_HOME` > `%APPDATA%\PocketShell`). `psh serve --port N`은 config.json을 바꾸지 않는다.
- `psh new --no-attach`: 생성 후 세션 id만 출력.

## 8. Android 앱
- Kotlin, minSdk 26, targetSdk/compileSdk 35, 패키지 `com.neststudio.pocketshell`.
- 터미널: Termux `terminal-emulator`/`terminal-view` 소스 vendoring(Apache-2.0, JNI pty 코드 제거). `TerminalSession`을 원격 백엔드(`RemoteTerminalSession`)로 대체: 입력 → WS binary, WS binary → `emulator.append()` + 화면 갱신. truecolor, CJK 폭은 Termux 엔진 그대로.
- 네트워크: OkHttp WebSocket + 지문 핀닝 TrustManager. hosts 시도 순서는 접속 시점의 폰 네트워크로 정함(HostSelector): 폰에 Tailscale VPN(100.64/10 주소)이 켜져 있으면 Tailscale 주소 → 폰 Wi-Fi/이더넷과 같은 서브넷의 LAN 주소 → 나머지, 꺼져 있으면 같은 LAN → 나머지 → Tailscale. 네트워크가 그대로인 동안은 마지막으로 성공한 주소를 먼저 시도함.
- 화면: (1) 페어링 — LAN 검색/주소 입력 + PIN + 지문 확인(12.1절), (2) 세션 목록 — 실행 중 / 외부 / 최근 섹션, 새 세션(셸 선택), 당겨서 새로고침, (3) 터미널 — 특수키 줄(ESC, CTRL(토글), ALT(토글), TAB, ←↑↓→, HOME, END, PGUP, PGDN, `|`, `~`, `/`, `-`), 외부 세션이면 "호스트로 인수" 버튼, history면 열 때 resume 호출.
- 재연결(AC-18): 연결 끊김 시 지수 백오프(1,2,4..최대 10초) 재연결, `onResume`·네트워크 변경 시 즉시 재연결. 재연결 시 emulator reset 후 서버 재생을 받음.
- 한글 IME(AC-17): `TerminalView.onCreateInputConnection`에서 `commitText`/조합 텍스트를 UTF-8로 전송, 조합 중 텍스트는 확정 시 전송.
- 크기: TerminalView의 cols/rows를 연결 쿼리와 `resize` 메시지로 보고.

## 9. QA 드라이버 (`qa/Pkshell.QA`)
- 임시 `PKSHELL_HOME` + 테스트 포트(예: 7391)로 `psh serve`를 자식 프로세스로 실행, 시나리오별 AC 체크, 결과를 `qa/out/report.md` + 콘솔 표(PASS/FAIL/SKIP + 증거)로 출력. 실패 시 exit code 1.
- 시나리오는 AC 번호별 함수. Android 렌더링은 `android` 모듈 JVM 단위테스트(`gradlew test`)가 담당하고, QA 드라이버는 adb 기기가 연결돼 있으면 APK 설치·실행 스모크까지 수행.

## 10. 호스트 구현 확정 사항 (계약 보충, host 구현 기준)
- 응답 코드: 성공 GET/POST = 200 + JSON(`POST /api/sessions`, resume, adopt 포함). `DELETE /api/sessions/{id}`, `DELETE /api/devices/{id}` = 204(본문 없음). 없음 = 404, history가 아닌 세션 resume = 409, 셸 실행 실패 등 = 400. 오류 본문 `{"error":"..."}`.
- local 토큰 전용 엔드포인트(`pair/start`, `devices`)에 기기 토큰으로 접근하면 401(403 아님).
- `GET /api/devices` → `{"devices":[{id,name,createdAt,revoked}]}` (tokenSha256은 노출하지 않음). revoke는 항목을 삭제하지 않고 `revoked:true`로 표시.
- `POST /api/sessions` 본문은 생략 가능. `shell:"pwsh"`인데 pwsh.exe가 PATH에 없으면 powershell.exe로 대체하고 Session.shell은 실제 값 `"powershell"`. title 기본값 = shell, cols/rows 기본 120x30.
- WS(host 세션) 순서: `hello` → 재생 binary 프레임(64KB 단위) → `replay-end` → `{"type":"size"}`(현재 PTY 크기, 항상) → 실시간. exited/history 세션에 붙으면 재생 후 `exit`이 온다. `client` 값이 android/cli/qa가 아니면 qa로 취급. cols/rows 미지정(0) 클라이언트는 크기 계산에서 제외.
- WS(external): `hello` → 마지막 프레임(있으면) → `replay-end` → 변경 시 프레임. 헬퍼 종료 시 `{"type":"exit","code":N}`.
- 외부 콘솔 목록은 2초 캐시. `console-info`가 정보를 못 얻는 프로세스(콘솔 없음)는 제외. external `cwd`는 PEB에서 best-effort(실패 시 빈 문자열).
- 서버 정상 종료 시 실행 중 host 세션 셸을 종료하고 history로 남긴다. 강제 종료/재부팅 후에는 시작 시 state=running인 meta를 exited(history)로 변환.
- attach CLI 분리 키 감지는 원시 `0x1d` 외에 win32-input-mode 시퀀스(`ESC[Vk;Sc;Uc;Kd;Cs;Rc_`, ConPTY가 `?9001h`를 요청하면 콘솔이 보냄)도 인식한다. 입력은 가공 없이 전달.

## 11. 자동 업데이트 (PC 호스트 + Android)
### 11.1 버전 단일 출처
- 저장소 루트 `version.properties`: `versionName=0.1.1-alpha`, `versionCode=2` (정수, 릴리스마다 +1).
- 호스트: csproj가 이 파일을 읽어 `InformationalVersion`=versionName 설정. `GET /api/info`의 `version`=versionName.
- Android: gradle이 읽어 `versionName`/`versionCode` 설정. git 태그 = `v<versionName>`.
- 비교: SemVer 2.0 (prerelease 포함, `0.1.1-alpha < 0.1.1 < 0.1.2-alpha`). 숫자 식별자는 숫자 비교.

### 11.2 업데이트 소스
- GitHub Releases: `GET https://api.github.com/repos/MrDH1004/PocketShell/releases?per_page=10` (prerelease 포함, draft 제외), 가장 높은 SemVer 태그 선택. 환경변수/설정 `PKSHELL_UPDATE_URL`(호스트), 앱 디버그 설정으로 URL 대체 가능(QA용 로컬 서버). User-Agent: `pocketshell-updater`.
- 자산 이름 규칙: `pocketshell-android-v<ver>.apk`, `pocketshell-host-win-x64-v<ver>.zip`.
- 무결성: 자산의 `digest`(`sha256:<hex>`) 필드와 다운로드 파일 SHA-256 일치 필수. digest 없으면 같은 릴리스의 `SHA256SUMS.txt`(`<hex>  <name>`)로 대체, 둘 다 없으면 업데이트 거부.

### 11.3 PC 호스트
- `serve` 시작 시 + 6시간마다 확인(설정 `autoUpdate`: `"auto"`(기본) | `"notify"` | `"off"`).
- 새 버전 발견 → `%PKSHELL_HOME%\updates\<ver>\`에 zip 다운로드·검증·압축 해제(staging).
- 적용 조건: 실행 중 host 세션 0개(셸이 ConPTY 자식이라 호스트 재시작 시 세션이 종료되기 때문). auto 모드는 유휴 상태가 되면 자동 적용, 세션이 있으면 대기 후 유휴 시 적용.
- `POST /api/update/apply {force?:bool}` (local 토큰 또는 기기 토큰): 세션이 있고 force가 아니면 409 `{error, runningSessions}`. `GET /api/update` → `{current, latest, state: "none"|"available"|"downloading"|"ready"|"applying"|"error", error?}`. `/api/info`에 `update` 동일 객체 포함.
- 적용 절차: 설치 폴더의 기존 파일을 `*.old`로 rename(실행 중 exe/dll도 rename 가능) → staging 파일 복사 → 같은 인자로 새 `psh serve` 실행 → 새 프로세스가 `/api/info` 응답하면 기존 프로세스 종료. 새 프로세스 시작 실패 시 `*.old` 복원 후 기존 프로세스 유지(롤백). 업데이트가 커밋된 뒤(기존 프로세스 종료 후)에만 `*.old` 삭제.
- CLI: `psh update [--check] [--force]`.
- 구현 확정 사항(host):
  - 설정: `config.json`의 `autoUpdate`, `updateUrl`(피드 URL, 환경변수 `PKSHELL_UPDATE_URL`이 우선), 확인 주기는 환경변수 `PKSHELL_UPDATE_INTERVAL_SEC`(기본 6시간, QA용).
  - 모드: `auto` = 발견 즉시 다운로드·검증 후 유휴 시 자동 적용, `notify` = `available`까지만(다운로드는 apply 시), `off` = 백그라운드 확인 없음(명시적 check/apply는 동작).
  - `POST /api/update/check` → 즉시 피드 확인 후 `GET /api/update`와 같은 객체. `POST /api/update/apply` 성공 = 200 + 같은 객체(적용은 비동기), 더 새 버전 없음 = 400. 피드는 호스트 자산(`pocketshell-host-win-x64-v<ver>.zip`)이 있는 릴리스 중 최고 버전을 고른다.
  - staging: `%PKSHELL_HOME%\updates\<ver>\app\` + 다운로드한 zip + `.ready`(검증된 sha256). `.ready` staging 재사용 시 보관된 zip을 다시 해시해 일치할 때만 그 zip에서 `app\`을 다시 풀어 사용(불일치·zip 없음 → 재다운로드). psh.exe 위치는 zip 루트 또는 단일 최상위 폴더(최초·재사용 동일 규칙). 현재 버전 이하의 staging은 시작 시 삭제.
  - 포트 인계: 기존 프로세스가 WS를 1012로 닫고 Kestrel·UDP 검색 소켓을 정지(포트 해제) → 세션 파일 핸들 해제 → 파일 교체(`*.old`) → 같은 인자로 새 `psh serve` 실행(환경변수 `PKSHELL_UPDATE_PARENT_PID`=기존 pid) → 새 프로세스는 바인드 실패 시 20초간 재시도, 기존 프로세스는 루프백 `/api/info`(지문 핀닝)가 **새 버전**을 보고하면 종료(30초 제한). 실패(새 프로세스 종료/시간 초과/버전 불일치) 시 새 프로세스 kill → `*.old` 복원 → 같은 프로세스에서 리스너 재개·세션(history) 재로드, state=`error`. 롤백된 버전은 같은 프로세스에서 자동 재적용하지 않는다(명시적 apply는 재시도).
  - 커밋 규칙: 업데이트는 기존 프로세스가 인계에 성공해 **종료한 시점에 커밋**된다. 그 전까지는 기존 프로세스가 롤백할 수 있으므로 업데이트로 실행된 새 프로세스(`PKSHELL_UPDATE_PARENT_PID` 설정)는 기존 pid가 종료하기 전에는 어떤 `*.old`도 지우지 않는다(시작 시 정리 생략, 기존 pid 종료 후에만 정리, 60초 내 종료하지 않으면 정리하지 않고 다음 시작에 맡김). 일반 시작(`PKSHELL_UPDATE_PARENT_PID` 없음)은 시작 시 정리.
  - `*.old` 정리 대상: 교체 시 설치 폴더에 기록하는 매니페스트 `pocketshell.update-files.txt`(교체한 상대 경로 목록, 자기 자신 포함, 다른 파일과 같은 방식으로 `*.old` 백업·롤백)에 있는 파일 `X` 중 `X`가 존재하는 `X.old`만 삭제. 매니페스트가 없으면 아무것도 지우지 않고, 목록에 없는 사용자 `*.old` 파일은 건드리지 않는다.
  - 롤백 방어: 백업이 있어야 할 파일의 `X.old`가 없으면 `X`를 삭제하지 않고(현재 파일 유지) 실패로 기록해 `error` 메시지(restore problems)에 포함.

### 11.4 Android
- 앱 시작 시(하루 1회 제한) + 세션 목록 메뉴 "업데이트 확인"에서 확인.
- 새 버전 → 다이얼로그(버전, 릴리스 노트) → 다운로드(앱 캐시) → SHA-256 검증 → FileProvider URI로 설치 인텐트(`REQUEST_INSTALL_PACKAGES`, 출처 허용 미설정 시 설정 화면 안내). 서명 키가 같아야 덮어쓰기 설치됨(릴리스는 동일 키로 서명).
- 세션 목록에 PC 호스트 업데이트 상태 표시(`/api/info.update`), "PC 업데이트 적용" 버튼(세션 실행 중이면 확인 후 force).

### 11.5 릴리스 스크립트
- `scripts/release.ps1 -Version <x.y.z[-pre]>`: version.properties 갱신(versionCode+1) → host publish(win-x64, framework-dependent) zip → APK 빌드(같은 서명 키) → SHA256SUMS.txt → 커밋·태그·푸시 → `gh release create`(prerelease 여부는 버전에 `-`가 있으면 true).

## 12. PIN 페어링 + LAN 검색
### 12.1 PIN 페어링
- `POST /api/pair/start {mode:"pin"}` (local 토큰) 또는 `psh pair` → `{pin:"123456", expiresAt, fpShort, hosts, port}`. PIN: 암호학적 난수 6자리, 5분 만료, 일회용. 동시에 유효한 PIN은 1개(새로 발급하면 이전 PIN 폐기).
- `fpShort`: 인증서 SHA-256 앞 8바이트를 `XXXX-XXXX-XXXX-XXXX`(대문자 hex) 형식으로 표시. PC 콘솔에 PIN과 함께 크게 출력.
- 폰: 호스트 주소(LAN 검색 결과 선택 또는 IP 직접 입력) + PIN 입력 → TLS 연결해 서버 인증서 지문을 받아(TOFU) `fpShort`를 화면에 표시하고 PC 화면과 같은지 사용자 확인 → `POST /api/pair/claim {pin, deviceName}` (인증 없음) → `{deviceId, token, fp}`. 이후에는 저장된 fp로 핀닝.
- 무차별 대입 방지: PIN 실패 5회면 해당 PIN 즉시 폐기(새로 발급해야 함). 원격 IP별 claim 실패 10회/10분 초과 시 429. 실패는 로그에 기록.
- 한계(문서화): 6자리 PIN은 능동 MITM에 대해 PAKE 수준의 보호를 주지 못함 → fpShort 육안 비교로 보완, LAN/Tailscale 전용.
- code 모드(2절, 로컬 도구·QA 전용)는 유지. claim body는 `code` 또는 `pin` 중 하나.
- 구현 확정 사항(host): claim 응답은 code/pin 모두 `{deviceId, token, fp}`. IP별 실패 한도(10회/10분 → 429)는 code·pin claim 실패를 합산하며, 한도 초과 중에는 올바른 PIN/code도 429. 틀린 PIN·만료·폐기 = 401. `pair/start` 본문 생략 또는 `mode`≠`"pin"`이면 code 모드 응답 `{code, expiresAt}`.

### 12.2 LAN 검색
- 호스트가 UDP 7381(설정 가능)에서 대기, 사설 IPv4 인터페이스에만 바인딩. 요청 데이터그램 `PKSHELL?1` → 응답 JSON `{"v":1,"name","hosts":[...],"port":7380,"fpShort"}` (토큰·민감정보 없음).
- 구현 확정 사항(host): 바인딩 대상 = 서버 bind 목록 중 사설 IPv4(10/8, 172.16/12, 192.168/16) + 127.0.0.1(로컬 도구용, 외부 노출 없음). Tailscale CGNAT·0.0.0.0에는 바인딩하지 않음. 포트: `--discovery-port N` > `config.json` `discoveryPort`(기본 7381), 0이면 끔. 바인드 실패는 경고만(서버는 계속).
- 폰: 각 Wi-Fi 브로드캐스트 주소(및 255.255.255.255)로 전송, 1.5초간 응답 수집해 목록 표시. Tailscale은 IP 직접 입력.

## 13. 트레이 앱 (PocketShell.exe)
- `host/src/PocketShell.Tray` (WinForms, WinExe). 설치 폴더에 `psh.exe`와 함께 배포(릴리스 zip에 둘 다 포함).
- 실행 시 `psh serve`를 창 없이(CreateNoWindow) 시작. 단일 인스턴스(`Local\PocketShell.Tray`).
- 실행 여부 = `psh serve`가 잡는 이름 있는 뮤텍스 `Local\PocketShell.Serve.{port}` 존재 여부(CLI·업데이트 핸드오프로 뜬 서버도 인식).
- 중지 = 이벤트 `Local\PocketShell.Stop.{port}` 신호 → 서버가 Kestrel을 정상 종료(세션 정리) 후 종료.
- 감시: 사용자가 중지하지 않았는데 서버가 없으면 약 4초 뒤 재시작, 5분에 3회 넘으면 중단하고 풍선 알림.
- 메뉴: 상태, 휴대폰 연결 (PIN)…(`POST /api/pair/start {mode:pin}`을 local.token + cert.sha256 핀닝으로 호출해 PIN·지문·주소·만료 카운트다운 표시), 서버 시작/중지, 데이터·로그 폴더, Windows 시작 시 자동 실행(HKCU Run `PocketShell`, 첫 실행 때 켬), 종료(서버도 중지).
- 로그: `%APPDATA%\PocketShell\logs\tray.log`.
