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

## 차단 판정의 전제 — diff 인과성 (수렴 규칙)

**등급과 차단은 다르다.** p0~p2 라는 등급은 "얼마나 심각한가" 이고, verdict 차단은 "이 티켓이
그것 때문에 못 나가는가" 다. 둘을 같은 것으로 취급하면 검증 라운드가 수렴하지 않는다 — 고칠
때마다 그 옆의 기존 코드가 새로 보이고, 라운드마다 새 차단이 생겨 끝나지 않는다.

아래는 **차단(REQUEST_CHANGES)으로 올릴 자격**의 전제다. 자격이 없으면 등급은 그대로 매기되
`findings` 가 아니라 **`advisory`** 에 넣고, 필요하면 후속 티켓 제안을 함께 적는다.

| # | 전제 | 자격 없을 때 |
|---|---|---|
| C1 | **이번 diff 가 원인이다.** 변경된 라인이 그 결함을 만들거나 도달 가능하게 했고, 그 라인을 근거로 인용할 수 있다. | 변경되지 않은 라인만 인용된다면 **기존 결함**이다 → `advisory` + `(기존)` 마커 + 후속 티켓 제안 |
| C2 | **고칠 수 있다.** 제시하는 수정이 이 언어·플랫폼·이 티켓이 소유한 계약 안에서 달성 가능하다. | 플랫폼이 제공하지 않는 보장(예: 경로 기반 TOCTOU 완전 제거 — fd 보유 `openat` 계열 필요)이나 남의 티켓 계약 변경을 요구하면 → `advisory` 에 **잔존 위험과 필요 조건**을 적는다 |
| C3 | **닫힌 부류를 다시 열지 않는다.** 어떤 결함 부류의 *이 diff 안 사례*가 모두 처리됐으면 그 부류는 이 티켓에서 닫힌다. | 같은 부류를 다른 대상으로 확장해 재차 차단하지 않는다 → `advisory` |
| C4 | **결정 문서가 정한 것을 재론하지 않는다.** 사람이 결정 문서(`.claude-local/*-DECISIONS.md` 등)로 확정한 범위·설계는 finding 이 아니다. | 오탐으로 기각하고 근거 결정 항목을 인용한다 |
| C5 | **레포 파일만 대상이다.** 오케스트레이션 산출물(`.agent/orchestration/runs/**`)은 프로세스 상태이지 리뷰 대상이 아니다. | 산출물 자체를 finding 대상으로 삼지 않는다 |

### 테스트 지적의 경계 (C6)

테스트 finding 은 **이 diff 가 만든 동작 중 검증되지 않은 것**을 지목해야 p2 다.
이미 테스트가 있는 동작에 단정을 더 붙이라는 요구는 **p3** 이며 차단하지 않는다 —
그 요구는 라운드마다 무한히 깊어질 수 있다.

### 왜 이 규칙이 필요한가 (관측)

wave 3 재검증에서 한 티켓에 5라운드가 돌았고, 2라운드 이후는 전부 위 전제를 못 갖춘
지적이었다: 이 티켓이 만들지 않은 시작 경로(C1), 달성 불가한 TOCTOU 제거 요구(C2),
같은 가드를 대상만 바꿔 재요구(C3), 결정 문서가 승인한 소유 범위 재론(C4),
자기 산출물 참조(C5), 이미 테스트된 동작의 단정 추가(C6).

> **수렴 의무**: 라운드가 진행될수록 finding 은 **줄어야** 한다. 직전 라운드에 없던 차단을
> 올릴 때는 그것이 **직전 수정이 새로 만든 회귀**임을 변경 라인으로 보여야 한다.
> 보일 수 없으면 `advisory` 다.

## 판정 불가 시 (fail-closed)

리뷰 산출물이 없거나 파싱 실패(`_parse_error`)거나 `verdict` 키가 없으면 **REQUEST_CHANGES 로
취급**한다. 판정 불가를 통과로 읽으면 검증을 건너뛴 채 사람 게이트에 도달한다.

**이 규칙은 `verdict` 를 선언하는 스키마의 산출물에만 적용된다** — `review.json` ·
`final-summary.json` · `ticket-review.json` 이다. **축 산출물(`axis-review.json`)에는 `verdict`
필드가 없다**: 축은 판정을 내리지 않고 `findings` 를 올리며, 판정은 그것을 모으는 요약 노드가 한다.
축 산출물에 `verdict` 가 없다는 것을 fail-closed 사유로 올리지 않는다 — 스키마가 그렇게 정의돼 있어
**모든 축이 영구히 걸리고**, 코드에 지적이 없는 티켓도 APPROVED 에 도달할 수 없다.
축의 판정 불가는 파일 부재 또는 `_parse_error` 로만 판단한다.

## 참고

- [`review-discipline.md`](./review-discipline.md) — 리뷰 게이트·출력·브리핑 규율
- [`review-false-positives.md`](./review-false-positives.md) — 오탐 패턴 카탈로그 (기각·강등 기준)
- [`conventions.md`](./conventions.md) — 프로세스/머지 게이트 (p1 판정 근거)
- `.agent/rules/` — 코드 작성 규칙 (p1·p2 판정 근거)
