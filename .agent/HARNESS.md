# Undine Harness

Undine(Compose Desktop Git 클라이언트) 개발에 쓰는 AI 협업 자동화 패키지. **SSOT 는 `.agent/`** 이고,
`.claude/`·`.codex/` 는 벤더 CLI 가 탐색하는 **생성 투영본**이다 (`.agent/tools/sync-vendors.py`).
**git tracked** 자산만 포함 — **clone + 환경 변수 + project `.mcp.json` 첫 승인 1회**로 동일 동작.
(claude.ai connector — Slack/Gmail/Calendar/Drive/Figma/GitHub — 는 개인 OAuth 연결이 별도로 필요.)

## 디렉토리 구조

```
.agent/
├── HARNESS.md                       # 본 문서 — 진입점 + 진단 rubric (SSOT)
├── onboarding.md                    # 셋업 가이드
├── README.md                        # .agent 구조 + 벤더 투영 규칙
├── guidelines/
│   └── llm-collaboration.md         # 작업 원칙 (SessionStart 훅이 매 세션 주입)
├── hooks/                           # 표준 hooks (.sh chmod +x, 개수 SSOT: hooks/README.md)
│   ├── README.md
│   ├── custom-session-start.sh             # SessionStart: 브랜치/JDK/MCP env 안내 + 가이드라인 주입
│   ├── custom-gradlew-jvm-guard.sh         # PreToolUse Bash: ./gradlew 전 undine.jvm vs $JAVA_HOME
│   ├── custom-check-commit-prefix.sh       # PreToolUse Bash: [UND-NN] 접두사 + 제목 정밀 lint
│   ├── custom-block-git-push.sh            # PreToolUse Bash: git push 직전 사용자 승인 프롬프트(ask)
│   ├── custom-block-env-read.sh            # PreToolUse Bash: .env / 시크릿 echo 차단
│   ├── custom-secrets-read-guard.sh        # PreToolUse Read: .env/credentials/*.pem 차단
│   ├── custom-secrets-edit-guard.sh        # PreToolUse Edit/Write: 민감 파일 편집 가드
│   ├── custom-block-generated-edit.sh      # PreToolUse Edit/Write: 생성 투영 편집 차단
│   ├── custom-confirm-mcp-write.sh         # PreToolUse MCP: 외부 write 호출 직전 승인 프롬프트(ask)
│   ├── custom-detekt-touch-reminder.sh     # PostToolUse: .kt 변경 시 detekt 실행 안내
│   └── custom-detekt-async-run.sh          # PostToolUse(async): .kt 변경 시 detekt 자동 실행
├── agents/                          # specialized subagents (개수 SSOT: agents/README.md)
│   ├── README.md
│   ├── custom-kotlin-desktop-engineer.md   # Kotlin/Compose/JGit 작성 가이드 + 정적 리뷰 (2-mode, opus)
│   ├── custom-pr-call-graph-reviewer.md    # 1-hop 호출 그래프 + interface↔구현 비대칭
│   ├── custom-silent-failure-hunter.md     # 예외 삼킴·silent fallback·취소 미전파 (push 전 게이트)
│   ├── custom-design-doc-author.md         # 요구사항 → 설계 문서(TDD) 초안
│   ├── custom-ticket-decomposer.md         # 설계 → 티켓 분해 + 의존 DAG + wave 너비 검증
│   ├── custom-research-investigator.md     # [Analysis] 범위 한정 병렬 조사 → scope-*.md
│   ├── custom-analysis-synthesizer.md      # [Analysis] finding 종합 → 결론·권고 → analysis/ 아카이브
│   └── custom-retrospective-analyst.md     # [Analysis] 회고 → retrospectives/
├── skills/                          # slash command + auto-invoke (개수 SSOT: skills/README.md)
│   ├── README.md
│   ├── custom-develop-orchestrator/        # 티켓 1건 스펙→구현→검증 1-루프 오케스트레이터
│   ├── custom-orchestrate/                 # DAG 워크플로우 실행 (dry-run → 승인 → 실행)
│   ├── custom-self-code-review/            # push 전 5축 자가 점검 — **축 정의 SSOT**
│   ├── custom-affected-test-runner/        # 변경 범위 테스트 게이트
│   ├── custom-pr-create/                   # PR 템플릿 본문 + gh pr create (기본 Draft)
│   ├── custom-pr-review/                   # PR 로컬 다축 정적 리뷰 (worktree 격리 + 오탐 필터)
│   └── custom-release-tagger/              # semver 릴리즈 태그 + 노트 초안
├── rules/                           # 코드 작성 규칙 (개수 SSOT: rules/README.md)
│   ├── README.md                    # docs/conventions.md(프로세스 룰)와의 경계
│   ├── architecture-layers.md       # 레이어 경계·의존 방향·Gateway 배치·패키지 구조
│   ├── kotlin-idioms.md             # null·불변·sealed·스코프 함수·코루틴·로깅
│   ├── jgit-usage.md                # JGit 자원 수명(use {})·스레드·페이징·파괴적 연산
│   ├── compose-ui.md                # 상태 끌어올리기·리컴포지션·LazyColumn key·디자인 토큰
│   ├── credential-handling.md       # SSH 키·토큰 저장·로그 마스킹·호스트 키 검증
│   ├── exception-handling.md        # 도메인 예외 번역·실패 종류 구분·취소 전파
│   └── testing.md                   # Kotest 통일·실제 임시 저장소 강제·경계값
├── scripts/
│   ├── README.md
│   └── validate-harness.sh          # 카운트·cross-file 정합 검증 (수동 실행, drift 예방)
├── docs/
│   ├── README.md                    # docs 인덱스
│   ├── conventions.md               # 프로세스 게이트 4개 룰 (JDK·티켓 접두사·파일 소유·파괴적 변경)
│   ├── ssot-map.md                  # 어디서 무엇이 단일 진실 공급원인지
│   ├── review-grading.md            # [Review] 등급(p0~p5)·verdict 산출·fail-closed 정본
│   ├── review-false-positives.md    # [Review] 오탐 패턴 카탈로그 (FP-ID)
│   ├── review-discipline.md         # [Review] 리뷰 규율 정본 (게이트·출력·브리핑·체크리스트·HTML)
│   ├── analysis-workflow.md         # [Analysis] Step 1~7 + 검증 게이트 (절차 정본)
│   ├── collaboration-protocol.md    # [Analysis] job workspace·로그 형식·산출물 규약
│   └── job-lifecycle.md             # [Analysis] START~CLOSE 라이프사이클 + 아카이브 정책
├── templates/
│   ├── README.md
│   └── retrospective-report.template.md   # [Analysis] 회고 보고서 템플릿
├── orchestration/                   # 멀티 에이전트 워크플로우 (정본: orchestration/README.md)
│   ├── workflows/*.toml             # DAG (harness-audit · develop-1-spec · develop-2-implement
│   │                                #      · develop-3-repair · develop-3-approve)
│   ├── runner/run-graph.py          # DAG 실행기 (stdlib only). 게이트 노드 · --start-at 재개
│   ├── runner/adapters/*.toml       # 벤더 어댑터 — 새 LLM CLI 는 TOML 1개 추가로 붙는다
│   ├── schemas/*.json               # codex --output-schema 용
│   └── runs/                        # 실행 산출물 (gitignored — 프롬프트·로그·비용)
└── tools/
    └── sync-vendors.py              # .agent/ → .claude/·.codex/ 투영 생성기 (--check 로 드리프트 판정)

# 벤더 투영 (생성물 — 직접 편집 금지, custom-block-generated-edit.sh 가 차단)
.claude/agents/ · .claude/skills/ · .claude/rules/     # .agent/ 에서 그대로 복사
.codex/agents/*.toml                                   # .agent/agents/*.md → TOML 변환

# 벤더 전용 손유지 파일 (투영 대상 아님 — .agent/hooks/*.sh 를 경로로 가리킨다)
.claude/settings.json                # 권한 화이트리스트 + hooks 등록
.codex/config.toml · .codex/hooks/   # Codex 훅 배선·payload 어댑터

# 프로젝트 루트 동반 자산
CLAUDE.md · AGENTS.md                # 벤더별 진입 지침 (라우팅만 — 내용은 .agent/ 참조)
tickets/                             # 티켓 정의 + 의존 DAG (작업 단위 SSOT)
.github/pull_request_template.md     # PR 본문 포맷 SSOT
.mcp.json                            # 프로젝트 MCP (context7)
```

## 플러그인 의존성 없음

본 harness 는 **Claude Code 플러그인/마켓플레이스 의존이 없다** — `.claude/settings.json` 에
`extraKnownMarketplaces` / `enabledPlugins` 모두 미설정. clean clone + env export 만으로 동작한다.

## 의존 방향

```
skills  →  agents  →  (mcp + bash hook guards)
hooks   →  (독립; tool 호출 전/후, 세션 시작에 끼어듦)
docs    ←  agents/skills가 참조만 (write 금지)

# 권한 모델은 deny + hook guard 가 기본이며, allow 는 Analysis 산출 경로 + 무해한 read-only Bash 에만 한정:
#   - permissions.allow = Analysis 산출 경로 (Edit on /.claude-local/jobs/** · /analysis/** · /retrospectives/**)
#     + read-only Bash 2종 (Bash(date:*) 타임스탬프 · Bash(ls:*) Step 4.5 게이트).
#     Write(path) 규칙은 파일 권한 검사에 매칭되지 않으므로 두지 않는다 — Edit 규칙이 모든 파일 편집 도구를 커버한다.
#   - permissions.deny 가 .env/credentials/key 류 Read 를 1차 차단.
#   - custom-block-*.sh / custom-secrets-*-guard.sh 훅이 Bash/Edit 레이어에서 2~3차 차단.
```

## 진단 Rubric (총 100점)

> **원칙**: rubric 은 가변 사실(카운트 등)을 본문에 박지 않는다 — 측정도구가 스스로 drift 원천이 되지 않도록,
> 카운트류는 각 디렉토리 README/`validate-harness.sh` 에 위임하고 여기서는 "실제와 일치" 규칙만 둔다.

| 영역 | 배점 | 하위 체크 (모두 충족 = PASS) |
|---|---:|---|
| **A. 이식성** | 18 | ⓐ 모든 harness 자산 git tracked ⓑ 사용자별 절대경로·username 의존 없음(산출 경로는 `/` 앵커) ⓒ OAuth/시크릿 의존은 onboarding.md 로 분리 |
| **B. 완결성** | 18 | 변경 절차에 등록된 모든 표면이 실재: ⓐ hooks/ agents/ skills/ rules/ (+ 각 README) ⓑ settings.json · .mcp.json · onboarding.md · scripts/validate-harness.sh ⓒ docs/(conventions·ssot-map·review-grading·review-false-positives·review-discipline·analysis-workflow·collaboration-protocol·job-lifecycle) ⓓ templates/ · orchestration/ |
| **C. 일관성 (형식)** | 13 | ⓐ frontmatter 스키마 통일(agent/skill=name·description, rule=`paths`) ⓑ kebab-case 파일·디렉토리명 ⓒ registry형 디렉토리(agents·skills·hooks·rules·docs·scripts)마다 README 존재 ⓓ HARNESS 트리 구문 유효 |
| **D. 실효성** | 18 | ⓐ hooks 스크립트 `bash -n` 통과 + 실제 정규식 매치 ⓑ agents `tools:` 가 실존 도구명이며 서버명만 두지 않음 ⓒ 워크플로우 `--dry-run` 이 전부 통과하고 wave 너비가 설계와 일치 |
| **E. 온보딩** | 9 | ⓐ onboarding.md 만으로 셋업(clone→env→MCP→빌드) ⓑ project `.mcp.json` 첫 승인 절차 명시 ⓒ onboarding 내 수량 표기가 가변 카운트를 박지 않음 |
| **F. 유지보수성** | 9 | ⓐ 변경 절차 표가 갱신 대상 파일을 빠짐없이 열거 ⓑ 훅/에이전트/스킬/규칙 추가 컨벤션 문서화 ⓒ **cross-file drift 검증 수단 존재**(`scripts/validate-harness.sh`) |
| **G. 안전성** | 5 | ⓐ 파괴적 Git 연산(push·보호 브랜치 merge) ask/차단 가드 ⓑ MCP write ask 가드 ⓒ 시크릿 Read/echo 차단 + `permissions.deny` 가 README 문구와 일치 |
| **H. 문서 정합성 (교차)** | 10 | ⓐ 각 README 목록·헤딩 카운트 = 실제 파일 수 ⓑ settings.json hook 등록 ↔ hooks/*.sh 파일명 일치, `custom-` 접두 무결 ⓒ .mcp.json 등록 수 = 명세 ⓓ HARNESS·onboarding·루트 CLAUDE.md 가 가변 카운트를 하드코딩하지 않음. **자동 점검: `bash .agent/scripts/validate-harness.sh`** |

### 채점 모델

- 각 영역은 위 하위 체크의 통과율로 환산: **≥80% → PASS(배점 만점)** · **40–80% → PARTIAL(배점 절반)** · **<40% → FAIL(0점)**.
- **게이트**: FAIL 영역이 **2개 이상이면 총점과 무관하게 "배포 불가"**(harness 신뢰 불가).
- 카운트·README 목록 멤버십·settings↔hooks·wikilink 무결성(B·C·H 일부)은 `validate-harness.sh` 가 기계적으로 판정한다.
  단 가드는 **수치형 카운트 하드코딩**만 잡는다 — 서술형 표기·문맥 정확성은 사람 점검 몫이다.

### 합격선: 90/100

## 변경 절차

| 변경 | 갱신할 파일 |
|---|---|
| 새 훅 | `.agent/hooks/<name>.sh` + `.claude/settings.json` matcher + `.codex/config.toml` + `.agent/hooks/README.md` |
| 새 에이전트 | `.agent/agents/<name>.md` (Scope Boundary + Verification Checklist 필수) + `.agent/agents/README.md` |
| 새 스킬 | `.agent/skills/<name>/SKILL.md` + `.agent/skills/README.md` |
| 새 코드 규칙 | `.agent/rules/<axis>.md` (`paths` frontmatter + 근거) + `.agent/rules/README.md` |
| 새 티켓 | `tickets/UND-NN-<slug>.md` + `tickets/README.md` 의 wave 표·DAG |
| Analysis Workflow 절차 변경 | `.agent/docs/analysis-workflow.md` + 루트 `CLAUDE.md` 동기화 |
| 새 워크플로우 | `.agent/orchestration/workflows/<name>.toml` + `.agent/orchestration/README.md` 트리 (`--dry-run` 으로 wave 너비 확인) |
| 새 벤더(LLM 에이전트) | `.agent/orchestration/runner/adapters/<vendor>.toml` + `adapters/README.md` 표 (러너 코드는 고치지 않는다) |
| **`.agent/` 자산 변경 후 투영 (필수)** | `.agent/tools/sync-vendors.py` 실행 — 투영본을 직접 고치면 훅이 차단한다 |
| **표면 추가/삭제 후 검증 (필수)** | `bash .agent/scripts/validate-harness.sh` — **경고 0** 확인 |

## SSOT (단일 진실 공급원)

전체 매핑은 [`docs/ssot-map.md`](docs/ssot-map.md) 가 정본이다. 하네스 자체에 한정하면:

| 정보 | SSOT |
|---|---|
| harness 진단 기준 | 본 문서의 Rubric |
| 프로세스 게이트 4개 룰 | `.agent/docs/conventions.md` |
| 코드 작성 규칙 | `.agent/rules/` (축별) |
| 리뷰 등급·verdict | `.agent/docs/review-grading.md` |
| 5축 자가 리뷰 정의 | `.agent/skills/custom-self-code-review/SKILL.md` (무인 노드가 `role_file` 로 주입) |
| 카운트·cross-file 정합 검증 | `.agent/scripts/validate-harness.sh` (각 README 헤딩이 카운트 SSOT) |
| 하네스 자산 전체 | `.agent/` — `.claude/`·`.codex/` 는 생성 투영본 |
| 벤더 투영 생성 규칙 | `.agent/tools/sync-vendors.py` |
| 멀티 에이전트 워크플로우·러너·어댑터 | `.agent/orchestration/README.md` |
| 권한·훅 등록 | `.claude/settings.json` (Claude) · `.codex/config.toml` (Codex) |
