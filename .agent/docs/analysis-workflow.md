# Analysis Workflow

이 프로젝트 안에서 자체 구동하는 분석 워크플로우의 절차 정본이다.
오케스트레이터(메인 세션)와 `custom-research-investigator` / `custom-analysis-synthesizer` / `custom-retrospective-analyst` 에이전트가 이 절차를 따른다.

> 협업 규약(workspace·로그 형식·산출물)은 [`collaboration-protocol.md`](collaboration-protocol.md), 라이프사이클·아카이브는 [`job-lifecycle.md`](job-lifecycle.md) 참조.

---

## 분기 규칙 (Analysis vs Development)

요청을 받으면 먼저 **Analysis** 인지 **Development** 인지 판단한다.

- **Analysis**: "분석해봐", "조사해줘", "파악해줘", "찾아봐", "확인해봐", "가능한지 확인", "원인이 뭔지", "왜 발생하는지", "전체 흐름", "어떻게 동작하는지" 등 코드 변경 없이 정보 수집/결론 도출이 목적인 경우 → 본 워크플로우.
- **Development**: "수정해줘", "구현해줘", "추가해줘", "만들어줘", "리팩토링해줘" 등 코드 변경이 목적인 경우 → 본 워크플로우 범위 밖(별도 Development 잡).

모호한 경우 사용자에게 묻지 않고 요청의 주된 의도에 따라 오케스트레이터가 판단한다.

---

## Analysis Workflow

```
Step 1  오케스트레이터             분석 질문 이해 · 조사 범위 분할 · investigation-brief 작성
Step 2  [사용자 승인]              조사 범위와 방향 확인
Step 3  custom-research-investigator   각 범위별 병렬 조사 (N개 병렬)
Step 4  분기
        ├─ 추가 조사 불필요   → Step 4.5
        └─ 새 범위 발견       → Step 3 (추가 brief 작성, 최대 1회)
Step 4.5 검증 게이트 (orchestrator) Step 5 위임 전 finding 파일 존재 확인
Step 5  custom-analysis-synthesizer    발견 종합 · 최종 분석 보고서 (Implementation Bridge + Development Handoff 포함)
Step 6  분기
        ├─ 분석 완료          → Step 7
        └─ 코드 변경 필요     → Step 7 후 → Development 잡으로 전환 (동일 job 재사용)
Step 7  custom-retrospective-analyst   프로세스 회고 및 개선 제안 (job close)
```

오케스트레이터는 Step 1에서 `context/investigation-brief-{N}.md`를 직접 작성한다. 연쇄 조사는 **최대 1회**, 이후 사용자에게 판단 위임. 분석→구현 전환 시 `## Implementation Bridge` + `## Development Handoff` 가 연결점이며, **동일 job 을 재사용**한다.

Step 5 synthesizer 위임 시 오케스트레이터는 프롬프트에 `job-name` 과 아카이브 날짜(YYYY-MM-DD)를 **반드시 포함**한다 — 누락 시 synthesizer 가 `analysis/` 아카이브 경로를 구성하지 못해 9/9 미실행(2026-06-16 doc-verify 배치)이 재발한다. CLOSE 전 아카이브 존재 검증은 [`job-lifecycle.md`](job-lifecycle.md) §3.

### 배치 모드 (동일 패턴 N잡 일괄 처리)

동일 패턴의 잡 N개를 연속 처리하는 배치(예: 모듈별 문서 검증 9잡)에서는 다음을 허용한다:

- **일괄 승인**: 첫 잡에서 사용자가 패턴을 승인하면 나머지 잡의 Step 2 를 생략할 수 있다. 각 잡 collaboration.log APPROVED 에 "배치 일괄 승인 (N잡)" 으로 명시.
- **배치 회고**: 개별 잡 Step 7 을 스킵하고, 배치 종료 시 `_<prefix>-retrospective` 회고 잡 1개로 대체한다. 각 잡 CLOSE 에 "회고: 배치 위임" 1줄 명시.

배치 모드로 처리한 잡은 개별 9-이벤트 완전성·개별 Step 7 누락을 정본 위반으로 보지 않는다 (job-lifecycle §3 배치 예외). 단 **synthesizer 아카이브(`analysis/`)는 배치에서도 잡별로 필수**다.

### 병렬 실행 규칙 · 위임 필수 항목

Step 3 의 N개 investigator 는 **하나의 메시지에 여러 Agent tool 호출**로 동시에 dispatch 한다 (직렬 호출 금지).

**DISPATCHED 직전 brief 파일 게이트**: dispatch 직전 오케스트레이터는 `ls context/investigation-brief-*.md` 로 brief 파일이 **실제로 존재**하는지 1회 확인한다. BRIEF 로그만 기록되고 파일이 없으면(2026-06-16 payment 잡) investigator 가 brief 없이 도는 비정상 상태가 되므로, 없으면 brief 를 재Write 후 진행한다. (Step 4.5 게이트는 조사 *후* 라 investigator 의 brief-less 실행을 막지 못한다 — 본 게이트가 사전 차단.)

**위임 프롬프트 필수 3종**: (a) brief 절대경로, (b) Scope ID + topic, (c) job workspace 절대경로. 산출물 파일명 prefix 규칙(`scope-*` 안전, `findings/analysis/summary/report` 시작 금지)은 [`custom-research-investigator.md`](../agents/custom-research-investigator.md) 에 2026-06-11 probe 로 확정·문서화되어 있으므로 **오케스트레이터가 위임 시 재명시할 필요가 없다** — 에이전트 정의를 신뢰한다.

---

## Step 4.5 검증 게이트 (필수)

Step 5 (synthesizer) 위임 직전에 오케스트레이터는 **반드시** `ls context/` 로 brief 에 정의한 finding 파일 N 개의 존재를 확인한다.

> investigator 산출물 파일명이 prefix 규칙(`scope-*` — basename 을 `findings`/`analysis`/`summary`/`report` 로 시작하지 않음)을 지키면 서브에이전트가 정해진 경로에 **직접 Write** 하므로 정상적으로 파일이 존재한다. 본 게이트는 에이전트 크래시·인라인 출력 누락 등 **예외 상황의 안전망**이다.

누락 시 다음 중 1 가지를 선택한다:

1. **Rescue Write**: 에이전트 응답에 인라인 출력된 finding 본문이 보전되어 있다면 orchestrator 가 직접 Write 로 보존. collaboration.log 에 `RESCUE-{ID}` 로 기록.
2. **재위임**: 인라인 출력도 부재한 경우 동일 scope 를 재위임 (`SendMessage` 로 기존 agent 컨텍스트 활용 우선).
3. **BLOCKED**: 재위임도 실패한 경우 BLOCKED 종료 후 사용자 판단 위임.

배경: 2026-05-15 search-pipeline 잡 Scope γ 에서 agent 가 30KB 인라인 출력했지만 Write 호출 누락. orchestrator 의 `ls context/` 1 회로 발견하여 rescue 처리 가능했음. 본 게이트가 없으면 synthesis 가 missing finding 으로 부분 결과 도출 위험.

---

## Step 1 사전 절차 — 조사 대상 위치 확정

brief 의 Scope 분할 전에 **요청이 다루는 개념의 핵심 키워드를 1회 grep** 하여 실제 코드 위치를 확정한다.

절차:

1. 요청에서 핵심 키워드 2~3개 추출 (예: "레인 배치", "hunk 스테이징", "원격 인증")
2. `src/` 하위를 키워드로 1회 검색 — 내장 **Grep 도구** (pattern=`<keyword>`, `-i`, output_mode=files_with_matches).
   - **0건이어도 "부재" 로 단정하지 않는다** — 이름이 다를 수 있다. 상위 개념(예: `graph`·`stage`·`credential`)으로 **1회 재grep** 한다.
   - 레이어별로 어디에 걸리는지 기록한다 — `domain` 에만 걸리면 순수 로직, `infrastructure` 에 걸리면 JGit 경계다.
3. 결과를 brief 의 "사전 확인된 사실" 섹션에 **절대경로:라인** 또는 부재 사실로 기재한다.
   선행 finding 인용에 의존하지 말고 직접 검증한다.
4. 조사 범위가 명확해지면 그에 맞춰 Scope 를 분할한다.

이 절차를 생략해 조사 위치가 잘못 식별되면 "연쇄 조사 최대 1회" 한도가 강제로 소진된다.


## Step 1-a. 경계 확인 — 코드만으로 단정하지 않을 지점

조사 대상이 **JGit 실제 동작에 의존**하면 코드 읽기만으로 결론내지 않는다.

- 빈 저장소·detached HEAD·병합 커밋·고아 브랜치에서의 동작은 **임시 저장소를 만들어 확인**한다.
- 라이브러리 버전별 차이가 의심되면 `gradle/libs.versions.toml` 의 실제 버전을 근거로 남긴다.

## Step 1-b. 활성도 판단 — 호출 경로로만

"이 코드가 실제로 쓰이는가" 는 추측이 아니라 **호출 경로**로 판단한다.

1. Gateway interface → 구현체 → UseCase → 화면까지 이어지는지 grep 으로 확인한다.
2. 중간이 끊겨 있으면 미사용으로 분류하고, **끊긴 지점을 근거로 남긴다**.
3. Compose 화면은 진입점(네비게이션·상위 Composable)에서 실제로 호출되는지까지 확인한다.

## Step 1-c. 사용자 전제 검증 grep (전제가 포함된 분석 잡에서 필수)

사용자 요청에 "X 는 Y 다" 형태의 전제가 포함되면, brief 작성 전에 **그 전제를 1회 grep 으로 검증**한다.
전제가 틀린 채 조사가 시작되면 모든 finding 이 함께 틀린다. 검증 결과(참/거짓/불명)를 brief 에 명시한다.


## 추가 요청의 Scope 통합 vs 분리 기준

분석 진행 중 사용자가 추가 요청을 하면 기존 Scope 의 sub-task 로 통합할지 독립 Scope 로 분리할지 다음 기준으로 판단:

- **통합 (기존 Scope 의 sub-scope 추가)**: 조사 대상 파일/repo 가 기존 Scope 와 동일 + 예상 finding 분량이 기존 Scope 전체의 30% 미만
- **독립 Scope 로 분리**: 조사 대상이 새 영역 + 예상 분량이 50% 이상 + 병렬 dispatch 추가가 가능한 상황

---

## collaboration.log 필수 기록 이벤트

다음 이벤트가 모두 collaboration.log 에 남아야 한다 (누락 시 회고에서 워크플로우 추적 불가). **기록은 오케스트레이터 단독**이다 (single-writer — [`collaboration-protocol.md`](collaboration-protocol.md) §형식). 아래 6·8 처럼 actor 가 에이전트인 줄도 **행위 주체가 에이전트일 뿐 물리적 기록자는 오케스트레이터**다 — 에이전트는 DONE 요약을 응답으로 반환하고 오케스트레이터가 받아 기록한다:

```
1. [ts] orchestrator | START      | job=<job-name> workflow=analysis
2. [ts] orchestrator | BRIEF      | investigation-brief-{N}.md 작성 완료
3. [ts] orchestrator | UPDATE     | brief 갱신 (사용자 추가 요청 반영 — 있을 때만)
4. [ts] orchestrator | APPROVED   | 사용자 승인 (Step 2 통과, 내용 1줄 요약)
5. [ts] orchestrator | DISPATCHED | investigator-{A/B/C/D} 병렬 dispatch
6. [ts] custom-research-investigator-{X} | DONE | finding 산출 (N 사실, 1줄 요약)
6.5 [ts] orchestrator | ACCEPTED  | finding 검증 완료 (Step 4.5 게이트 통과 — 라운드별 기록)
7. [ts] custom-analysis-synthesizer  | DONE | synthesis-report.md 산출 (섹션 수, OQ 수)
8. [ts] custom-retrospective-analyst | DONE | retrospective 작성 완료
9. [ts] orchestrator | CLOSE      | 잡 종료
```

ISO8601 UTC 타임스탬프 강제. ACCEPTED 는 해당 라운드의 모든 병렬 investigator DONE 수신 + `ls context/` 파일 확인 직후에 기록한다 (사후 일괄 기록 금지).

---

## 기존 점도구와의 역할 경계

기존 analysis 성격 에이전트와 본 워크플로우는 **공존(보완)** 한다 — 대체하지 않는다.

| 도구 | 역할 | research-investigator 와의 경계 |
|------|------|------|
