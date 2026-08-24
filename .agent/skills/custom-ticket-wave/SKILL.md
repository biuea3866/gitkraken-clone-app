---
name: custom-ticket-wave
description: >
  티켓 여러 건을 의존 DAG 로 위상정렬해 wave 단위로 병렬 실행한다 (티켓당 워크트리 1개 + 러너 프로세스 1개).
  워크플로우 1개를 티켓 N건에 동시에 적용하며, 실행 전에 wave 계획·소유 경로 교집합을 검증하고,
  사용자 확인을 받은 뒤에만 실제 실행한다. 게이트에 닿으면 그 wave 에서 멈춘다.
  **명시 호출 전용** — 자연어 작업 요청("구현해줘" 등)만으로는 발화하지 않는다 (티켓 N건 × 유료 호출).
  Use when:
  - 사용자가 "/custom-ticket-wave" 를 호출
  - "wave 3 티켓 전부 스펙 돌려줘" 처럼 **여러 티켓**에 같은 단계를 적용하라는 요청
  - 티켓 목록과 워크플로우를 함께 대며 병렬 실행을 요청
---

# custom-ticket-wave

티켓 **DAG** 를 돌리는 진입점. 티켓 의존을 위상정렬해 wave 로 나누고, wave 안의 티켓을
**전량 동시에** 실행한다. **사용자가 요청할 때만 돈다.**

## 세 진입점의 관계 — 포함이 아니라 형제

```
/custom-orchestrate            워크플로우 1개                → run-graph.py × 1
/custom-develop-orchestrator   티켓 1건 7단계 (게이트 2개)     → run-graph.py × 7 (순차)
/custom-ticket-wave  ⟵ 본 스킬  티켓 N건 × 워크플로우 1개      → run-graph.py × N (프로세스 병렬)
```

세 스킬은 서로를 호출하지 않는다. 같은 러너를 **다른 폭**으로 부른다.

- 티켓 1건을 처음부터 끝까지 돌리려면 → [[custom-develop-orchestrator]]
- 워크플로우 1개만 돌리려면 → [[custom-orchestrate]]
- 본 스킬은 **한 단계를 여러 티켓에 동시에** 적용한다 (스펙 11건, 구현 11건 …)

## 왜 필요한가

`tickets/README.md` 가 10 wave · 너비 분포 `[1,11,11,4,1,1,12,9,1,2]` 를 계산해 뒀지만
그 DAG 를 소비하는 기계가 없었다. 티켓 병렬은 관행이었고 두 번 깨졌다:

- wave 3 스펙 11건을 `4+4+3` 으로 끊어 돌려 벽시계만 **45분 → 15분** 차이 (`pipeline-tuning.md` §2)
- 여러 세션이 한 워크트리를 공유해 한 티켓의 파일이 다른 티켓 커밋에 휩쓸림 (파일 소유 Rule 3)

실행기가 둘 다 **구조적으로** 막는다 — 배치가 불가능하고, 티켓마다 워크트리를 만든다.

## 실행 절차

### Step 1 — 대상 확정 (추측 금지)

워크플로우와 티켓 목록을 확정한다. **티켓 키를 추측하지 않는다** — 모호하면 되묻는다.
잘못된 티켓으로 N건을 돌리면 비용이 N배로 날아간다.

- 티켓을 명시: `--tickets UND-13,UND-14,UND-16`
- wave 전량: `--wave 3` (티켓 헤더의 `wave` 값 기준)

### Step 2 — 계획 검증 (필수, 무료)

```bash
V=.agent/orchestration/runner/wave-graph.py
W=.agent/orchestration/workflows
$V $W/<workflow>.toml --tickets <목록> --plan-only
```

여기서 확인할 것 — 하나라도 어긋나면 실행하지 않고 사용자에게 보고한다:

| 출력 | 뜻 |
|---|---|
| 티켓 wave 구성·너비 | 의도한 병렬 폭인가 |
| `대상 밖 의존 — 이미 완료로 간주` | 그 선행 티켓이 **정말** 끝났는가 (안 끝났으면 대상에 넣는다) |
| `⚠️ 헤더가 없어 wave 선택에서 제외` | 빠진 티켓이 대상이어야 하는가 (헤더를 먼저 채운다) |
| `🛑 소유 경로 교집합` | 같은 wave 두 티켓이 같은 파일을 쓴다 — **분해를 고친다** |

소유 교집합은 `--allow-overlap` 으로 뚫을 수 있지만 **머지 충돌을 감수한다는 선언**이다.
사용자가 명시하지 않으면 쓰지 않는다.

### Step 3 — 사용자 확인

계획을 그대로 게시하고 확인을 받는다. 게시할 내용:

- 워크플로우 이름 + 쓰기/읽기 전용
- 티켓 wave 구성 (너비 포함)
- 만들어질 워크트리 경로와 브랜치
- **동시에 뜰 러너 프로세스 수** (= 첫 wave 너비) 와 그 안의 노드 폭

### Step 4 — 실제 실행

```bash
$V $W/<workflow>.toml --tickets <목록> \
  --set <공통 자리표시자> \
  --set-each spec_file=.agent/orchestration/runs/{ticket}/spec.json \
  --max-parallel 6
```

- `--set` — 전 티켓 공통 값 (`decisions_file=없음` 등)
- `--set-each` — 값의 `{ticket}` 이 티켓 키로 치환된다 (티켓별 산출물 경로)
- `--set ticket=<키>` 는 실행기가 자동으로 넣는다 — 직접 주지 않는다
- `--max-parallel` — 각 러너 **내부** 노드 폭 (5축이면 5, 6축이면 6)
- `--max-tickets` — 동시 티켓 상한. **기본 0(전량)을 그대로 쓴다.** 배치로 끊으면 벽시계만 늘어난다
- 출력을 `| tail` 로 가리지 않는다 — 티켓별 성공/실패가 실시간으로 보인다

먼저 `--dry-run` 을 붙이면 각 러너에 `--dry-run` 이 전달돼 **LLM 호출 0** 으로 자리표시자·
경로까지 검증된다. 유료 실행 직전에 한 번 돌릴 값이 있다.

### Step 5 — 게이트는 wave 단위로 묶어 검토

러너 하나가 게이트에 닿으면(exit 2) 그 티켓 wave 는 완료가 아니므로 **다음 wave 를 시작하지
않는다.** 실행기가 게이트 대기 티켓의 run-dir 을 나열한다.

- 티켓별로 따로 승인하지 않는다 — **wave 를 한 번에** 검토한다
- 재개는 각 워크트리에서 `run-graph.py --run-dir <그 run-dir> --start-at <다음 노드>`
- 종료 코드: `0` 전 티켓 완료 · `2` 게이트 대기 있음(정상) · `1` 실패 있음

## 산출물

| 무엇 | 어디 |
|---|---|
| 통합 매니페스트 | `.agent/orchestration/runs/wave-<타임스탬프>.json` |
| 티켓별 러너 로그 | `<워크트리>/.agent/orchestration/runs/<UND-NN>/wave-runner.log` |
| 티켓별 노드 산출물 | `<워크트리>/.agent/orchestration/runs/<UND-NN>/<node>.json` |

매니페스트의 `manifest` 필드가 `이번 실행이 쓰지 않음` 이면 그 티켓의 `run.json` 은 **이전
라운드 파일**이다 — 이번 결과로 읽지 않는다.

## 하지 않는 것

- ❌ 자동 발화 (티켓 N건 × 유료 호출 — 명시 호출 전용)
- ❌ 티켓 키·워크플로우를 추측해서 채우기
- ❌ Step 2 계획 검증 없이 실행
- ❌ 소유 교집합을 `--allow-overlap` 으로 임의 통과
- ❌ `--max-tickets` 로 배치 끊기 (사용자가 자원 한계를 명시한 경우만)
- ❌ 쓰기 워크플로우를 `--no-worktree` 로 실행 (실행기가 거부한다)
- ❌ 게이트 도달(exit 2)을 실패로 보고
- ❌ verdict 를 읽고 다음 워크플로우를 자동으로 고르기 — 워크플로우 경계는 사람이다
- ❌ 워크트리 삭제 (`git worktree remove`) — 사람의 미커밋 변경이 사라진다

## 관련

- [[custom-orchestrate]] — 워크플로우 1개 실행
- [[custom-develop-orchestrator]] — 티켓 1건 전체 루프 (게이트 2개)
- `.agent/orchestration/README.md` — 러너 옵션·프로필 라우팅·게이트
- `tickets/README.md` — 티켓 DAG·wave 배치 (의존은 각 티켓 md 헤더가 정본)
