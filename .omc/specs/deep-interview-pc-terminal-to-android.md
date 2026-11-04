# Deep Interview Spec: PC 터미널 ↔ Android 미러링 앱 (MyPCToAndroid)

## Metadata
- Interview ID: di-mypc2android-20261104
- Rounds: 9 (+ Round 0 topology, 캡처 방식 결정 1회)
- Final Ambiguity Score: 18.3%
- Type: greenfield
- Generated: 2026-11-04
- Threshold: 0.2
- Threshold Source: default
- Initial Context Summarized: no
- Status: PASSED (pending approval)

## Clarity Breakdown
| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Goal Clarity | 0.88 | 0.40 | 0.352 |
| Constraint Clarity | 0.80 | 0.30 | 0.240 |
| Success Criteria | 0.75 | 0.30 | 0.225 |
| **Total Clarity** | | | **0.817** |
| **Ambiguity** | | | **0.183** |

## Topology
| Component | Status | Description | Coverage |
|-----------|--------|-------------|----------|
| PC 호스트 | active | ConPTY 세션 생성·소유(기본) + 외부 콘솔 버퍼 파싱 부착(보조) | AC-1~4, AC-12~14 |
| 전송/동기화 | active | PC↔폰 실시간 스트림, 다중 클라이언트, 인증 | AC-5~8 |
| 세션 목록/히스토리 | active | 실행 중 + 외부 콘솔 + 종료된 최근 세션 | AC-9~11 |
| 폰 클라이언트(Android) | active | 완전 네이티브 렌더러, 특수키, 한글 IME | AC-15~19 |
| PC 측 뷰 | active | Windows Terminal 탭에서 attach CLI 실행 | AC-20~22 |

## Goal
PC에 상주하는 호스트가 터미널 세션(ConPTY)을 소유하고, Android 네이티브 앱과 PC의 Windows Terminal 탭이 **같은 세션에 동시에 붙어** 원본 VT 스트림으로 색상(24bit)·TUI까지 동일하게 보고 양쪽에서 입력한다. 폰에서는 실행 중 세션·외부 콘솔·종료된 최근 세션 목록에서 골라 이어서 작업하며, 폰에서 연 세션은 PC에도 WT 탭으로 열려 있다.

## 핵심 설계 결정 — 캡처/표시 방식
- **채택: C (스트림 기본 + 버퍼 파싱 보조)**
  - 기본: 호스트가 ConPTY로 띄운 셸의 **원본 VT/ANSI 바이트 스트림을 가공 없이 전달**, 각 클라이언트의 터미널 에뮬레이터가 파싱·렌더링. 서버 측 HTML/문자열 변환 금지.
  - 보조: 앱 밖에서 이미 떠 있는 콘솔은 `AttachConsole` + `ReadConsoleOutput`(문자+속성) 폴링, 입력은 `WriteConsoleInput`. 16색 근사 허용.
- 기각: 문자열 추출(색상·TUI 손실), 서버 측 파싱→HTML(직접 구현한 에뮬레이터 버그 위험).

## Constraints
- PC: Windows 11, ConPTY(`CreatePseudoConsole`) 사용.
- 네트워크: 같은 Wi-Fi(LAN) + 외부망은 Tailscale 경유. 앱은 포트포워딩·중계서버를 포함하지 않음. 호스트는 LAN/Tailscale 인터페이스에만 바인딩.
- 인증: 최초 1회 PC에 표시되는 QR을 스캔해 기기 토큰 발급, 이후 자동 접속. 통신 TLS. 등록 기기는 PC에서 취소 가능.
- 크기 정책: 동시에 붙은 클라이언트 중 **가장 작은 cols×rows**로 PTY 크기 설정(클라이언트 접속·해제·리사이즈 때 재계산). 큰 쪽은 여백 표시.
- 히스토리: 종료된 세션의 원본 VT 스크롤백을 디스크에 저장. 재개 시 저장된 출력을 재생한 뒤 같은 작업폴더·같은 셸 종류·같은 제목으로 새 셸을 이어 붙임(죽은 프로세스 복구는 불가).
- PC 측 표시: Windows Terminal 탭에서 호스트 attach CLI(`<host> attach <id>`) 실행. 폰에서 세션을 열거나 만들 때 해당 세션의 WT 탭이 없을 때만 `wt -w 0 nt ...`로 자동 생성, 포커스는 이동하지 않음.
- 폰: 완전 네이티브 렌더러(WebView 미사용), APK 사이드로드. 권장: Termux `terminal-emulator`/`terminal-view`(Apache-2.0) 재사용 검토.
- 호스트 기술 스택: **계획 단계에서 확정**(후보: .NET 8 / Go / Rust — ConPTY·Win32 콘솔 API 접근성 기준).

## Non-Goals
- 서버 측 VT→HTML/문자열 변환.
- 인터넷 직접 노출, 자체 중계서버, Play 스토어 배포.
- 죽은 프로세스의 상태(메모리·실행 중이던 프로그램) 복원.
- 외부 콘솔 모드에서의 24bit 색상·TUI 완전 지원.
- PC 전용 GUI 터미널 창(WPF/WinUI) 구현.
- iOS 클라이언트.

## Acceptance Criteria
### PC 호스트
- [ ] AC-1 호스트가 cmd/pwsh/bash(WSL·Git Bash) 세션을 ConPTY로 생성하고, 모든 클라이언트가 분리돼도 세션이 유지된다.
- [ ] AC-2 세션 출력 원본 바이트가 수정 없이 모든 클라이언트에 브로드캐스트된다(바이트 단위 비교 테스트).
- [ ] AC-3 신규 클라이언트 접속 시 현재 화면 상태가 즉시 재현된다(스크롤백 재생 또는 스냅샷).
- [ ] AC-4 스크롤백이 세션별로 디스크에 저장되고 PC 재부팅 후에도 남는다.
### 전송/동기화
- [ ] AC-5 PC와 폰이 동시에 같은 세션에 붙어 양쪽 입력이 모두 반영된다.
- [ ] AC-6 PTY 크기 = 접속 중 클라이언트의 최소 cols×rows, 접속·해제·회전 시 갱신된다.
- [ ] AC-7 미등록 기기·취소된 토큰은 접속이 거부된다. QR 페어링 1회 후 자동 접속된다.
- [ ] AC-8 LAN 및 Tailscale IP 양쪽에서 접속되고 TLS로 암호화된다. 입력→에코 지연 LAN 기준 체감 100ms 이하(목표값, 계획 단계에서 측정 기준 확정).
### 세션 목록/히스토리
- [ ] AC-9 목록에 실행 중 호스트 세션 / 외부 콘솔(‘외부’ 표시) / 종료된 최근 세션이 구분 표시된다(제목·셸·작업폴더·최근 활동시각).
- [ ] AC-10 종료된 세션 선택 시 저장된 출력이 색상 그대로 재생되고 같은 폴더·셸로 새 셸이 이어 붙는다.
- [ ] AC-11 PC 재부팅 후에도 히스토리 목록과 출력 복원이 동작한다.
### PC 호스트(보조 모드 — 외부 콘솔)
- [ ] AC-12 앱 밖에서 실행 중인 cmd/pwsh 콘솔(독립 conhost 및 WT 탭)이 목록에 나타난다.
- [ ] AC-13 폰에서 외부 콘솔 화면이 16색 근사로 ~200ms 주기 갱신되고 키 입력이 주입된다.
- [ ] AC-14 ‘호스트로 인수’ 실행 시 같은 작업폴더에 호스트 세션이 생성되어 완전 모드로 전환된다.
### 폰 클라이언트
- [ ] AC-15 Claude Code·vim 같은 전체화면 TUI가 깨지지 않고, Esc/Ctrl/Tab/방향키/Ctrl+C가 특수키 줄로 동작한다.
- [ ] AC-16 24bit 트루컬러 그라데이션 테스트 출력이 PC WT와 같은 RGB로 렌더링된다.
- [ ] AC-17 한글 출력의 문자 폭(2칸)이 정확하고 폰 IME로 한글 입력이 된다.
- [ ] AC-18 화면이 꺼졌다 켜지거나 네트워크가 바뀌면 자동으로 재연결하고 화면이 복원된다.
- [ ] AC-19 QR 스캔 페어링, 세션 목록, 새 세션 생성(셸 선택) UI가 있다.
### PC 측 뷰
- [ ] AC-20 `<host> attach <id>`를 WT 탭에서 실행하면 폰과 같은 세션이 같은 색상으로 보이고 입력된다.
- [ ] AC-21 폰에서 세션을 열거나 만들 때 PC에 해당 세션 WT 탭이 없으면 자동 생성되고, 있으면 중복 생성하지 않으며 포커스를 빼앗지 않는다.
- [ ] AC-22 PC에서 세션 목록 조회·새 세션 생성(CLI 또는 트레이) 가능하다.

## Assumptions Exposed & Resolved
| Assumption | Challenge | Resolution |
|------------|-----------|------------|
| 이미 떠 있는 아무 터미널에나 붙어 색상 그대로 미러링 가능 | Windows는 외부 콘솔의 VT 스트림 가로채기 불가 | C: 호스트 소유 세션은 완전, 외부 콘솔은 버퍼 파싱 16색 보조 + 인수 기능 |
| 파싱 vs 스트링 중 택1 | 둘 다 결함 | 원본 스트림 전달 + 클라이언트 에뮬레이터가 파싱 |
| 종료된 세션을 "다시 연다" | 죽은 프로세스는 복구 불가 | 출력 재생 + 같은 폴더/셸로 새 셸 |
| 폰에서 열면 PC에도 열려야 함 | 탭 누적 부작용 | 없을 때만 자동 WT 탭, 포커스 유지 |
| 두 기기가 하나의 화면 크기를 공유 | PTY는 크기 1개 | 최소 크기 맞춤 |
| 앱이면 WebView로 충분 | Simplifier 검토 | 사용자 선택: 완전 네이티브 렌더러 |

## Technical Context (greenfield)
- 호스트: Windows 서비스/트레이 상주 프로세스, ConPTY, WebSocket(TLS) 서버, QR 페어링, 스크롤백 저장소, 외부 콘솔 열거(콘솔 프로세스 탐색 → AttachConsole → ReadConsoleOutputW 폴링), attach CLI 포함.
- PC attach CLI: 자신의 콘솔을 raw/VT 모드로 전환하고 호스트 스트림을 그대로 stdout으로, stdin을 그대로 호스트로 전달.
- Android: Kotlin, 네이티브 터미널 뷰(Termux 라이브러리 후보), OkHttp WebSocket, CameraX/ML Kit QR, 특수키 줄.
- 프로토콜(초안): 세션 목록/생성/attach/resize/input/output/detach 메시지, output은 바이너리 프레임.

## Ontology (Key Entities)
| Entity | Type | Fields | Relationships |
|--------|------|--------|---------------|
| Host | core | 바인딩 IP, 등록 기기 목록 | Host has many Sessions, Devices |
| Session | core | id, 셸, 작업폴더, 제목, 상태(running/exited), 크기 | Session has Scrollback, many Clients |
| ExternalConsole | supporting | pid, 창 제목, 버퍼 | 인수 시 Session 생성 |
| Scrollback | supporting | 원본 VT 바이트 로그 | belongs to Session |
| HistoryEntry | supporting | 종료 시각, 셸, 폴더 | 재개 시 새 Session + Scrollback 재생 |
| Client | core | 종류(Android/AttachCLI), cols×rows | attaches to Session |
| Device | supporting | 토큰, 이름, 취소 여부 | Client 인증 |
| AttachCLI | supporting | WT 탭 | Client 종류 |
| SpecialKeyBar | supporting | Esc/Ctrl/Tab/방향키 | Android Client UI |
| Tailscale망 | external | — | 외부 접속 경로 |

## Ontology Convergence
| Round | Entity Count | New | Changed | Stable | Stability |
|-------|-------------|-----|---------|--------|-----------|
| 1 | 7 | 7 | - | - | - |
| 2 | 8 | 1 | 0 | 7 | 88% |
| 3 | 9 | 1 | 0 | 8 | 89% |
| 4–6 | 9 | 0 | 0 | 9 | 100% |
| 7 | 10 | 1 | 0 | 9 | 90% |
| 8–9 | 10 | 0 | 0 | 10 | 100% |

## Interview Transcript
<details><summary>Q&A</summary>

- R0a 캡처 방식 → C (스트림 기본 + 버퍼 파싱 보조)
- R0b 구성요소 5개 → 확정
- R1 네트워크 → 같은 Wi-Fi + Tailscale (65.5%)
- R2 히스토리 재개 → 출력 복원 + 같은 폴더 새 셸 (55.7%)
- R3 PC 뷰 → Windows Terminal 탭 (49.2%)
- R4 PC 자동 열기 → 없을 때만 자동 탭, 포커스 유지 (45%)
- R5 크기 정책 → 작은 쪽에 맞춤 (41.4%)
- R6 폰 앱 → 완전 네이티브 렌더러 (37.6%)
- R7 완료 기준 → TUI, 24bit, 한글, 재연결·복원 전부 (26.9%)
- R8 인증 → QR 페어링 + 기기 토큰 (24.5%)
- R9 외부 콘솔 → 보기+입력+호스트로 인수 (18.3%)
</details>
