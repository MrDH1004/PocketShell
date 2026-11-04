# MyPCToAndroid (mpta) — 설계/계약 문서 (SSOT)

사양: `.omc/specs/deep-interview-pc-terminal-to-android.md` (AC-1~22). 이 문서는 구현 계약이다. 컴포넌트 간 인터페이스를 바꾸면 이 문서를 먼저 수정한다.

## 0. 저장소 구조
```
host/                      .NET 8 솔루션 (Mpta.sln)
  src/Mpta.Core/           세션/ConPTY/스크롤백/크기정책/인증/외부콘솔/프로토콜 (class lib, net8.0-windows)
  src/Mpta/                단일 exe `mpta.exe` (serve | list | new | attach | pair | devices | revoke | console-reader | console-info)
  tests/Mpta.Tests/        xUnit 단위 테스트
qa/Mpta.QA/                E2E QA 드라이버 (.NET 8 console) — 호스트를 실제로 띄워 AC 검증, 리포트 출력
android/                   Kotlin Android 앱 (Gradle, AGP), 모듈: app, terminal-emulator, terminal-view (Termux에서 vendoring, Apache-2.0)
docs/
```

## 1. 호스트 런타임
- 언어: C# / .NET 8 (`net8.0-windows`), ASP.NET Core Kestrel (minimal API + WebSockets). 패키지: `QRCoder`.
- 데이터 디렉터리: `MPTA_HOME` 환경변수, 없으면 `%APPDATA%\mpta`. 구조:
  - `config.json` `{ "port": 7380 }`
  - `cert.pfx` 자체서명 인증서(최초 실행 시 생성, RSA 2048, CN=mpta, 10년). SHA-256 지문(DER, 소문자 hex, 구분자 없음)을 `cert.sha256`에 기록.
  - `local.token` 로컬 CLI용 토큰(최초 생성, 32바이트 base64url).
  - `devices.json` `[{id, name, tokenSha256, createdAt, revoked}]` (토큰 원문은 저장하지 않음)
  - `sessions/<id>/meta.json`, `sessions/<id>/output.vt` (원본 출력 바이트 append)
- 포트: 기본 7380, `--port` 또는 config로 변경. **HTTPS/WSS만** 제공.
- 바인딩: `127.0.0.1` + 사설 IPv4(10/8, 172.16/12, 192.168/16) + Tailscale CGNAT(100.64.0.0/10) 인터페이스 주소. `0.0.0.0` 바인딩 금지. `--bind <ip>` 반복 지정 시 그것만 사용(QA용).
- `mpta serve` 는 포그라운드 실행(콘솔). 로그는 stdout + `logs/host.log`.

## 2. 인증
- 모든 `/api/*`(pair/claim 제외)와 `/ws/*`는 `Authorization: Bearer <token>` 필요. WebSocket은 헤더 대신 `?token=` 쿼리도 허용(하위 호환; Android 앱은 헤더만 사용 — URL에 토큰을 싣지 않음).
- 토큰 종류: (a) local.token — **루프백(127.0.0.1) 접속에서만** 유효, (b) 기기 토큰 — devices.json에 해시가 있고 revoked=false.
- 실패 시 HTTP 401. revoke 즉시 해당 기기의 열린 WS 연결도 종료(close code 4401).
- 페어링: `POST /api/pair/start` (local 토큰 필요) → `{code, expiresAt, qrPayload, qrAscii, qrPngPath}`. code: 일회용, 32바이트 base64url, 5분 만료.
  - qrPayload(JSON 문자열): `{"v":1,"name":"<PC 이름>","hosts":["192.168.x.x","100.x.x.x"],"port":7380,"fp":"<sha256hex>","code":"<code>"}`
  - `POST /api/pair/claim` (인증 없음) body `{code, deviceName}` → `{deviceId, token}`. 코드 재사용·만료 시 401.
- `GET /api/devices`, `DELETE /api/devices/{id}` (revoke) — local 토큰만 허용.
- 클라이언트(Android/CLI)는 서버 인증서를 **fp 지문으로 핀닝**(CA 검증 대신 지문 일치 검사).

## 3. 세션 모델
```
Session { id: string(12 hex), kind: "host"|"external"|"history",
          title, shell: "cmd"|"pwsh"|"powershell"|"bash"|"wsl"|<exe>, cwd,
          state: "running"|"exited", createdAt, lastActivity (ISO8601 UTC),
          cols, rows, clients: [{id, type:"android"|"cli"|"qa", cols, rows}],
          pid (external), resumedFrom (nullable) }
```
- `host`: 호스트가 ConPTY(`CreatePseudoConsole`)로 띄운 세션. 클라이언트가 0명이어도 셸 종료 전까지 유지(AC-1).
- `history`: state=exited인 host 세션. 목록에 `kind:"history"`로 나옴. meta는 디스크에 남아 재부팅 후에도 유지(AC-4/11). 최근 50개 보관.
- `external`: 앱 밖 콘솔. id = `ext-<pid>`.
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
| DELETE | `/api/sessions/{id}` | running이면 kill, history면 삭제 |
| POST | `/api/external/{pid}/adopt` | 외부 콘솔의 cwd(가능하면)·셸 종류로 새 host 세션 생성 → Session (AC-14). cwd를 못 얻으면 사용자 홈 |
| POST | `/api/pair/start` | 2절 |
| POST | `/api/pair/claim` | 2절 |
| GET/DELETE | `/api/devices[/id]` | 2절 |

## 5. WebSocket 스트림
`wss://<host>:7380/ws/sessions/{id}?client=android|cli|qa&cols=N&rows=N[&openPcTab=0|1]`
- 서버→클라: **binary** = 원본 출력 바이트. **text** = JSON 제어: `{"type":"size","cols","rows"}`, `{"type":"exit","code"}`, `{"type":"hello","session":Session}`(연결 직후, 재생보다 먼저), `{"type":"replay-end"}`(재생 완료 표시).
- 클라→서버: **binary** = 입력 바이트(UTF-8/VT 그대로 PTY에 write). **text** = `{"type":"resize","cols","rows"}`.
- `cols`/`rows`(쿼리, resize 메시지, `POST /api/sessions` 본문)는 서버에서 1..1000으로 clamp. 쿼리의 0/누락은 "크기 미보고".
- 클라→서버 메시지 1개는 최대 1 MB. 초과 시 서버가 close code **1009**(message too big)로 종료.
- 서버 측 클라이언트별 출력 큐는 4096 청크로 제한. 소비가 밀려 큐가 넘치면 해당 소켓만 close code **4408**("slow consumer")로 종료 — 클라이언트는 재연결하여 replay로 복구(다른 클라이언트는 영향 없음).
- close code 요약: **4401** = 기기 revoke(재연결 중단), **4408** = slow consumer(재연결), **1009** = 메시지 과대, 1000 = 정상 종료.
- external 세션(`ext-<pid>`): 서버가 콘솔 버퍼를 ~200ms마다 폴링, 변경 시 **VT 프레임**(`\x1b[?25l\x1b[H` + 각 행 SGR 16색 + `\x1b[K` + 커서 위치 `\x1b[r;cH` + `\x1b[?25h`)으로 binary 전송. 클라이언트 입력 바이트는 KEY_EVENT로 변환해 `WriteConsoleInputW`(AC-13). 크기 정책 미적용.
- PC 탭 자동 열기(AC-21): `client=android` 연결이고 `openPcTab`≠0이며 해당 세션에 `cli` 클라이언트가 없고 진행 중인 열기 요청도 없으면 → `wt.exe -w 0 nt --title "<title>" "<mpta.exe 경로>" attach <id> --port <서버포트> [--home <MPTA_HOME>]` 실행(`--home`은 서버에 MPTA_HOME이 설정된 경우만; WT 탭은 서버 환경변수를 상속하지 않으므로 필요). 15초 내 cli 클라이언트가 붙지 않으면 pending 해제. 포커스를 빼앗지 않도록 실행 전후 foreground 창을 기록하고 바뀌었으면 원래 창으로 복귀 시도. host 세션에만 적용.

## 6. 외부 콘솔(보조 모드)
- 열거: 프로세스 이름 `cmd.exe|powershell.exe|pwsh.exe` 중 부모가 위 셸이 아니고(최상위 셸), 호스트 세션의 자손이 아니며, mpta 자신이 아닌 것.
- 콘솔 API 호출은 **헬퍼 자식 프로세스**에서: `mpta console-info <pid...>` → stdout JSON `[{pid,title,cols,rows}]`; `mpta console-reader <pid>` → `FreeConsole`→`AttachConsole(pid)`→`CONOUT$`/`CONIN$` 열고, stdout(파이프)에 길이접두 프레임(4바이트 LE 길이 + VT 프레임 바이트) 출력, stdin(파이프)으로 입력 바이트 수신(4바이트 LE 길이 + 바이트).
- 색 변환: Windows 속성 FG(B=1,G=2,R=4,I=8) → ANSI 인덱스 `(R?1:0)|(G?2:0)|(B?4:0)`, I면 90번대 아니면 30번대; BG 동일(40/100번대). `COMMON_LVB_UNDERSCORE`→`4`, `COMMON_LVB_REVERSE_VIDEO`→`7`.
- 입력 변환: 출력 가능 문자→유니코드 KEY_EVENT(down+up); `\r`→VK_RETURN; `\x7f`/`\b`→VK_BACK; `\t`→VK_TAB; 단독 `\x1b`→VK_ESCAPE; `\x1b[A/B/C/D`→화살표; `\x1b[H/F`→Home/End; `\x1b[5~/6~`→PgUp/PgDn; `\x1b[3~`→Delete; 0x01~0x1a→Ctrl+문자(VK 'A'+n-1, LEFT_CTRL_PRESSED, uChar=제어문자).

## 7. CLI (`mpta.exe`)
- `mpta serve [--port N] [--bind ip]...`
- `mpta list` 세션 표(종류/ID/제목/셸/폴더/상태/최근활동)
- `mpta new [--shell pwsh] [--cwd D] [--title T] [--attach]` (기본: 생성 후 현재 콘솔에서 attach)
- `mpta attach <id>` 현재 콘솔을 VT 입력/출력 모드로 전환(`ENABLE_VIRTUAL_TERMINAL_PROCESSING`, `ENABLE_VIRTUAL_TERMINAL_INPUT`, raw 입력), 콘솔 크기 보고(창 크기 변경 시 resize 전송), 출력 바이트 그대로 stdout에 쓰기. `client=cli`. 분리 단축키: `Ctrl+]` 후 `d`. 세션 exit 시 메시지 출력 후 종료.
- `mpta pair` → pair/start 호출, 콘솔에 ASCII QR + payload 출력, PNG 열기.
- `mpta devices`, `mpta revoke <id>`
- CLI는 `local.token`과 `cert.sha256`을 읽어 `wss://127.0.0.1:<port>`로 접속(핀닝).
- 클라이언트 verb 공통 옵션: `--port N`(우선순위: `--port` > 환경변수 `MPTA_PORT` > config.json port), `--home DIR`(> `MPTA_HOME` > `%APPDATA%\mpta`). `mpta serve --port N`은 config.json을 바꾸지 않는다.
- `mpta new --no-attach`: 생성 후 세션 id만 출력.

## 8. Android 앱
- Kotlin, minSdk 26, targetSdk/compileSdk 35, 패키지 `com.mpta.client`.
- 터미널: Termux `terminal-emulator`/`terminal-view` 소스 vendoring(Apache-2.0, JNI pty 코드 제거). `TerminalSession`을 원격 백엔드(`RemoteTerminalSession`)로 대체: 입력 → WS binary, WS binary → `emulator.append()` + 화면 갱신. truecolor, CJK 폭은 Termux 엔진 그대로.
- 네트워크: OkHttp WebSocket + 지문 핀닝 TrustManager. hosts 목록을 순서대로 시도(LAN → Tailscale).
- 화면: (1) 페어링 — QR 스캔(CameraX + ML Kit barcode) 또는 payload 붙여넣기, (2) 세션 목록 — 실행 중 / 외부 / 최근 섹션, 새 세션(셸 선택), 당겨서 새로고침, (3) 터미널 — 특수키 줄(ESC, CTRL(토글), ALT(토글), TAB, ←↑↓→, HOME, END, PGUP, PGDN, `|`, `~`, `/`, `-`), 외부 세션이면 "호스트로 인수" 버튼, history면 열 때 resume 호출.
- 재연결(AC-18): 연결 끊김 시 지수 백오프(1,2,4..최대 10초) 재연결, `onResume`·네트워크 변경 시 즉시 재연결. 재연결 시 emulator reset 후 서버 재생을 받음.
- 한글 IME(AC-17): `TerminalView.onCreateInputConnection`에서 `commitText`/조합 텍스트를 UTF-8로 전송, 조합 중 텍스트는 확정 시 전송.
- 크기: TerminalView의 cols/rows를 연결 쿼리와 `resize` 메시지로 보고.

## 9. QA 드라이버 (`qa/Mpta.QA`)
- 임시 `MPTA_HOME` + 테스트 포트(예: 7391)로 `mpta serve`를 자식 프로세스로 실행, 시나리오별 AC 체크, 결과를 `qa/out/report.md` + 콘솔 표(PASS/FAIL/SKIP + 증거)로 출력. 실패 시 exit code 1.
- 시나리오는 AC 번호별 함수. Android 렌더링은 `android` 모듈 JVM 단위테스트(`gradlew test`)가 담당하고, QA 드라이버는 adb 기기가 연결돼 있으면 APK 설치·실행 스모크까지 수행.

## 10. 호스트 구현 확정 사항 (계약 보충, host 구현 기준)
- 응답 코드: 성공 GET/POST = 200 + JSON(`POST /api/sessions`, resume, adopt 포함). `DELETE /api/sessions/{id}`, `DELETE /api/devices/{id}` = 204(본문 없음). 없음 = 404, history가 아닌 세션 resume = 409, 셸 실행 실패 등 = 400. 오류 본문 `{"error":"..."}`.
- local 토큰 전용 엔드포인트(`pair/start`, `devices`)에 기기 토큰으로 접근하면 401(403 아님).
- `GET /api/devices` → `{"devices":[{id,name,createdAt,revoked}]}` (tokenSha256은 노출하지 않음). revoke는 항목을 삭제하지 않고 `revoked:true`로 표시.
- `POST /api/sessions` 본문은 생략 가능. `shell:"pwsh"`인데 pwsh.exe가 PATH에 없으면 powershell.exe로 대체하고 Session.shell은 실제 값 `"powershell"`. title 기본값 = shell, cols/rows 기본 120x30.
- WS(host 세션) 순서: `hello` → 재생 binary 프레임(64KB 단위) → `replay-end` → `{"type":"size"}`(현재 PTY 크기, 항상) → 실시간. exited/history 세션에 붙으면 재생 후 `exit`이 온다. `client` 값이 android/cli/qa가 아니면 qa로 취급. cols/rows 미지정(0) 클라이언트는 크기 계산에서 제외.
- WS(external): `hello` → 마지막 프레임(있으면) → `replay-end` → 변경 시 프레임. 헬퍼 종료 시 `{"type":"exit","code":N}`.
- 외부 콘솔 목록은 2초 캐시. `console-info`가 정보를 못 얻는 프로세스(콘솔 없음)는 제외. external `cwd`는 PEB에서 best-effort(실패 시 빈 문자열).
- 페어링 PNG 경로: `<MPTA_HOME>/pair-qr.png`.
- 서버 정상 종료 시 실행 중 host 세션 셸을 종료하고 history로 남긴다. 강제 종료/재부팅 후에는 시작 시 state=running인 meta를 exited(history)로 변환.
- attach CLI 분리 키 감지는 원시 `0x1d` 외에 win32-input-mode 시퀀스(`ESC[Vk;Sc;Uc;Kd;Cs;Rc_`, ConPTY가 `?9001h`를 요청하면 콘솔이 보냄)도 인식한다. 입력은 가공 없이 전달.

## 11. 자동 업데이트 (PC 호스트 + Android)
### 11.1 버전 단일 출처
- 저장소 루트 `version.properties`: `versionName=0.1.1-alpha`, `versionCode=2` (정수, 릴리스마다 +1).
- 호스트: csproj가 이 파일을 읽어 `InformationalVersion`=versionName 설정. `GET /api/info`의 `version`=versionName.
- Android: gradle이 읽어 `versionName`/`versionCode` 설정. git 태그 = `v<versionName>`.
- 비교: SemVer 2.0 (prerelease 포함, `0.1.1-alpha < 0.1.1 < 0.1.2-alpha`). 숫자 식별자는 숫자 비교.

### 11.2 업데이트 소스
- GitHub Releases: `GET https://api.github.com/repos/MrDH1004/MyPCToAndroid/releases?per_page=10` (prerelease 포함, draft 제외), 가장 높은 SemVer 태그 선택. 환경변수/설정 `MPTA_UPDATE_URL`(호스트), 앱 디버그 설정으로 URL 대체 가능(QA용 로컬 서버). User-Agent: `mpta-updater`.
- 자산 이름 규칙: `mpta-android-v<ver>.apk`, `mpta-host-win-x64-v<ver>.zip`.
- 무결성: 자산의 `digest`(`sha256:<hex>`) 필드와 다운로드 파일 SHA-256 일치 필수. digest 없으면 같은 릴리스의 `SHA256SUMS.txt`(`<hex>  <name>`)로 대체, 둘 다 없으면 업데이트 거부.

### 11.3 PC 호스트
- `serve` 시작 시 + 6시간마다 확인(설정 `autoUpdate`: `"auto"`(기본) | `"notify"` | `"off"`).
- 새 버전 발견 → `%MPTA_HOME%\updates\<ver>\`에 zip 다운로드·검증·압축 해제(staging).
- 적용 조건: 실행 중 host 세션 0개(셸이 ConPTY 자식이라 호스트 재시작 시 세션이 종료되기 때문). auto 모드는 유휴 상태가 되면 자동 적용, 세션이 있으면 대기 후 유휴 시 적용.
- `POST /api/update/apply {force?:bool}` (local 토큰 또는 기기 토큰): 세션이 있고 force가 아니면 409 `{error, runningSessions}`. `GET /api/update` → `{current, latest, state: "none"|"available"|"downloading"|"ready"|"applying"|"error", error?}`. `/api/info`에 `update` 동일 객체 포함.
- 적용 절차: 설치 폴더의 기존 파일을 `*.old`로 rename(실행 중 exe/dll도 rename 가능) → staging 파일 복사 → 같은 인자로 새 `mpta serve` 실행 → 새 프로세스가 `/api/info` 응답하면 기존 프로세스 종료. 새 프로세스 시작 실패 시 `*.old` 복원 후 기존 프로세스 유지(롤백). 다음 시작 시 `*.old` 삭제 시도.
- CLI: `mpta update [--check] [--force]`.

### 11.4 Android
- 앱 시작 시(하루 1회 제한) + 세션 목록 메뉴 "업데이트 확인"에서 확인.
- 새 버전 → 다이얼로그(버전, 릴리스 노트) → 다운로드(앱 캐시) → SHA-256 검증 → FileProvider URI로 설치 인텐트(`REQUEST_INSTALL_PACKAGES`, 출처 허용 미설정 시 설정 화면 안내). 서명 키가 같아야 덮어쓰기 설치됨(릴리스는 동일 키로 서명).
- 세션 목록에 PC 호스트 업데이트 상태 표시(`/api/info.update`), "PC 업데이트 적용" 버튼(세션 실행 중이면 확인 후 force).

### 11.5 릴리스 스크립트
- `scripts/release.ps1 -Version <x.y.z[-pre]>`: version.properties 갱신(versionCode+1) → host publish(win-x64, framework-dependent) zip → APK 빌드(같은 서명 키) → SHA256SUMS.txt → 커밋·태그·푸시 → `gh release create`(prerelease 여부는 버전에 `-`가 있으면 true).

## 12. PIN 페어링 + LAN 검색
### 12.1 PIN 페어링 (QR 대안)
- `POST /api/pair/start {mode:"pin"}` (local 토큰) 또는 `mpta pair --pin` → `{pin:"123456", expiresAt, fpShort, hosts, port}`. PIN: 암호학적 난수 6자리, 5분 만료, 일회용. 동시에 유효한 PIN은 1개(새로 발급하면 이전 PIN 폐기).
- `fpShort`: 인증서 SHA-256 앞 8바이트를 `XXXX-XXXX-XXXX-XXXX`(대문자 hex) 형식으로 표시. PC 콘솔에 PIN과 함께 크게 출력.
- 폰: 호스트 주소(LAN 검색 결과 선택 또는 IP 직접 입력) + PIN 입력 → TLS 연결해 서버 인증서 지문을 받아(TOFU) `fpShort`를 화면에 표시하고 PC 화면과 같은지 사용자 확인 → `POST /api/pair/claim {pin, deviceName}` (인증 없음) → `{deviceId, token, fp}`. 이후에는 저장된 fp로 핀닝(QR 경로와 동일).
- 무차별 대입 방지: PIN 실패 5회면 해당 PIN 즉시 폐기(새로 발급해야 함). 원격 IP별 claim 실패 10회/10분 초과 시 429. 실패는 로그에 기록.
- 한계(문서화): 6자리 PIN은 능동 MITM에 대해 PAKE 수준의 보호를 주지 못함 → fpShort 육안 비교로 보완, LAN/Tailscale 전용.
- QR 경로(`code`)는 그대로 유지. claim body는 `code` 또는 `pin` 중 하나.

### 12.2 LAN 검색
- 호스트가 UDP 7381(설정 가능)에서 대기, 사설 IPv4 인터페이스에만 바인딩. 요청 데이터그램 `MPTA?1` → 응답 JSON `{"v":1,"name","hosts":[...],"port":7380,"fpShort"}` (토큰·민감정보 없음).
- 폰: 각 Wi-Fi 브로드캐스트 주소(및 255.255.255.255)로 전송, 1.5초간 응답 수집해 목록 표시. Tailscale은 IP 직접 입력.
