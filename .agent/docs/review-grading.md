# 리뷰 등급 (p0~p5) 과 verdict — in-repo 정본

코드 리뷰 지적의 심각도 등급과 verdict 산출 규칙. `.agent/orchestration/workflows/develop-*.toml`
의 리뷰 노드, `.agent/skills/custom-self-code-review`, `.agent/skills/custom-pr-review` 가
**같은 기준**을 쓰도록 여기에 단일 정의를 둔다 (clone 단독 동작 — 개인 글로벌 설정에 의존하지 않는다).

## 등급

| 레벨 | 기준 | verdict 영향 |
|---|---|---|
| **p0** | 보안 취약점 · 데이터 손실 · 크래시 · 인증/인가 우회 | REQUEST_CHANGES |
| **p1** | 레이어 의존 위반 · 컨벤션 위반(`.agent/rules/`) · 프로세스 게이트 위반(`.agent/docs/conventions.md`) · 설계-코드 불일치 | REQUEST_CHANGES |
| **p2** | 신규 비즈니스 로직에 테스트 없음 · 과대 메서드 · 변수명 축약 · 조용한 실패(예외 삼킴·부적절한 fallback) | REQUEST_CHANGES |
| **p3** | 네이밍 nit · 포맷 · 사소한 가독성 개선 | COMMENT |
| **p4** | 대안 제안 (현재 코드도 무방) | COMMENT |
| **p5** | 정보성 코멘트 (액션 불필요) | — |

## verdict 산출

```
p0~p2 가 하나라도 있으면        → REQUEST_CHANGES
p3~p4 만 있으면                 → COMMENT
p0~p4 가 없으면                 → APPROVED
```

`.agent/orchestration/schemas/review.json` 의 `verdict` enum 이 이 규칙의 기계 표현이다.

## 판정 규율 (모든 리뷰 주체 공통)

1. **변경 파일 전수 읽기.** 요약·추측으로 판정하지 않는다.
2. **근거 필수.** 모든 finding 에 `파일:라인` 과 구체 패턴을 적는다. "위험해 보임" 류 금지.
3. **오탐 SSOT 선행 대조.** finding 을 적기 전에 [`review-false-positives.md`](./review-false-positives.md)
   를 읽고, 해당하는 패턴이면 기각 또는 강등한다 (diff 밖 단정 · nullable 자체 지적 ·
   soft-delete/`@TransactionalEventListener(AFTER_COMMIT)`/`PESSIMISTIC_WRITE` 같은 의도된 컨벤션).
4. **컨텍스트를 읽었다면 상향.** 스펙·티켓 AC 를 읽은 상태에서 발견한 코드-설계 불일치는 p1 이상이다.
5. **자동 수정 대상은 p0~p2 뿐.** p3~p4 는 보고에 남기고 자동으로 고치지 않는다 (스코프 확산 방지).

## 판정 불가 시 (fail-closed)

리뷰 산출물이 없거나 파싱 실패(`_parse_error`)거나 `verdict` 키가 없으면 **REQUEST_CHANGES 로
취급**한다. 판정 불가를 통과로 읽으면 검증을 건너뛴 채 사람 게이트에 도달한다.

## 참고

- [`review-discipline.md`](./review-discipline.md) — 리뷰 게이트·출력·브리핑 규율
- [`review-false-positives.md`](./review-false-positives.md) — 오탐 패턴 카탈로그 (기각·강등 기준)
- [`conventions.md`](./conventions.md) — 프로세스/머지 게이트 (p1 판정 근거)
- `.agent/rules/` — 코드 작성 규칙 (p1·p2 판정 근거)
