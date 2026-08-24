# .agent/orchestration — 멀티 에이전트 워크플로우

여러 LLM 에이전트를 DAG 로 엮어 무인 실행하는 계층. 워크플로우 정의(무엇을 어떤 순서로)와
러너(어떻게 실행)와 어댑터(어떤 CLI 로)를 분리한다.

```
.agent/orchestration/
├── README.md                       # 본 문서
├── workflows/                      # 워크플로우 정의 (DAG)
│   ├── harness-audit.toml              # HARNESS.md 수량·경로 주장 감사 (rubric H 자동화)
│   ├── develop-1-spec.toml             # 개발 ①②③ — 요구사항 → 근거 → 스펙 → 승인 게이트
│   ├── develop-2-implement.toml        # 개발 ④ — 구현 → 1차 5축 병렬 검증 → verdict
│   ├── develop-3-repair.toml           # 개발 ⑥ REQUEST_CHANGES — 수정 → 2차 6축 → 최종 → 게이트
│   ├── develop-3-approve.toml          # 개발 ⑥ APPROVED·COMMENT — 문서 점검 → 최종 → 게이트
│   ├── develop-4-verify.toml           # 개발 ⑧ — 게이트 ⑦에서 사람이 고친 diff 를 5축 재검증
│   └── ticket-review.toml              # tickets/ 를 5개 렌즈로 병렬 리뷰 → 종합 (읽기 전용)
├── runner/
│   ├── run-graph.py                # DAG 실행기 (서드파티 의존 0, stdlib tomllib)
│   └── adapters/                   # 벤더 어댑터 — 새 LLM CLI 는 여기 TOML 1개만 추가
│       ├── README.md                   # 어댑터 스키마 + 추가 절차
│       ├── claude.toml
│       └── codex.toml
├── schemas/                        # 구조화 출력 강제용 JSON Schema (codex --output-schema)
│   ├── facts.json · spec.json · review.json · doc-drift.json
│   └── axis-review.json (단일 축) · final-summary.json (최종 판정)
└── runs/                           # 실행 산출물 (gitignore — 프롬프트·로그·비용 포함)
```

## 실행

대화형으로는 **`/custom-orchestrate <workflow>`** 로 부른다 — dry-run 검증 → 실행 계획 게시 →
사용자 확인 → 실행 → 산출물·게이트 요약까지 스킬이 처리한다 (`.agent/skills/custom-orchestrate/`).
아래는 직접 호출하는 형태다.

```bash
R=.agent/orchestration/runner/run-graph.py
W=.agent/orchestration/workflows

$R $W/harness-audit.toml                      # 실행
$R $W/harness-audit.toml --dry-run            # wave 계획만 (자리표시자 검증 포함)
$R $W/<workflow>.toml --only inventory,synthesize   # 부분 실행 (의존 무시)
$R $W/<workflow>.toml --max-parallel 2        # 동시 실행 상한 (기본 4)
$R $W/<workflow>.toml --no-failover           # 벤더 소진 시 대체 벤더 재시도 끄기 (기본 켜짐)
$R $W/<workflow>.toml --set ticket=UND-14      # 프롬프트의 {{ticket}} 치환 (반복 가능)
```

프롬프트의 `{{key}}` 는 `--set key=value` 로 치환한다. 미해결 자리표시자가 남아 있으면
**노드를 하나도 실행하지 않고** 실패한다 (`--dry-run` 에서도 검증된다).

산출물은 `.agent/orchestration/runs/<타임스탬프>-<workflow>/` 에 남는다. `run.json` 이 매니페스트다.

## 사람 게이트와 재개

`type = "gate"` 노드에 닿으면 러너는 체크리스트를 출력하고 **거기서 멈춘다** (exit 2).
러너에는 대기 기능이 없다 — 게이트는 "사람이 판단하고 재개 명령을 실행한다"로 표현된다.

```bash
$R $W/<workflow>.toml                                   # 게이트에서 멈춤 (exit 2)
# 사람이 <run-dir>/<gate>.json 과 업스트림 산출물 검토
$R $W/<workflow>.toml --run-dir <위 run-dir> --start-at <다음 노드>   # 재개
```

`--start-at` 은 그 노드와 **후손만** 실행한다. 이미 끝난 업스트림 산출물은 `--run-dir` 로
넘긴 이전 실행 디렉토리에서 그대로 읽으므로, `--run-dir` 없이 쓰면 실패한다.
게이트 노드는 단독 wave 여야 한다 (같은 wave 에 실행 노드가 섞이면 러너가 거부한다).

## 벤더·모델 라우팅 정책

작업 성격으로 정한다. 노드마다 `vendor`/`model` 을 명시하고, 새 워크플로우도 이 표를 따른다.

| 작업 성격 | 벤더·모델 | 이유 |
|---|---|---|
| **코드 작성** (구현·리팩터링) | `claude` / `opus` | 설계 판단과 다중 파일 편집 품질이 결과를 좌우한다 |
| **코드 리뷰** | `codex` / `gpt-5.6-terra` | 판정 기준이 문서로 고정돼 있고, 스키마 강제(`--output-schema`)로 형식이 안정된다 |
| **문서 작성·점검** (스펙·드리프트) | `codex` / `gpt-5.6-terra` | 위와 같음. 읽기 전용이라 병렬로 넓게 돌릴 수 있다 |
| **MCP 가 필요한 조회** (context7 라이브러리 문서 등) | `claude` + `mcp_config` | codex 노드는 프로젝트 MCP 에 접근할 수 없다 |

### 벤더가 소진되면 대체 벤더로 1회 넘어간다

한쪽 사용량이 바닥나도 워크플로우가 통째로 죽지 않도록, 노드 실행이 **소진 신호와 함께** 실패하면
대체 벤더로 **1회만** 재시도한다. 선언은 어댑터 TOML 의 `[failover]` 에 있고 러너에는 벤더 분기가 없다 —
규칙과 스키마는 [`runner/adapters/README.md`](runner/adapters/README.md).

```
claude 소진 → codex 로 재시도    (mcp_config·tools 제거됨)
codex  소진 → claude 로 재시도   (output_schema 제거됨 — 구조화 출력 강제 사라짐)
```

- **소진일 때만** 넘어간다. 스키마 오류·타임아웃 같은 일반 실패는 그대로 실패로 남는다.
- **1 hop 제한** — 순환과 무한 재시도를 막는다.
- 제거된 키·감지 패턴은 터미널·`<node>.log`·`run.json` 에 전부 남는다. 조용히 넘어가지 않는다.
- 끄려면 `--no-failover`.

**MCP 가 필수인 노드는 failover 로 되살아나지 않는다.** codex 노드에는 프로젝트 MCP 가 붙지 않으므로
`mcp_config` 가 제거된 채 실행된다 — 조회 결과가 비면 하위 노드가 그 빈 값을 근거로 삼는다.
그런 노드는 실패로 두고 사람이 판단하는 편이 낫다.

### MCP 는 claude 노드만 붙는다

codex 노드는 `.mcp.json` 을 읽지 않고(개인 설정에 `codex mcp add` 로 따로 등록해야 한다),
claude.ai connector 계열(`mcp__claude_ai_*`)은 아예 존재하지 않는다. 그래서 **MCP 가 필요한
단계는 claude 노드로 잡고 결과를 파일로 codex 에 넘긴다.**

```toml
[[nodes]]
id = "evidence"
vendor = "claude"
mcp_config = ".mcp.json"                       # --mcp-config + --strict-mcp-config
tools = ["Read", "mcp__context7__get-library-docs"]

[[nodes]]
id = "spec"
vendor = "codex"
needs = ["evidence"]                           # evidence.json 경로가 프롬프트에 주입된다
```

`tools` 에 쓸 MCP 도구 이름을 **명시해야** 호출된다 (`--allowedTools` 로 넘어간다). 헤드리스에서
실제로 붙는 것은 `.mcp.json` 등록분뿐이다 — `context7` 같은 stdio/HTTP 서버는
붙고, claude.ai connector 계열은 붙지 않는다. 따라서 **요구사항은 사람이 파일로 넘기고,
노드는 코드와 파일만 근거로 삼는** 분업이 된다.

다운스트림 codex 노드 프롬프트에는 "사실은 evidence 파일만 근거로 쓰고, 요구사항과 다르면
evidence 를 우선한다" 를 넣는다 — 그래야 티켓의 부정확한 서술이 스펙으로 전파되지 않는다.

#### 나중에 MCP 조회를 codex 노드로 옮길 때

현재는 **claude 노드가 MCP 조회를 담당**한다. codex 로 옮기는 것은 뒤로 미룬 결정이며, 옮길 때 알아야 할 것:

- codex 는 프로젝트 `.mcp.json` 을 읽지 않는다. `$CODEX_HOME/config.toml` 의 `[mcp_servers]` 만 본다 (`codex mcp add <name> --url <URL>` 또는 `-- <command>` 로 등록, stdio 서버는 `--env KEY=VALUE`).
- 개인 codex 홈에 서버를 등록해 두면 codex 노드도 MCP 에 닿을 수 있다 — 다만 개인 설정이라 clone 단독 동작이 아니다.
- 다만 **도구 이름이 다를 수 있다.** 같은 서비스라도 서버 구현이 다르면 도구 이름과 인자가 그대로 옮겨가지 않으므로, 전환 시 노드 프롬프트를 도구 이름 기준으로 다시 맞춰야 한다.
- 개인 홈 기반이라 **clone 단독 동작이 아니다.** 재현하려면 등록 절차를 onboarding 에 넣어야 한다.
- codex 노드에서 MCP 도구가 실제 노출되는지는 **아직 검증하지 않았다** — 전환 착수 시 최소 프로브부터 한다.

## 개발 루프 (custom-develop-orchestrator)

티켓 1건을 스펙 → 구현 → 1차 5축 검증 → (필요 시) 수정·2차 검증 → 최종 판정까지 돈다.
**사람 게이트가 워크플로우 경계**이고, **verdict 분기는 메인 Claude 가 판정**한다 (러너에 조건부 분기 없음).

```
develop-1-spec        evidence(claude) → spec(codex) → approve_spec 🚦
develop-2-implement   implement_1(claude) → [5축 병렬](codex) → review_summary_1(codex)
                             │
       verdict == REQUEST_CHANGES ──→ develop-3-repair
                             │          repair_and_verify_2(claude) → [2차 6축 병렬](codex)
                             │          → final_summary(codex) → review_and_draft 🚦
       verdict == APPROVED|COMMENT ─→ develop-3-approve
                                        docs_final_1(codex) → final_summary(codex) → review_and_draft 🚦
                             │
       ⑦에서 사람이 코드를 고쳤다면 ─→ develop-4-verify
                                        [재검증 6축 병렬](codex) → final_summary_v(codex) → verify_and_draft 🚦
```

| 단계 | 워크플로우 · 노드 | 담당 |
|---|---|---|
| ① 요구사항 수집 (티켓 본문 → 파일) | (워크플로우 밖) `--set requirements_file=` | 사람 또는 대화형 Claude |
| ① 코드베이스 근거 수집 | `develop-1-spec` → `evidence` | claude/sonnet |
| ② 스펙 생성 | `develop-1-spec` → `spec` | codex/terra |
| **③ 구현 승인 🚦** | `develop-1-spec` → `approve_spec` (exit 2) | 사람 |
| ④ 구현 ⟲ 로컬 검증 | `develop-2-implement` → `implement_1` | claude/opus (write) |
| ④ 1차 5축 검증 | `intent_ac_1`·`tests_1`·`side_effects_1`·`deploy_1`·`rollback_1` | codex/terra 병렬 |
| ④ verdict 확정 | `review_summary_1` | codex/terra |
| **⑤ verdict 분기 판정** | (워크플로우 밖) `review_summary_1.json` 을 읽어 선택 | 메인 Claude |
| ⑥ 수정 + 2차 6축 (REQUEST_CHANGES) | `develop-3-repair` | claude/opus (write) + codex/terra 병렬 |
| ⑥ 문서 점검 (APPROVED·COMMENT) | `develop-3-approve` → `docs_final_1` | codex/terra |
| ⑥ 최종 판정 | `develop-3-*` → `final_summary` | codex/terra |
| **⑦ 최종 검토 🚦** | `develop-3-*` → `review_and_draft` (exit 2) | 사람 |
| ⑧ 사람 수정분 재검증 (⑦에서 코드를 고쳤을 때만) | `develop-4-verify` → 6축 → `final_summary_v` | codex/terra 병렬 |
| **⑧ 재검증 게이트 🚦** | `develop-4-verify` → `verify_and_draft` (exit 2) | 사람 (`/custom-pr-create`) |

```bash
R=.agent/orchestration/runner/run-graph.py
W=.agent/orchestration/workflows
RUN=.agent/orchestration/runs/UND-14            # 티켓 1건 = run-dir 1개

# ①  티켓 본문을 파일로 준비 (헤드리스 노드는 대화 맥락이 없다)
# ②③ 스펙 → 게이트에서 exit 2
$R $W/develop-1-spec.toml --run-dir $RUN \
  --set ticket=UND-14 --set requirements_file=.claude-local/UND-14.md \
  --set decisions_file=<결정 문서 또는 없음>   # 빠뜨리면 낡은 결정으로 AC 가 굳는다

# ④  구현 + 1차 5축 + 요약
$R $W/develop-2-implement.toml --run-dir $RUN \
  --set ticket=UND-14 --set spec_file=$RUN/spec.json \
  --set decisions_file=<결정 문서 또는 없음> --max-parallel 5

# ⑤  분기 — 메인 Claude 가 판정 (없거나 파싱 실패면 REQUEST_CHANGES, fail-closed)
verdict=$(python3 -c "import json;print(json.load(open('$RUN/review_summary_1.json')).get('verdict','REQUEST_CHANGES'))")

# ⑥  REQUEST_CHANGES → develop-3-repair.toml / APPROVED·COMMENT → develop-3-approve.toml
$R $W/develop-3-repair.toml --run-dir $RUN \
  --set ticket=UND-14 --set spec_file=$RUN/spec.json \
  --set review_file=$RUN/review_summary_1.json \
  --set decisions_file=<결정 문서 또는 없음> --max-parallel 6

# ⑦  사람이 final_summary.json / docs_final_*.json 검토 → 문서 반영

# ⑧  ⑦에서 코드를 고쳤으면 재검증 (고치지 않았으면 건너뛴다) → 통과 후 커밋 → Draft PR
# 축 노드는 사람의 터미널을 볼 수 없다 — 빌드 증적을 파일로 남겨 넘긴다.
(cd <워크트리> && ./gradlew build > $RUN/human-build.log 2>&1)

$R $W/develop-4-verify.toml --run-dir $RUN \
  --set ticket=UND-14 --set spec_file=$RUN/spec.json \
  --set review_file=$RUN/final_summary.json \
  --set decisions_file=<결정 문서 또는 없음> \
  --set build_log=$RUN/human-build.log --max-parallel 6
```

의도적으로 지키는 것 3개:

- **노드는 커밋하지 않는다.** 변경을 워킹트리에 남기고 커밋·PR 은 게이트 ⑦ 이후 사람이 한다.
  무인 실행이 브랜치 히스토리를 만들면 되돌리는 비용이 사람에게 넘어간다.
- **문서 노드는 문서를 수정하지 않는다** (제안만). 대상은 항상 **최종 diff** 다 — 중간 상태를 근거로 하면
  이미 해소된 드리프트를 다시 올린다.
- **p3~p4 는 자동 수정하지 않는다.** 최종 보고에만 남긴다 (스코프 확산 방지).
- **사람이 게이트에서 고친 코드도 축 노드를 거친다** (⑧). 무인 5축은 수정 **이전** 상태만 봤으므로,
  게이트에서 손댄 diff 를 그대로 커밋하면 검증 없는 코드가 PR 로 나간다.

5축 정의의 SSOT 는 `.agent/skills/custom-self-code-review/SKILL.md` 하나다 — 축 노드가 `role_file` 로
주입받아 대화형 자가 리뷰와 무인 검증이 같은 기준을 쓴다. 등급·verdict 규칙은
`.agent/docs/review-grading.md` 가 정본이다.

`.agent/skills/custom-develop-orchestrator/SKILL.md` 가 이 워크플로우들을 구동하는 대화형 진입점이며,
**verdict 분기 판정을 담당**한다.

## 노드 스펙 (`[[nodes]]`)

| 필드 | 필수 | 설명 |
|---|---|---|
| `id` | ✅ | 노드 식별자. 산출물 파일명(`<id>.json`)이 된다 |
| `vendor` | ✅ | 어댑터 id (`claude` / `codex`). gate 노드에는 쓰지 않는다 |
| `prompt` | ✅ | 작업 지시. 출력 계약은 러너가 자동으로 덧붙인다 |
| `type` | | `gate` 로 두면 사람 게이트 (러너가 멈춘다). 생략 시 실행 노드 |
| `model` | | `opus` / `gpt-5.6-terra` 등 벤더별 모델 id |
| `effort` | | 추론 강도. claude `--effort`, codex `model_reasoning_effort` 로 매핑 |
| `needs` | | 업스트림 노드 id 배열. 이게 그래프의 엣지다 |
| `role_file` | | 역할 문서 경로. 프롬프트 앞에 주입된다 |
| `output_schema` | | JSON Schema 경로. **codex 전용** (claude 는 스키마 강제 수단이 없다) |
| `tools` | | claude `--allowedTools` 목록 |
| `permission_mode` | | claude `--permission-mode` (기본 `dontAsk`. 쓰기 노드는 `acceptEdits`) |
| `mcp_config` | | MCP 설정 파일 경로. **claude 노드 전용** — `--mcp-config` + `--strict-mcp-config` 로 넘어간다 |
| `sandbox` | | codex `-s` — `read-only` (기본) / `workspace-write` |
| `cwd` | | 레포 루트 기준 실행 디렉토리 |
| `timeout_seconds` | | 노드 타임아웃 (기본 900) |

의존이 없는 노드는 같은 wave 로 묶여 **병렬 실행**된다. 순환이 있으면 실행 전에 실패한다.
벤더가 지원하지 않는 필드를 노드에 두면(예: claude 노드에 `output_schema`) 실행 전에 실패한다 —
조용히 무시되지 않는다.

## 지켜야 할 제약 (실측으로 확인된 것)

- **엣지는 파일로만 흐른다.** 노드는 업스트림 산출물 `<run-dir>/<id>.json` 을 읽는다. stdout 파싱에 의존하지 않는다 — 벤더별로 응답 봉투가 다르다.
- **구조화 출력은 비대칭이다.** codex 는 `--output-schema` 로 스키마를 강제할 수 있고, claude 는 없다. claude 노드의 JSON 은 러너가 응답에서 추출하며, 실패 시 `_parse_error` 로 기록되고 노드가 `failed` 처리된다.
- **같은 wave 의 쓰기 노드는 하나만 둔다.** 같은 파일을 두 노드가 쓰면 충돌한다 (한 파일 = 한 writer). 읽기 전용 노드는 `sandbox = "read-only"` / `tools = ["Read"]` 로 두고 같은 워크트리를 공유한다.
- **헤드리스 노드에는 승인을 누를 사람이 없다.** `permissionDecision: "ask"` 를 내는 훅(`custom-block-git-push`·`custom-confirm-mcp-write`)에 걸리면 노드가 진행하지 못한다. 워크플로우 노드에서 push·merge·MCP write 를 시키지 않는다.
- **Codex 노드는 프로젝트 훅 신뢰 승인이 선행돼야 한다.** 미승인 상태에서는 레포 가드가 조용히 스킵된다.
- **조건부 실행(분기)은 러너에 없다.** verdict 에 따라 다음 단계를 고르는 흐름은 워크플로우를 나누고 사람 또는 대화형 Claude 가 산출물을 읽어 선택한다.

## 추가/갱신 시

1. 새 워크플로우는 `workflows/<name>.toml` 에 두고 본 README 트리에 한 줄 추가한다.
2. `--dry-run` 으로 wave 너비를 먼저 확인한다. 모든 wave 가 너비 1 이면 병렬 이득이 없으니 분해를 다시 본다.
3. 노드 프롬프트에 "추측 금지 · 근거(evidence) 필수" 를 넣는다. 근거 없는 fact 는 하위 노드에서 증폭된다.
4. 새 LLM 에이전트(벤더)를 붙이려면 `runner/adapters/README.md` 를 따른다 — 러너 코드는 고치지 않는다.
