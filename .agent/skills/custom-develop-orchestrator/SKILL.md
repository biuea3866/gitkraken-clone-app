---
name: custom-develop-orchestrator
description: >
  티켓(UND-NN) 1건을 스펙 → 구현 → 1차 5축 검증 → (필요 시) 수정·2차 검증 → 최종 요약까지
  한 루프로 도는 개발 워크플로우 오케스트레이터. DAG 실행·상태·산출물 전달은
  .agent/orchestration 러너가 맡고, 메인 Claude 는 대화·게이트·verdict 분기를 맡는다.
  체크포인트마다 터미널에 진행 로그를 남기고, 스펙 승인(③)·최종 검토(⑦) 두 게이트에서 멈춘다.
  Use when:
  - "UND-NN 작업해줘 / 구현해줘" 처럼 티켓 키 + 작업 의도가 들어올 때
  - 사용자가 "/custom-develop-orchestrator UND-NN" 호출
  - 티켓 1건을 처음부터 끝까지(스펙~Draft 직전) 한 흐름으로 진행하고 싶을 때
---

# custom-develop-orchestrator

티켓 1건을 7단계로 도는 개발 루프. **게이트는 2곳(③ 구현 승인 / ⑦ 최종 검토)** 이고, 나머지는 자동 진행한다.

## 역할 분담 (핵심)

| 주체 | 책임 |
|---|---|
| **메인 Claude (이 스킬)** | 대화·요구사항 수집·게이트 진행·**verdict 분기 판정**·체크포인트 게시 |
| **러너** (`.agent/orchestration/runner/run-graph.py`) | DAG 실행·wave 병렬·상태·산출물 파일 전달 |
| **claude 노드** (opus) | 구현·수정·재테스트 (workspace-write) |
| **codex 노드** (terra) | 읽기 전용 구조화 리뷰(5축)·문서 드리프트 점검 |

러너에는 **조건부 분기가 없다.** verdict 에 따라 어느 후속 워크플로우를 돌릴지는 **이 스킬이 판정**한다.

## 커뮤니케이션 원칙 (필수 — 모든 단계에 우선 적용)

모든 사용자 커뮤니케이션은 **터미널**로 한다. 체크포인트(📨#1~#4)·현황 보고(📊)·게이트(③·⑦) 승인 요청·
구현 중 발생하는 추가 질문·확인·결정 요청을 모두 터미널에 출력하고, 사용자 입력으로 응답을 받는다.

- 게이트(③·⑦)에서는 사용자 승인 전까지 다음 단계로 진행하지 않는다.
- 게이트에서 "수정 필요" 류 응답을 받으면 **선다형 재질문을 띄우지 말고** 수정 내용을 자유 입력으로
  받는다(수정 사유를 추측해 선택지로 나열하지 않는다).
- 스펙 외 변수·모호한 선택지가 생기면 임의 결정하지 말고 터미널로 묻는다.
- 체크포인트 게시는 정형 4회(📨#1~#4)지만, 사용자 현황 질의에 답하는 애드혹 📊 게시는 별개로 필요할 때마다 한다.
- **워크플로우 실행 전에 실행할 명령을 그대로 게시**하고, 실행 후 노드별 성공/실패·비용을 요약한다
  (러너가 wave 진행을 실시간 출력한다).

## 진행 표시 (필수 — 루프 내내 상시 노출)

루프가 도는 동안 **현재 어느 단계인지·진행률이 터미널 UI 에 항상 보여야** 한다. todo 리스트를
**단일 진행 표시기**로 쓴다(Claude Code 의 todo 패널이 터미널에 상시 렌더된다).

- **루프 시작 시(Step 0 직후·① 진입 전) 한 번** todo 리스트를 생성한다 — 아래 7개 항목 고정. 제목은
  `[n/7]  <단계명>` 형식으로 진행률(n/7)을 보이게 한다(동그라미 숫자 금지). **이모지는 사람 개입 지점에만**
  붙인다(💬 입력 / 🚦 게이트):
  1. `[1/7]  요구사항 수집`
  2. `[2/7] 💬  스펙 생성 (develop-1-spec)`  — 사람 입력
  3. `[3/7] 🚦  구현 승인 — 게이트`  — 승인
  4. `[4/7]  구현 + 1차 5축 검증 (develop-2-implement)`
  5. `[5/7]  verdict 분기 판정`
  6. `[6/7]  수정·재검증 또는 문서 점검 (develop-3-*)`
  7. `[7/7] 🚦  최종 검토 → Draft PR — 게이트`  — 승인
- **사람 개입 지점 표기 구분**:
  - **🚦 게이트(③·⑦)** = "승인해야 다음으로 진행". 승인 전 멈춘다.
  - **💬 입력 단계(②, 그리고 ① 은 커넥터 미연결 시 조건부)** = 사람과 Q&A 로 주고받지만 **승인이 아니라 구체화**다.
    ②에 별도 승인 게이트를 두지 않는다 — 스펙 승인은 바로 뒤 ③ 에서 한 번만 한다(중복 방지).
- **단계 전환마다 즉시 갱신**한다 — 진입하는 단계는 `in_progress`, 끝난 단계는 `completed`. 항상 정확히
  1개만 `in_progress` 로 유지한다.
- 게이트(③·⑦) 대기 중에는 해당 항목을 `in_progress` 로 둔다.
- todo 는 진행 표시 전용이다 — 체크포인트(📨#1~#4)·현황(📊) 게시를 **대체하지 않는다**(둘 다 한다).

## 검증 루프 상한 — 최대 3회

**같은 단계를 3회 넘게 반복하지 않는다.** 스펙 재생성·수정 라운드 모두 해당한다.

| 단계 | 상한 | 3회 도달 시 |
|---|---|---|
| 스펙 재생성 (`open_questions` 해소) | **3회** | 남은 질문을 **결정 문서로 답하고** 구현으로 넘어간다 |
| 수정 라운드 (`REQUEST_CHANGES`) | **3회** | 남은 finding 을 사람에게 보고하고 **판단을 요청**한다 |

**왜 상한을 두는가.** 라운드를 돌 때마다 질문의 질이 올라가지만 수렴하지는 않는다 —
답을 하나 주면 그 답이 새 질문을 만든다(UND-01 은 5라운드, wave 2 는 40건 → 19건).
어느 시점에는 **완벽한 스펙보다 실행해 보고 고치는 쪽이 싸다.**

**3회 도달 시 지켜야 할 것**

1. 남은 질문을 **결정 문서에 명시적으로 답한다** — 답 없이 넘기면 구현 노드가 추측한다.
2. 그 답을 `--set decisions_file=` 로 구현 워크플로우에 넘긴다.
3. **무엇을 미결로 남긴 채 진행하는지 사용자에게 보고한다.** 조용히 넘어가지 않는다.
4. 라운드 수를 `final_summary.rounds_run` 과 대조해 기록한다.

## when to use

- "UND-NN 작업해줘" — 티켓 1건 처음~끝 흐름
- `/custom-develop-orchestrator UND-NN`

## Step 0 — 사전 준비 (최초 확인)

1. **워크트리 준비 (필수)**: 메인 작업 디렉토리에서 구현하지 않는다.
   `git worktree add .worktrees/und-nn -b feat/UND-NN <short-desc>` (base 는 `origin/main`).
   이미 메인에 브랜치를 체크아웃해 뒀으면 메인을 원래 브랜치로 되돌리고(파킹한 변경은 stash 복원) 워크트리로 분리한다.
   - **워크플로우는 러너를 실행한 체크아웃의 워킹트리를 대상으로 돈다** (`cwd = "."` 가 그 체크아웃의
     루트로 해석된다). 따라서 **워크트리 안의 러너**를 호출한다:
     `.worktrees/und-nn/.agent/orchestration/runner/run-graph.py`
   - `--run-dir` 은 **절대경로 또는 메인 트리 경로**로 줘서 산출물이 한곳에 모이게 한다.
2. **CLI 확인**: `claude` · `codex` 둘 다 PATH 에 있어야 한다. 없으면 그 사실을 보고하고 멈춘다.
3. **run-dir 고정**: **티켓 1건 = run-dir 1개**. `.agent/orchestration/runs/<UND-NN>/` 로 정하고 전 단계가 이어 쓴다.
4. **게이트 종료 코드**: 게이트 도달 시 러너는 **exit 2** 로 끝난다. 실패가 아니다 — 0 만 성공으로 보지 않는다.

## 루프 단계

> 게이트(🚦)에서만 멈춘다. 구현·검증 라운드는 사람 승인 없이 자동 진행.

아래에서 `$R` = 워크트리의 러너 경로, `$W` = `.agent/orchestration/workflows`, `$RUN` = 이 티켓의 run-dir.

### ① 요구사항 수집  (메인 Claude)
- `tickets/UND-NN-*.md` 를 Read 하여 작업 내용·테스트 케이스를 파악한다. 티켓 md 가 없으면 사용자에게 요구사항을 묻는다.
- **요구사항·논의 결과를 파일로 고정**한다 (`.claude-local/UND-NN.md`). 헤드리스 노드는 대화 맥락을
  커넥터가 없으므로 티켓 조회는 여기서 끝내고 파일로 넘긴다.
- 입력이 모호하면 사용자에게 추가 질문을 던져 요구사항을 구체화한다.

### ② 스펙 생성  (develop-1-spec — evidence + spec + 게이트)
```bash
$R $W/develop-1-spec.toml --run-dir $RUN \
  --set ticket=UND-NN --set requirements_file=.claude-local/UND-NN.md \
  --set decisions_file=<결정 문서 경로 또는 없음>
```
- **`decisions_file` 을 빠뜨리지 않는다.** 스펙이 정정 이전 결정으로 AC 를 굳히면, 정정을 따른 구현을
  이후 5축이 **AC 위반으로 지적**한다 — 오탐이 파이프라인을 막는다 (wave 2 에서 UND-03·UND-07 이 그렇게 막혔다).
- `evidence`(claude/sonnet — 코드베이스 조사) → `spec`(codex/terra) → `approve_spec` 게이트에서 **exit 2**.
- 운영 근거가 필요 없는 티켓이라도 evidence 노드는 돈다 — "해당 없음" 을 근거로 남기게 되어 있다.

### 📨#1 — 스펙 확정  → 게이트 ③ 입력
- `$RUN/spec.json` 요약을 게시한다: 목표 · AC · 영향 모듈(디렉토리·JVM) · 테스트 계획 · **open_questions**.
- `open_questions` 가 비어 있지 않으면 **먼저 해소**한다 — 구현 노드는 비어 있지 않으면 구현하지 않고 멈춘다.
- `evidence.json` 이 요구사항과 다른 사실을 보고했으면 그 불일치를 함께 게시한다.

### ③ 구현 승인  🚦 (사람)
- 사용자 승인 전 구현 시작 금지.

### ④ 구현 + 1차 5축 검증  (develop-2-implement, 무승인 자동)
```bash
$R $W/develop-2-implement.toml --run-dir $RUN \
  --set ticket=UND-NN --set spec_file=$RUN/spec.json \
  --set decisions_file=<결정 문서 경로 또는 없음> --max-parallel 5
```
- wave 1 `implement_1`(codex/terra, workspace-write) → wave 2 5축 병렬(codex/terra) → wave 3 `review_summary_1`.
- **노드는 커밋하지 않는다.** 변경은 워크트리에 남는다 — 커밋은 게이트 ⑦ 이후 사람이 한다.
  쓰기 노드가 codex 라 `.claude/settings.json` 훅(push 차단·시크릿 가드·JDK 가드)이 **걸리지 않는다** —
  게이트에서 `git log`·`git status` 로 커밋이 생기지 않았는지 사람이 직접 확인한다.
- 장기 실행이므로 시작 전 예상 소요와 확인 방법을 공지한다. 러너 출력을 파이프로 가리지 않는다
  (노드별 성공/실패·비용이 실시간으로 보인다).
- `implement_1` 이 `blocked_by_open_questions` 로 끝나면 ②로 돌아간다.

### 📨#2 — 구현 + 1차 검증 결과
- `implement_1.json`(변경 파일·추가 테스트·빌드 결과·deviations) 과 `review_summary_1.json`
  (verdict · 레벨별 finding 수 · 수정 우선순위) 를 1회 게시한다.

### ⑤ verdict 분기 판정  (메인 Claude — 러너가 아님)
`$RUN/review_summary_1.json` 의 `verdict` 를 읽어 후속 워크플로우를 고른다.

```bash
verdict=$(python3 -c "import json;print(json.load(open('$RUN/review_summary_1.json')).get('verdict','REQUEST_CHANGES'))")
```

| verdict | 다음 |
|---|---|
| `REQUEST_CHANGES` (p0~p2 하나 이상) | `develop-3-repair.toml` — 수정 + 2차 6축 재검증 |
| `APPROVED` · `COMMENT` (p3~p4만 또는 없음) | `develop-3-approve.toml` — 2차 검증 생략, 문서 점검만 |

- **fail-closed**: 파일이 없거나 `_parse_error` 거나 `verdict` 키가 없으면 **REQUEST_CHANGES 로 취급**한다.
  판정 불가를 통과로 읽으면 검증을 건너뛴 채 게이트에 도달한다.
- 판정 근거(verdict · p0~p2 개수 · 선택한 워크플로우)를 터미널에 남긴다.
- p3~p4 는 자동 수정 대상이 아니다 — 최종 보고에만 남는다.

### ⑥ 수정·재검증 또는 문서 점검  (develop-3-*, 무승인 자동)

**REQUEST_CHANGES 경로**
```bash
$R $W/develop-3-repair.toml --run-dir $RUN \
  --set ticket=UND-NN --set spec_file=$RUN/spec.json \
  --set review_file=$RUN/review_summary_1.json \
  --set decisions_file=<결정 문서 경로 또는 없음> --max-parallel 6
```
wave 1 `repair_and_verify_2`(codex/terra) → wave 2 2차 6축 병렬(5축 + `docs_final_2`) → wave 3 `final_summary`
→ wave 4 게이트 **exit 2**.

**APPROVED · COMMENT 경로**
```bash
$R $W/develop-3-approve.toml --run-dir $RUN \
  --set ticket=UND-NN --set spec_file=$RUN/spec.json \
  --set review_file=$RUN/review_summary_1.json \
  --set decisions_file=<결정 문서 경로 또는 없음>
```
wave 1 `docs_final_1` → wave 2 `final_summary` → wave 3 게이트 **exit 2**.

- 문서 노드는 **최종 diff** 만 대상으로 하고 **제안만** 낸다 — 파일을 수정하지 않는다.
- `repair_and_verify_2.json` 의 `disputed`(수정에 동의하지 않은 finding) 는 2차 축 노드가 타당성을 판정한다.
- 2차에서도 `remaining_blocking` 이 남으면 **묻지 말고 3회전을 한 번 더 돌린다** (상한 3회 이내).
  같은 워크플로우를 `--run-dir` 유지로 다시 실행하되, `--set review_file=$RUN/final_summary.json`
  으로 **직전 판정**을 넘긴다. 게이트에서만 멈춘다는 원칙이 여기에도 적용된다 —
  자동으로 돌 수 있는 라운드를 사람에게 묻는 것은 루프를 거기서 끊는 것과 같다
  (wave 2 에서 6건이 2회전 상태로 사람에게 넘어와 손으로 고쳤다).
- **3회전 후에도 남으면** 그때 사람에게 보고하고 판단을 요청한다 (상한 규칙).
- 3회전 전에 finding 을 살펴 **오탐이 반복되고 있으면** 라운드를 더 돌리지 말고 멈춘다 —
  같은 지적이 반복되는 것은 스펙·결정 문서가 어긋났다는 신호다. `.agent/docs/review-false-positives.md`
  대조 후 스펙을 고치는 쪽이 싸다.

### 📨#4 — 최종 요약 + 문서 갱신 제안  → 게이트 ⑦ 입력
- `final_summary.json`: verdict · `rounds_run` · `remaining_blocking` · `advisory`(p3~p4) · AC 충족 ·
  `pr_ready` · `next_steps_ko`.
- `docs_final_*.json`: 문서 갱신 **diff 제안** (승인 시 반영, 거부 시 미반영).
- **memory** (개인 자산)는 예외로 **직접 갱신**한다 — 제안/승인 불요, PR 에 안 들어감(레포 밖).
  `MEMORY.md` 인덱스 + 파일1=사실1 규칙 준수.
- 그 외 모든 문서(도메인/컨텍스트 + 하네스/팀가이드, 전부 tracked)는 **자동 수정 금지 — 제안만**.
  PR 포함 여부는 "코드유발이냐" 가 아니라 **사용자 승인**이 결정한다.

### ⑦ 최종 검토  🚦 (사람)
- **코드 확인 환경 마련 여부 질문**(필수): "변경 코드를 직접 확인할 수 있게 IDE 환경(IntelliJ IDEA/VS Code 등)을
  마련할지" 묻는다 — 워크트리/브랜치 포함. IDEA 선택 시 ④의 워크트리 디렉토리를 직접 연다
  
- **문서 제안 승인 처리**: 사용자가 승인한 항목만 워크트리 브랜치에 커밋해 **해당 PR 에 포함**한다.
  코드 커밋과 논리적으로 분리(별도 커밋)하되 같은 PR 에 싣는다.
- **게이트에서 코드를 고쳤다면 ⑧ 재검증을 먼저 돌린다** — 사람이 넣은 수정(오탐 기각·설계 결정 반영·
  직접 패치)은 어떤 축 노드도 보지 않은 상태다. 문서만 고쳤으면 ⑧을 건너뛴다.
- **커밋은 사람 승인 후 여기서 처음 일어난다** — 노드는 커밋하지 않았다.
  접두사 `[UND-NN] - <type>: <제목>`.
- **PR 은 명시적 확인 후에만 생성**: IDE 를 열어준 직후 곧장 만들지 않는다. **"확인되었습니까? Draft PR 올릴까요?"**
  를 묻고 응답을 기다린다 — 게이트 승인 ≠ PR 즉시 생성.
- 확인을 받은 뒤에만 [[custom-pr-create]] 로 `--draft` PR 생성.
- 이후부터 CI 트리거(base 가 `main`/`stage-*` 일 때 — `dev` 브랜치는 사용하지 않는다).

### ⑧ 사람 수정분 재검증  (develop-4-verify — ⑦에서 코드를 고쳤을 때만)
```bash
# 축 노드는 사람의 터미널을 볼 수 없다 — 빌드 증적을 파일로 남겨 넘긴다.
(cd <워크트리> && ./gradlew build > $RUN/human-build.log 2>&1)

$R $W/develop-4-verify.toml --run-dir $RUN \
  --set ticket=UND-NN --set spec_file=$RUN/spec.json \
  --set review_file=$RUN/final_summary.json \
  --set decisions_file=<결정 문서 경로 또는 없음> \
  --set build_log=$RUN/human-build.log --max-parallel 6
```
- 수정 노드가 없다. **지금 워킹트리의 diff** 를 5축 + 문서 드리프트로 다시 본다 → `final_summary_v.json`
  → `verify_and_draft` 게이트 **exit 2**.
- `remaining_blocking` 이 비어야 커밋·PR 로 간다. 남으면 고치고 다시 ⑧을 돌린다.
- **⑧을 다시 돌릴 때는 직전 판정을 먼저 스냅샷**한다 — `final_summary_v.json` 은 이 워크플로우가
  덮어쓰므로 그대로 `review_file` 로 넘기면 노드가 읽지 못한다:
  `cp $RUN/final_summary_v.json $RUN/final_summary_v.prev.json` 후 그 경로를 넘긴다.
- 축 노드가 **사람이 기각한 finding 을 다시 올리면**, 기각 근거를
  `.agent/docs/review-false-positives.md` 에 항목으로 남겨 다음 라운드부터 재발을 막는다.
  단 그 문서는 **검증 대상 브랜치의 체크아웃**에서 읽힌다 — 하네스 브랜치에만 넣어 두면 그 항목이
  main 에 들어오기 전까지 같은 지적이 계속 올라온다. 재발 횟수를 새 근거로 착각하지 않는다.

## 안티패턴

- ❌ ③ 승인 전에 구현 시작
- ❌ **verdict 판정을 러너에 기대** — 조건부 분기는 러너에 없다. ⑤에서 이 스킬이 판정한다
- ❌ **판정 불가를 통과로 처리** — 산출물 없음·파싱 실패는 REQUEST_CHANGES (fail-closed)
- ❌ p3~p4 자동 수정 (스코프 확산 — 최종 보고에만 남긴다)
- ❌ 게이트 exit 2 를 실패로 오인해 루프 중단
- ❌ 메인 작업 디렉토리에서 구현 (항시 워크트리)
- ❌ 노드가 커밋·push·Draft PR 수행 (전부 게이트 ⑦ 이후 사람 몫)
- ❌ 커밋마다 게시 (정형 체크포인트는 4회 — 애드혹 📊 게시는 별개로 허용)
- ❌ 문서·하네스 문서를 ⑥에서 자동 수정 (제안만 — memory 만 자동)
- ❌ ⑦ 에서 IDE 만 열어주고 "확인되었습니까?" 없이 곧장 PR 생성
- ❌ **게이트에서 코드를 고쳐 놓고 ⑧ 재검증 없이 커밋·PR** — 무인 5축은 수정 **이전** 상태만 봤다
- ❌ **PR 체크리스트에 셀프 리뷰 완료 표기** — 실제로 그 상태를 본 축 노드나 자가 리뷰가 없을 때
- ❌ feature 브랜치끼리 PR 만들고 CI 도는 것으로 기대 (CI 는 `main`/`stage-*` base 만)

## 관련

- [[custom-self-code-review]] — 5축 정의의 SSOT (축 노드에 `role_file` 로 주입된다)
- [[custom-pr-create]] — ⑦ Draft PR 본문
- `.agent/orchestration/README.md` — 러너·게이트·재개·벤더 라우팅
- `.agent/docs/review-grading.md` — p0~p5 등급·verdict 산출·fail-closed
