# 벤더 어댑터 — 새 LLM 에이전트 붙이기

러너(`run-graph.py`)에는 벤더 분기가 없다. CLI 를 어떻게 조립하고 결과를 어디서 회수하는지는
**이 디렉토리의 TOML 이 유일한 정의**다. 새 에이전트를 붙이려면 TOML 1개를 추가한다 —
러너 코드는 고치지 않는다.

> **프로필과의 경계**: 어댑터는 "이 CLI 를 **어떻게 부르는가**" 를 정의하고,
> [`../../profiles.toml`](../../profiles.toml) 은 "노드를 **어떤 구성으로** 부르는가"(vendor·model·
> effort·권한)를 정의한다. 프로필이 준 키도 결국 여기 `[flags]` 를 통해 argv 가 되므로,
> 프로필은 자신이 지정한 벤더가 지원하는 키만 쓸 수 있다 (위반 시 실행 전에 실패한다).

## 등록된 어댑터 (2개)

| 파일 | 벤더 | 프롬프트 전달 | 결과 회수 | 비고 |
|---|---|---|---|---|
| `claude.toml` | Claude Code CLI (`claude -p`) | stdin | stdout JSON 봉투 | 프로젝트 MCP 를 붙일 수 있는 유일한 벤더 |
| `codex.toml` | Codex CLI (`codex exec`) | argv | `-o` 출력 파일 | `--output-schema` 로 출력 형식 강제 가능 |

## 스키마

| 키 | 필수 | 설명 |
|---|---|---|
| `id` | ✅ | 워크플로우 노드의 `vendor` 값이 된다 |
| `description` | | 사람용 설명 |
| `base` | ✅ | 항상 붙는 고정 argv 배열 |
| `prompt_delivery` | ✅ | `stdin` (표준입력) · `argv` (마지막 인자) |
| `result` | ✅ | `stdout_envelope` (stdout JSON 에서 꺼냄) · `out_file` (CLI 가 파일에 씀) |
| `envelope_result_key` | | `result = stdout_envelope` 일 때 최종 텍스트가 담긴 키 (기본 `result`) |
| `envelope_cost_key` | | 비용이 담긴 키. 있으면 매니페스트에 누적된다 |
| `[defaults]` | | 노드가 값을 주지 않았을 때 적용할 기본값 |
| `[flags]` | ✅ | 노드 키 → argv 조각. **노드에 값이 있을 때만** 붙는다 |
| `[runtime]` | | 러너가 값을 채우는 슬롯 — `out_file` · `cwd` |
| `[failover]` | | 이 벤더가 소진됐을 때의 대체 경로 (아래 절 참조) |

### 자리표시자

| 표기 | 치환 대상 |
|---|---|
| `{value}` | 노드 값 원본 |
| `{csv}` | 리스트를 쉼표로 연결 (`tools` 등) |
| `{repo_path}` | 레포 루트 기준 경로로 해석하고 **존재를 검증**한다 (없으면 실행 전 실패) |
| `{path}` | `[runtime]` 슬롯 전용 — 러너가 산출물 경로/실행 디렉토리를 넣는다 |

### 지원하지 않는 필드는 조용히 무시되지 않는다

노드에 있는 키가 어댑터 `[flags]` 에 없으면 러너가 **실행 전에 실패**한다. 예를 들어 claude
노드에 `output_schema` 를 두면 "벤더 'claude' 는 'output_schema' 를 지원하지 않습니다" 로 멈춘다.
러너가 직접 해석하는 예약 키(`id` · `vendor` · `type` · `needs` · `prompt` · `role_file` ·
`cwd` · `timeout_seconds` · `label`)는 이 검사에서 제외된다.

## 벤더 소진 시 failover

한 벤더의 사용량이 바닥나도 워크플로우가 통째로 죽지 않도록, **대체 벤더로 1회 재시도**한다.
러너에는 벤더 이름 분기가 없다 — 아래 선언만 보고 동작한다.

```toml
[failover]
fallback_to = "codex"            # 소진 시 넘어갈 벤더 (자기 자신·미존재 벤더는 실행 전 실패)
fallback_model = "gpt-5.6-terra" # 대체 벤더의 모델 id (노드의 model 은 벤더 전용이라 못 넘긴다)
exhaustion_patterns = [          # stdout+stderr 에 대해 대소문자 무시 정규식
  "usage limit reached",
  "rate.?limit",
  "\"status\"\\s*:\\s*429",
]

# 벤더 전환 시 안전 의미를 잃지 않도록 키·값을 명시 변환한다.
[failover.translate.permission_mode]
acceptEdits = { key = "sandbox", value = "workspace-write" }
```

| 규칙 | 동작 |
|---|---|
| **1 hop 제한** | 노드당 재시도 1회. `claude → codex → claude` 순환과 양쪽 소진 시 무한 재시도를 막는다 |
| **소진일 때만** | `exhaustion_patterns` 에 걸릴 때만 넘어간다. 스키마 오류·타임아웃 같은 일반 실패는 그대로 실패로 남는다 |
| **미지원 키 제거** | 대체 벤더의 `[flags]` 에 없는 키는 제거된다. **무엇을 버렸는지 로그·`run.json` 에 전부 기록**한다 |
| **안전 키 변환** | `[failover.translate]` 로 `permission_mode` ↔ `sandbox` 를 매핑해 쓰기 노드가 승인 모드를 잃지 않게 한다 |
| **끄기** | `--no-failover` |

기록 위치: 터미널 `[failover: a → b]` · `<node>.log` 의 `===== FAILOVER =====` 절 ·
`run.json` 의 `failover[]`(`from`·`to`·`signal`·`changes`)와 `vendor_requested`.

> ⚠ **감수하는 품질 저하.** 벤더가 바뀌면 그 벤더 전용 기능은 사라진다.
> `codex → claude` 면 `--output-schema` 가 빠져 구조화 출력 강제가 없어지고(러너가 응답에서 JSON 을
> 추출한다) 파싱 실패 확률이 오른다. `claude → codex` 면 `mcp_config`·`tools` 가 빠져 MCP 조회가
> 불가능해진다. **MCP 가 필수인 노드는 failover 로 되살아나지 않는다** — 실패로 두고 사람이 판단하는 편이 낫다.

> ⚠ 일시적 rate limit 도 `exhaustion_patterns` 에 걸린다. 기다렸다 재시도하는 대신 벤더를 바꾸는
> 선택이며, 그게 싫으면 해당 패턴을 어댑터에서 빼면 된다.

## 추가 절차

1. `<vendor>.toml` 을 이 디렉토리에 만든다. `id` 는 파일명과 같게 둔다.
2. 해당 CLI 의 헤드리스 실행 방식을 확인해 `prompt_delivery` · `result` 를 정한다.
   - 최종 응답을 stdout 으로 내면 `stdout_envelope` (봉투 키를 `envelope_result_key` 로 지정)
   - 파일로 쓰는 옵션이 있으면 `out_file` + `[runtime].out_file`
3. `[flags]` 에 워크플로우에서 쓸 노드 키만 매핑한다. 매핑하지 않은 키는 그 벤더에서 사용 불가가 된다.
4. 위 표에 행을 추가하고, 최소 워크플로우 1개를 `--dry-run` → 실제 1회 실행으로 검증한다.
5. 검증 전에는 팀 워크플로우에 넣지 않는다. 어댑터가 잘못되면 노드가 실패하지 않고 **빈 산출물**을
   내는 경우가 있어, 하위 노드가 근거 없이 진행할 수 있다.

### 예: 최종 응답을 stdout 으로 내는 가상의 CLI

```toml
id = "acme"
description = "Acme Agent CLI — 예시 (미검증 템플릿)"

base = ["acme", "run", "--quiet"]
prompt_delivery = "stdin"
result = "stdout_envelope"
envelope_result_key = "output"

[defaults]
mode = "safe"

[flags]
model = ["--model", "{value}"]
mode = ["--mode", "{value}"]
tools = ["--tools", "{csv}"]
config = ["--config", "{repo_path}"]
```

## 검증되지 않은 것

- 어댑터 스키마는 `claude` · `codex` 두 벤더로만 실증됐다. 세 번째 벤더에서 `base` + `[flags]`
  조합만으로 부족한 사례(예: 프롬프트를 파일로만 받는 CLI)가 나오면 러너에 전달 방식을 추가해야 한다.
  그때는 `prompt_delivery` 에 값을 하나 더 넣는 방향으로 확장한다 — 벤더별 `if` 분기를 되살리지 않는다.
