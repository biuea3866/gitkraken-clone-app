# .agent/agents — 서브에이전트 (8개)

특정 축을 깊게 보는 전문 에이전트. 전부 **읽기 전용**이며 코드를 수정하지 않는다 — 조사·리뷰·초안만 낸다.

`.claude/agents/` (Claude Code) 와 `.codex/agents/*.toml` (Codex) 은 여기서 생성되는 투영본이다.
본문을 고치면 `.agent/tools/sync-vendors.py` 로 재생성한다.

## 목록

| 에이전트 | 역할 | 언제 |
|---|---|---|
| [`custom-kotlin-desktop-engineer`](custom-kotlin-desktop-engineer.md) | Kotlin·Compose Desktop·JGit 작성 가이드 + 정적 리뷰 | 신규 클래스 배치 결정, 변경 코드 리뷰 |
| [`custom-pr-call-graph-reviewer`](custom-pr-call-graph-reviewer.md) | 변경 파일 밖 1-2 hop 호출 영향·interface↔구현 비대칭 | PR 리뷰 시 파급 범위 의심 |
| [`custom-silent-failure-hunter`](custom-silent-failure-hunter.md) | 예외 삼킴·부적절한 fallback·취소 미전파 전담 | push 전 에러 처리 점검 |
| [`custom-design-doc-author`](custom-design-doc-author.md) | 기술 설계 문서(TDD) 초안 | 신규 기능 착수 |
| [`custom-ticket-decomposer`](custom-ticket-decomposer.md) | 티켓 분해 + 의존 DAG + wave 너비 검증 | 설계 확정 후 |
| [`custom-research-investigator`](custom-research-investigator.md) | 범위 한정 조사, 병렬 실행 | 분석 워크플로우 조사 단계 |
| [`custom-analysis-synthesizer`](custom-analysis-synthesizer.md) | 여러 조사 finding 교차 종합 | 조사 완료 후 결론 |
| [`custom-retrospective-analyst`](custom-retrospective-analyst.md) | 완료 job 회고 → 하네스 개선 제안 | 워크플로우 마지막 단계 |

## 공통 규율

- **읽기 전용.** 코드 수정은 메인 세션 또는 구현 노드가 한다.
- **근거 필수.** 모든 지적에 `파일:라인` 을 남긴다. "위험해 보임" 류 금지.
- **오탐 SSOT 선행 대조.** [`docs/review-false-positives.md`](../docs/review-false-positives.md) 에 해당하면 기각·강등.
- **등급·verdict 는 단일 기준.** [`docs/review-grading.md`](../docs/review-grading.md).
