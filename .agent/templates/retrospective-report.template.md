---
job: {job-name}
date: {YYYY-MM-DD}
workflow: {analysis | development}
phases:
  - {예: analysis (scope A/B/C parallel)}
---

# 회고: {job-name}

## Job Summary
- Job: {job-name}
- Duration: {start ~ end UTC, 총 wall-clock}
- Agents involved: {예: orchestrator, custom-research-investigator x3 (병렬), custom-analysis-synthesizer}
- Review loops: {N회 / Analysis 잡엔 없음 — Development 이식 시 적용}
- 연쇄 조사: {N회 / Analysis 에만 해당}

---

## A. 프로세스 회고 (Workflow Level)

### 잘된 점
1. ...

### 아쉬운 점 / 개선 가능
1. ...

### 개선 제안
1. **{한 문장 제안}** — 근거: {collaboration.log / context 산출물}에서 관찰된 패턴. 예상 효과: {...}

---

## B. 에이전트 협업 회고 (Agent Level)

| 에이전트 | Status | 주요 기여 | Difficulties 수집 |
|----------|--------|----------|-------------------|
| custom-research-investigator-1 | DONE | ... | - |
| custom-analysis-synthesizer | DONE | ... | [FRICTION] x1 |

### 에이전트별 노트
- **{agent-name}**: {특이점, 개선 포인트}

---

## C. 잠정 규칙 모니터링

워크플로우의 잠정 한도(연쇄 조사 최대 1회 / REQUEST_CHANGES 최대 2회)의 임계 도달 여부를 집계한다.

| 규칙 | 임계값 | 이번 Job 관측 | 누적 관측 |
|------|--------|-------------|---------|
| REQUEST_CHANGES 최대 2회 (Development 이식 시) | 2회 초과 시 재검토 | {N회} | 최근 5건: {...} |
| 연쇄 조사 최대 1회 | 2회 초과 시 재검토 | {N회} | 최근 5건: {...} |

**재검토 트리거**: 임계 도달 3회 누적 시 해당 한도(analysis-workflow.md)의 재평가를 개선 제안에 Priority HIGH 로 올린다.

---

## D. Harness 개선 제안

다음 작업에서 반영 가능한 구체적 변경사항 (`.agent/` 하네스 파일 기준):

- [ ] **{변경 대상 파일}**: {변경 내용} — 근거: {Difficulties 항목 / 관찰}
- [ ] `.agent/agents/{agent}.md`: ...
- [ ] `.agent/docs/analysis-workflow.md` / `CLAUDE.md` 라우팅 블록: ...

---

## E. collaboration.log 발췌 (선택)

주요 이벤트 3~5줄.

```
[timestamp] actor | STATUS | summary
```
