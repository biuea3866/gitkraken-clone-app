# 벤더 어댑터 — 새 LLM 에이전트 붙이기

러너(`run-graph.py`)에는 벤더 분기가 없다. CLI 를 어떻게 조립하고 결과를 어디서 회수하는지는
**이 디렉토리의 TOML 이 유일한 정의**다. 새 에이전트를 붙이려면 TOML 1개를 추가한다 —
러너 코드는 고치지 않는다.

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
