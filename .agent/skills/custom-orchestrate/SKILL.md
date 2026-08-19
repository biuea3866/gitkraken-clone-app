---
name: custom-orchestrate
description: >
  .agent/orchestration/workflows/*.toml 워크플로우 하나를 골라 실행한다 (멀티 에이전트 DAG).
  사전 dry-run 으로 wave 계획·자리표시자를 검증하고, 사용자 확인을 받은 뒤에만 실제 실행하며,
  노드별 결과·산출물 경로·게이트 도달 여부를 요약한다.
  **명시 호출 전용** — 자연어 작업 요청("구현해줘" 등)만으로는 발화하지 않는다.
  Use when:
  - 사용자가 "/custom-orchestrate" 를 호출
  - 사용자가 워크플로우 이름을 대며 실행을 요청 ("harness-audit 돌려줘", "develop-2 실행해줘")
  - 사용자가 "어떤 워크플로우 있어?" 처럼 목록을 물을 때
---

# custom-orchestrate

`.agent/orchestration/workflows/` 의 워크플로우 하나를 수동으로 실행하는 진입점.
**사용자가 요청할 때만 돈다** — 자동 발화하지 않는다.

## when to use

- `/custom-orchestrate` — 인수 없이 호출하면 **워크플로우 목록**을 보여주고 멈춘다
- `/custom-orchestrate <workflow>` — 그 워크플로우 실행
- `/custom-orchestrate <workflow> --set k=v --set k2=v2` — 자리표시자를 채워 실행
- "harness-audit 돌려줘" / "develop-2-implement 실행" 처럼 이름을 명시한 요청

**하지 않는 것**: 티켓 1건을 처음부터 끝까지 도는 루프. 그건 [[custom-develop-orchestrator]] 다
(이 스킬은 워크플로우 **1개** 실행에만 관여한다).

## 실행 절차

### Step 1 — 워크플로우 특정

```bash
ls .agent/orchestration/workflows/
```

인수가 없거나 이름이 모호하면 목록과 각 워크플로우의 `description` 첫 줄을 게시하고
**어느 것을 돌릴지 사용자에게 묻는다.** 임의로 고르지 않는다.

부분 이름(`develop-2`)은 유일하게 매칭될 때만 해석한다. 둘 이상 매칭되면 되묻는다.

### Step 2 — 필요한 `--set` 키 파악

워크플로우 파일의 `prompt` 에 있는 `{{key}}` 가 채워야 할 자리표시자다.

```bash
grep -oE '\{\{[a-zA-Z_][a-zA-Z0-9_]*\}\}' .agent/orchestration/workflows/<workflow>.toml | sort -u
```

사용자가 값을 주지 않은 키가 있으면 **터미널로 묻는다.** 추측해서 채우지 않는다 —
잘못된 티켓 키·경로로 노드를 돌리면 비용만 쓰고 산출물이 쓸모없어진다.

파일 경로를 받는 키(`requirements_file`·`spec_file`·`review_file`)는 **실존을 먼저 확인**한다.

### Step 3 — dry-run (필수, 무료)

```bash
.agent/orchestration/runner/run-graph.py .agent/orchestration/workflows/<workflow>.toml --dry-run [--set ...]
```

노드를 하나도 실행하지 않고 wave 계획과 자리표시자를 검증한다. 여기서 실패하면 그대로 보고하고 멈춘다.

### Step 4 — 실행 계획 게시 + 확인 요청 (필수)

실제 실행 전에 다음을 터미널에 게시하고 **사용자 확인을 받는다.** 확인 없이 실행하지 않는다 —
노드가 유료 모델을 호출하고, 쓰기 노드는 워킹트리를 수정한다.

- 실행할 명령 전문
- wave 구성 (dry-run 출력 그대로) — 노드 수·벤더·모델
- **쓰기 노드 유무**: `permission_mode` 또는 `sandbox = "workspace-write"` 인 노드가 있으면
  "이 워크플로우는 워킹트리를 수정합니다" 를 명시하고, 워크트리에서 돌릴지 확인한다
- 게이트 노드 유무: 있으면 "게이트에서 exit 2 로 멈춥니다" 를 명시

```bash
grep -nE 'permission_mode|sandbox = "workspace-write"|type = "gate"' .agent/orchestration/workflows/<workflow>.toml
```

### Step 5 — 실행

```bash
.agent/orchestration/runner/run-graph.py .agent/orchestration/workflows/<workflow>.toml \
  --run-dir <run-dir> [--set ...] [--max-parallel N]
```

- `--run-dir`: 개발 루프 워크플로우(`develop-*`)는 **티켓 1건 = run-dir 1개** 로 고정한다
  (`.agent/orchestration/runs/<UND-NN>/`). 그 외에는 생략해 타임스탬프 디렉토리를 쓴다.
- `--max-parallel`: 가장 넓은 wave 너비 이상으로 준다 (dry-run 출력의 최대 너비).
- 러너 출력을 파이프로 가리지 않는다 — wave 진행·노드 성공/실패·비용이 실시간으로 보인다.
- 장기 실행이면 시작 전에 예상 소요를 공지한다.

### Step 6 — 결과 요약

`run.json` 매니페스트를 읽고 게시한다.

```bash
python3 -m json.tool <run-dir>/run.json
```

게시 항목:

| 항목 | 출처 |
|---|---|
| 노드별 status·소요·비용 | `run.json` 의 `nodes[]` |
| 누적 비용 | `total_cost_usd` |
| 게이트 도달 여부 | `halted_at_gate` · `pending_nodes` |
| 산출물 경로 | `<run-dir>/<node-id>.json` |
| 핵심 산출물 내용 | 워크플로우 성격에 맞는 파일 (verdict·findings·proposals 등) |

**종료 코드 해석** (중요):

| exit | 의미 | 대응 |
|---|---|---|
| 0 | 전 노드 성공 | 산출물 요약 |
| 2 | **게이트 도달** — 실패가 아니다 | 게이트 체크리스트를 게시하고 사람 판단을 요청 |
| 1 | 노드 실패 있음 | 실패 노드의 `.log` 를 읽어 원인 보고 |

### Step 7 — 실패·게이트 후속

**노드 실패 시**
```bash
cat <run-dir>/<node-id>.log        # command · stdout · stderr
cat <run-dir>/<node-id>.json       # _parse_error 여부
```
`_parse_error` 면 모델이 JSON 계약을 못 지킨 것이다 — 같은 노드만 재실행한다:
`--run-dir <기존> --only <node-id>`.

**게이트 도달 시**
- 게이트 산출물(`<gate-id>.json` 의 `checklist`)을 게시하고 사용자 판단을 받는다.
- 같은 워크플로우 안에서 이어가려면: `--run-dir <기존> --start-at <다음 노드>`
  (`--run-dir` 없이 `--start-at` 을 쓰면 업스트림 산출물이 없어 실패한다)
- 개발 루프는 게이트 이후 **다른 워크플로우**로 넘어간다 — [[custom-develop-orchestrator]] 참조.

## 안티패턴

- ❌ Step 3 dry-run 없이 실행 (자리표시자 오류를 노드 실행 중에 발견 — 이미 쓴 비용이 날아간다)
- ❌ Step 4 확인 없이 실행 (유료 호출 + 워킹트리 수정)
- ❌ 자리표시자 값을 추측해서 채우기 (티켓 키·경로는 반드시 사용자에게 확인)
- ❌ exit 2 를 실패로 보고 (게이트 도달이다)
- ❌ 워크플로우를 임의로 골라 실행 (모호하면 되묻는다)
- ❌ 러너 출력을 `| tail` 로 가려 노드 실패를 놓치기
- ❌ 쓰기 노드가 있는 워크플로우를 메인 작업 디렉토리에서 실행 (워크트리 확인)
- ❌ 이 스킬로 티켓 전체 루프를 돌리려 하기 (워크플로우 1개 실행 전용)

## 관련

- `.agent/orchestration/README.md` — 워크플로우 목록·러너 옵션·게이트·벤더 라우팅
- `.agent/orchestration/runner/adapters/README.md` — 새 LLM 에이전트(벤더) 추가
- [[custom-develop-orchestrator]] — 티켓 1건 전체 루프 (여러 워크플로우 + verdict 분기)
