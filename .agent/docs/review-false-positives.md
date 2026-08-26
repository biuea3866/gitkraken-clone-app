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
   - **domain 계층**이 프레임워크·I/O 를 쓰는 것 자체를 지적 금지 — 이 프로젝트는 domain 프레임워크 의존을 허용한다.
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
10. **(FP-A10) 스펙 AC 와 결정 문서가 어긋날 때 코드를 AC 위반으로 지적 금지.** 결정 문서(`정정` 절)가
   요구사항·스펙보다 **뒤에 확정된 상위 근거**다. AC 가 정정 이전 서술을 굳혀 둔 경우, 지적 대상은
   코드가 아니라 **스펙**이다 — finding 이 아니라 `open_questions`/게이트 보고로 올린다.
   판별법: 그 AC 문구가 결정 문서의 어느 절을 인용했는지 찾고, 그 절에 `이 결정으로 대체된다` 류
   정정이 붙어 있는지 확인한다. (근거: wave 2 에서 A1→C1 정정을 놓쳐 UND-03·UND-07 이 오탐 p1 로 막혔다.)
11. **(FP-A11) JGit·라이브러리 내부 동작을 소스 확인 없이 단정 금지.** "이 API 는 정렬을 깨뜨린다"
   같은 지적은 해당 클래스의 실제 구현(디컴파일·소스 jar)을 확인한 근거를 함께 적는다.
   (근거: `DirCacheBuilder` 가 순서 이탈을 스스로 재정렬하는데도 정렬 파괴로 p1 이 올라왔다.)

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
| FP-B8 | `~GatewayImpl` 이 `Repository` 가 아니라 `GitAccess` 를 생성자로 받음 | `Repository` 는 스레드 안전하지 않아 직렬화·`Dispatchers.IO` 경계를 `GitAccess` 가 공유한다. 결정문 C1 이 "생성자로 `Repository` 를 받는다"(A1)를 **대체**했다 — Impl 이 락·`withContext` 를 다시 걸지 않는 것도 같은 이유다 |
| FP-B10 | `commit(amend = true)` 가 원격 포함 여부를 **재작성 뒤**에 `CommitResult.existsOnRemote` 로 알려줌 | preflight 를 추가하면 `StagingGateway`(UND-01 계약)와 UND-17·UND-26 이 함께 움직여야 한다. 결정문 C6 이 **domain 계약 유지 + 백업 ref 로 복구 지점 확보 + UI 사전 확인은 UND-17 소유**로 확정했다 — UND-06 범위에서 계약 변경을 요구하지 않는다 |
| FP-B11 | UI 티켓이 `application/<자기 slug>/` 와 `presentation/i18n/<자기>Strings.kt` 를 함께 소유 | 결정문 A1·A3 이 승인한 범위다 (`.claude-local/WAVE3-DECISIONS.md` §A1·§122–126). UND-13 → `application/sidebar/`, UND-16 → `application/diff/`, UND-19 → `application/welcome/` 식으로 각 UI 티켓이 자기 UseCase 를 소유한다 — "승인되지 않은 소유 범위 확장" 이 아니다 |
| FP-B12 | 정적 소스 검사 테스트가 `Mutex`·`withContext`·`Dispatchers` 문자열을 훑음 | 그 테스트는 **금지 패턴이 실행 코드에 없음**을 지키는 것이고 현재 통과한다. 같은 낱말이 주석·KDoc 에 등장하는 것을 "오검출" 로 지적하지 않는다 — 검사 대상은 테스트가 정의하며, 실패하지 않는 검사를 결함으로 올리지 않는다 |
| FP-B13 | 축 산출물(`axis-review.json`)에 `verdict` 키가 없음 | 그 스키마는 `verdict` 를 **선언하지 않는다** — 축은 `findings` 만 올리고 판정은 요약 노드가 한다. fail-closed 는 `verdict` 를 선언하는 스키마(`review.json`·`final-summary.json`·`ticket-review.json`)에만 적용된다 ([`review-grading`](./review-grading.md) "판정 불가 시"). 이것을 지적으로 올리면 코드에 문제가 없는 티켓도 APPROVED 에 도달하지 못한다 |
| FP-B14 | `java.util.logging` 사용을 `app/build.gradle.kts` 의 `modules(...)` 목록에 `java.logging` 이 없다는 이유로 배포 회귀(p0)로 지적 | Compose Gradle 플러그인이 `java.base`·`java.desktop`·**`java.logging`** 을 jlink `--add-modules` 에 기본으로 넣는다 (`app/build/compose/tmp/createRuntimeImage.args.txt` 로 확인). 빌드된 런타임 이미지의 `release` 파일 `MODULES` 에도 `java.logging` 이 있다. `modules(...)` 블록은 **플러그인 기본값에 더하는** 목록이지 전체 목록이 아니다. 덧붙여 `java.sql`(목록에 있음)이 `java.logging` 을 transitive 로 요구한다 |
| FP-B9 | `DirCacheBuilder.add()` 를 정렬 순서와 무관하게 호출 | `commit()` → `finish()` 가 `sorted` 플래그를 보고 `resort()` 한다 (JGit 7.3 확인). 엔트리를 뒤에 덧붙여도 인덱스 순서는 깨지지 않는다 |

## C. 코멘트 작성 형태

- **동일 유형 지적은 하나로 묶고**, 적용 가능한 다른 파일은 그 코멘트 안에 파일명으로 함께 레퍼런스한다 — 파일/라인마다 반복 금지.
- 확인이 필요하나 diff 로 단정 불가한 사항은 **단정 대신 ask 형태**로.
- 칭찬·잘 작성된 코드 코멘트 금지. 문제 없으면 코멘트를 비운다.

## 참조

- 코드 작성 규칙: [`architecture-layers`](../rules/architecture-layers.md) · [`jgit-usage`](../rules/jgit-usage.md) · [`compose-ui`](../rules/compose-ui.md) · [`kotlin-idioms`](../rules/kotlin-idioms.md) · [`exception-handling`](../rules/exception-handling.md) · [`credential-handling`](../rules/credential-handling.md) · [`testing`](../rules/testing.md)
- 리뷰 에이전트: [[custom-kotlin-desktop-engineer]] · [[custom-pr-call-graph-reviewer]] · [[custom-silent-failure-hunter]]
- 등급·verdict: [`review-grading`](./review-grading.md)
