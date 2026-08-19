# 리뷰 오탐 방지 — "코멘트하지 말 것" 단일 정본 (SSOT)

이 문서는 **반복적으로 기각되는 오탐 패턴**과 **의도된(정상) 패턴**을 한곳에 모은다.
자가 리뷰·PR 리뷰·무인 검증 노드가 **같은 기준**으로 기각하게 하는 것이 목적이다.

> **선행 규율**: 모든 리뷰 주체는 finding 을 적기 **전에** 이 문서를 읽고, 해당하는 패턴이면 기각 또는 강등한다.
> 등급·verdict 산출은 [`review-grading.md`](./review-grading.md) 가 정본이다.

> **안정 ID 규칙**: 각 항목에 `FP-` + 섹션 문자(A/B) + 번호 형식의 안정 ID 가 붙는다.
> **번호를 재사용하거나 순서를 바꾸지 않는다** (항목 삭제 시 ID 는 결번으로 남긴다).

## A. 코멘트하지 말 것 (오탐 — 누적 기각 패턴)

1. **(FP-A1) Kotlin nullable 타입(`?`) 자체를 null-safety 결함으로 지적 금지.** `val x: Foo?` 는 null 을 의도한 정상 선언이다.
   diff 안에 구체적 NPE 유발 경로(`!!`, 비검증 platform type 역참조)가 **실제로 보일 때만** 지적한다.
   "nullable 이니 null 처리 필요" 류 일반론·추측성 경고 금지. 불확실하면 단정 대신 ask 형태로.
2. **(FP-A2) 레이어를 벗어난 추측성 지적 금지.**
   - **presentation 계층**에 JGit 자원 수명·스레드 코멘트 금지 — 그 지적은 **infrastructure** 에, diff 에 실제 JGit 호출이 보일 때만.
   - **domain 계층**에 성능·I/O 코멘트 금지 — domain 은 순수 로직이라 I/O 가 없다.
3. **(FP-A3) diff 만으로 검증 불가한 사항은 단정(CRITICAL) 금지.** 호출되는 메서드 본문이 diff 에 없으면
   성능·자원 누수·취소 전파를 단정하지 않는다. 필요하면 "대형 저장소에서 확인 필요" 같은 **질문(ask) 형태**로만.
4. **(FP-A4) 테스트 코드(`src/test/**`)는 관대하게.** mock 개수·픽스처 추출·하드코딩 값·테스트 전용 헬퍼 노출은
   제안 수준 → **작성 금지**. 단, 테스트가 **실 사용처와 다른 형태로 검증**하는 fidelity 문제는 지적 가치가 있다.
5. **(FP-A5) `runBlocking` 을 무조건 지적 금지** — `main` 진입점과 테스트에서는 정상이다.
   UI 코루틴·UseCase 경로에 등장할 때만 지적한다.
6. **(FP-A6) 단순 중복 코드 추출·문서화·가독성 제안은 p3~p4** → 상향 금지.
7. **(FP-A7) PR 본문에 "비범위(out of scope)"/후속 처리로 명시된 사항**은 지적하지 않는다.
8. **(FP-A8) 이미 처리 로직이 있는 경우** 재지적 금지 — 변경 전 처리 유무를 diff 에서 확인한다.
9. **(FP-A9) Compose 리컴포지션 성능을 측정 없이 단정 금지.** `remember` 부재가 항상 문제는 아니다 —
   계산이 실제로 무거운 경로(레인 배치·대량 diff)에서만 지적한다.

## B. 의도된 패턴 — 버그 아님

아래는 이 코드베이스의 **의도된 컨벤션**이다. 결함으로 지적하지 않는다.

| ID | 패턴 | 의도 |
|---|---|---|
| FP-B1 | `Repository` 를 앱 수명 동안 열어 두고 닫지 않음 | 저장소 핸들은 세션 단위 캐시가 목적 — 전환 시에만 닫는다 ([`jgit-usage`](../rules/jgit-usage.md) 규칙 2) |
| FP-B2 | 도메인 모델이 JGit 타입 대신 자체 타입(`CommitId`)을 씀 | 레이어 경계 유지 — "불필요한 래핑"이 아니다 |
| FP-B3 | `Dispatchers.IO.limitedParallelism(1)` 로 Git 접근 직렬화 | `Repository` 가 스레드 안전하지 않아 의도적으로 직렬화한다 |
| FP-B4 | 원격 예외를 감싸며 메시지를 마스킹 | 자격증명 유출 방지 ([`credential-handling`](../rules/credential-handling.md) 규칙 2) — "예외 원문 손실"이 아니다 |
| FP-B5 | `sealed interface` 의 `when` 에 `else` 부재 | exhaustive 강제가 의도 — 새 케이스를 컴파일 에러로 잡는다 |
| FP-B6 | UI 상태 홀더가 `StateFlow` 를 노출하고 Composable 이 `collectAsState` | 표준 단방향 데이터 흐름 |
| FP-B7 | 임시 저장소를 만드는 테스트가 느림 | 실제 Git 동작 검증이 목적 — Mock 대체는 [`testing`](../rules/testing.md) 규칙 1 위반이다 |

## C. 코멘트 작성 형태

- **동일 유형 지적은 하나로 묶고**, 적용 가능한 다른 파일은 그 코멘트 안에 파일명으로 함께 레퍼런스한다 — 파일/라인마다 반복 금지.
- 확인이 필요하나 diff 로 단정 불가한 사항은 **단정 대신 ask 형태**로.
- 칭찬·잘 작성된 코드 코멘트 금지. 문제 없으면 코멘트를 비운다.

## 참조

- 코드 작성 규칙: [`architecture-layers`](../rules/architecture-layers.md) · [`jgit-usage`](../rules/jgit-usage.md) · [`compose-ui`](../rules/compose-ui.md) · [`kotlin-idioms`](../rules/kotlin-idioms.md) · [`exception-handling`](../rules/exception-handling.md) · [`credential-handling`](../rules/credential-handling.md) · [`testing`](../rules/testing.md)
- 리뷰 에이전트: [[custom-kotlin-desktop-engineer]] · [[custom-pr-call-graph-reviewer]] · [[custom-silent-failure-hunter]]
- 등급·verdict: [`review-grading`](./review-grading.md)
