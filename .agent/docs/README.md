# .agent/docs — 컨벤션 및 SSOT 문서

본 디렉토리는 **읽기 전용 참조** — agents/skills 가 인용하는 단일 출처다.

## 문서 목록

| 파일 | 역할 |
|---|---|
| `conventions.md` | 프로세스 게이트 4개 룰 (JDK 정합, 티켓 접두사, 파일 소유, 파괴적 변경 명시) |
| `ssot-map.md` | 어디서 무엇이 단일 진실 공급원인지 — 의심나면 가장 먼저 조회 |
| `analysis-workflow.md` | Analysis Workflow 절차 정본 (Step 1~7, 배치 모드, 검증 게이트) |
| `collaboration-protocol.md` | Job workspace·collaboration.log 형식·산출물 규약 (single-writer 모델) |
| `job-lifecycle.md` | 잡 라이프사이클·archiving·CLOSE 게이트 |
| `review-grading.md` | 리뷰 등급(p0~p5)·verdict 산출·판정 규율·fail-closed 정본 |
| `review-false-positives.md` | 리뷰 오탐 패턴 카탈로그 (FP-ID) — 게이트를 통과해도 여기 걸리면 기각 |
| `pipeline-tuning.md` | 파이프라인 실행 시간 튜닝 — 측정값·적용한 조치·품질 때문에 하지 않은 것 |
| `review-discipline.md` | 리뷰 규율 정본 — 검산 게이트·출력 템플릿·PR 이해 브리핑·보안/메모리 릭 체크리스트 |

## 의존 방향

```
agents/skills           ──읽기──>  docs/conventions.md
agents/skills           ──읽기──>  docs/ssot-map.md
분석워크플로우 에이전트  ──읽기──>  docs/{analysis-workflow,collaboration-protocol,job-lifecycle}.md
리뷰 주체 전체          ──읽기──>  docs/{review-grading,review-false-positives,review-discipline}.md
```

`docs/` 는 다른 곳으로 **쓰지 않는다**. 컨벤션 변경 PR 은:
1. `conventions.md` 수정
2. 영향받는 agent/skill 의 인용 부분 갱신
3. `ssot-map.md` 의 매핑 갱신
