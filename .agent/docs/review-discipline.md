# 리뷰 규율 정본 (SSOT) — 판정·출력·브리핑·체크리스트·HTML

[[custom-pr-review]] 의 리뷰 규율을 한 문서로 담는다. 오케스트레이터와 팬아웃 서브에이전트는 이 문서 전체를 Read 하되, **적용은 절별 조건**을 따른다:

| 절 | 적용 조건 |
|---|---|
| §검산 게이트 · §출력 템플릿 | **항상** — 모든 finding 의 확정·등급·형식 |
| §PR 이해 브리핑 | 오케스트레이터만 (Step 2.5, deep+비-trivial) |
| §보안 체크리스트 | 보안 표면(인증·인가·입력 진입점·쿼리 조립·외부 호출·역직렬화·시크릿·암호화) diff 일 때만 적용 |
| §메모리 릭 체크리스트 | 장수명 스코프 상태·캐시·ThreadLocal/MDC·Executor·코루틴 스코프·Compose 상태 diff 일 때만 적용 (Gate 1-L 근거) |
| §HTML 리포트 | `--html` opt-in 일 때만 (기본 OFF) |

> **오탐 SSOT 와의 관계**: [`review-false-positives.md`](review-false-positives.md) 는 FP-ID 로 관리되는 별도 정본이라 본 문서에 합치지 않는다. **게이트를 통과했어도 오탐 SSOT 에 걸리면 기각이 이긴다.**

## §검산 게이트

리뷰 후보(review candidate)를 **finding 으로 확정할지, 어느 등급으로 낼지**의 단일 정본이다.
[[custom-pr-review]] Step 4 와 팬아웃 서브에이전트가 이 문서를 권위로 따른다.

> **오탐 SSOT 와의 경계**: [`review-false-positives.md`](review-false-positives.md) 는 "**무엇을 코멘트하지 말 것**"(누적 기각 패턴·의도된 패턴)이고,
> 본 문서는 "**남은 후보를 어떤 근거로 확정하고 몇 등급으로 낼 것**"이다. 순서는 **게이트 → 오탐 SSOT 대조** 둘 다 적용이며,
> 두 문서가 충돌하면 **기각이 이긴다**(오탐 SSOT 에 걸리면 게이트를 통과했어도 보고하지 않는다).

### 등급 3종 — 단정 강도 축

| 등급 | 단정 강도 | 의미 |
|---|---|---|
| `[정리]` | 약 | 위험을 단정하지 않음. hygiene cleanup 힌트만 |
| `[질문]` | 보류 | 위험 가능성을 제기하나 외부 확인이 있어야 단정 |
| `[Bug]` | 강 | 코드 경로로 실패 동작이 확인됨 |

HIGH/MEDIUM/LOW 같은 별도 severity 축을 병행하지 않는다 — 등급이 곧 단정 강도다.
**확신이 없을 때 등급을 위로 끌어올리지 않는다.**

### 게이트 (하나라도 막히면 `[질문]` 또는 follow-up)

#### Gate 1. 실패 경로가 코드 경로로 확인됐는가

- 요구사항과 구현 방식이 다르다는 것만으로 bug 라고 쓰지 않는다.
- **잘못된 결과·누락·조기 종료·중복 처리·권한 우회** 같은 failing behavior 가 코드 경로로 확인될 때만 finding.
- 실패 경로가 그럴듯하지만 확인되지 않으면 `[질문]`.

#### Gate 1-L. 메모리 릭은 "누적 + 해제 부재" 축으로 검증한다

릭 후보는 단일 경로의 잘못된 결과(Gate 1)로는 확인되지 않는다 — 결과는 정상이고 시간·반복에 따라 메모리만 자란다.
Gate 1 을 그대로 적용하면 모든 릭이 미통과로 drop 되므로, 릭 후보는 아래 3조건을 코드로 검증한다.
패턴·grep 신호는 §메모리 릭 체크리스트.

- **① 결박**: 객체/엔트리가 싱글톤·`companion object`·ThreadLocal·정적 레지스트리·미취소 `CoroutineScope` 등 장수명 스코프에 보관된다.
- **② 누적**: 요청·입력·반복에 비례해 엔트리가 추가되는 경로가 있다.
- **③ 해제 부재**: evict/TTL/size-bound/remove/close/cancel/clear 중 어느 해제 주체도 코드에 없다(또는 우회 가능함을 코드로 보인다).

판정:

- 세 조건을 모두 코드로 확인하면 **측정값 없이도 `[Bug]` 자격**. Gate 4 의 "측정값 없으면 강등"은 릭에 적용하지 않는다.
- 셋 중 하나라도 코드로 못 보이면 `[질문]`. 해제 주체를 찾았으면 그 위치(`file:line`)를 drop/강등 사유에 남긴다.
- 참조 그래프 retention·off-heap 처럼 힙 덤프/프로파일러가 있어야 단정 가능한 릭은 `[질문]` + "확인 방법: 힙 덤프 / Pyroscope".

#### Gate 2. 쿼리/스트림 근거가 있으면 예시 데이터 대입

- 필터·정렬·페이징 조합이 근거에 포함되면 작은 예시 데이터를 **조건 적용 순서대로** 대입한다.
- 최소 요건: **경계값 1건 + 정상값 1건 = 2건 이상**. 1건만 대입하고 통과로 보지 않는다.
- 인덱스·정렬·limit 위치에 따라 결과가 달라지므로 "조건 누락" 류 finding 은 대입 결과로 한 번 더 확인한다.

#### Gate 3. PR 본문 / 티켓 / 코멘트는 검증 대상이지 proof 가 아니다

- 작성자의 설명이 코드 동작과 다르면 **그 자체가 finding 또는 질문 신호**다.
- 티켓 AC·PRD·설계 문서도 동일 — 의도이지 정답이 아니다.

#### Gate 4. 운영 행동 전제 검증

finding 의 시나리오에 "사용자/운영자/엔지니어가 X 한다"(재시도 버튼 클릭, 대시보드 조회, 배치 재실행) 류 **운영 행동이 전제**로 들어가면 stop and verify.
코드 경로만으로 위험이 서면 전제 없음, 사람이 특정 행동을 해야 위험이 격상되면 전제 있음.

- **성능 finding**: 트래픽 규모 또는 측정값 없이 "비용 2배"만으로 finding 화하지 않는다 → `[질문]` 또는 `[정리]`. 단 메모리 릭은 예외(Gate 1-L).
- **thesis(원인 진단)가 강해도 그 아래 finding 등급이 자동으로 강해지지 않는다** — 등급은 분리 판정.

#### Gate 5. 진입 상태 도달가능성 검증

Gate 1 이 "상태 S 에서 코드가 나쁜 결과를 낸다"(결과의 전진 검증)라면, Gate 5 는 "**상태 S 에 실제로 도달 가능한가**"(전제의 도달가능성)다.
Gate 1 만으로는 내부적으로 일관되지만 진입 상태가 불가능한 시나리오가 통과한다.

finding 이 **특정 공존 상태나 이벤트 순서**("A 가 in-flight 인 동안 B 가 일어난다", "재시도 2회 후 이전 차수 콜백 도착", "두 요청이 동시에 같은 행 갱신")에 의존하면 stop and verify.

- 그 상태를 **금지하거나 직렬화하는 가드**(상태 전이 조건, precondition 검사, 락, 유니크 제약, 완료 게이트, 도메인 불변식)를 코드에서 **먼저** 찾는다.
- 가드가 **부재하거나 우회 가능함을 코드로 보이지 못하면** `[Bug]` 가 아니라 `[질문]`.
- 가드를 찾았으면 위치(`file:line`)를 finding 또는 drop 사유에 명시한다.
- 타임라인을 구성하기 전에 그 타임라인의 **시작 상태**가 코드 가드를 통과하는지부터 확인한다. **구성한 타임라인이 그럴듯하다는 것은 도달가능성의 근거가 아니다.**

### 분기표 (위에서 아래로, 첫 매치)

| 조건 | 등급 |
|---|---|
| Gate 1~5 중 하나라도 미통과 | `[질문]` 또는 follow-up |
| Gate 통과 + 운영 행동 전제 없음 | `[Bug]` |
| Gate 통과 + 전제 있음 + 사용자 확인 가치 | `[질문]` |
| Gate 통과 + 전제 있음 + hygiene 만 의미 | `[정리]` |

- 둘 이상 행이 동시 매치하면 **더 약한 등급**을 고른다(`[정리]` < `[질문]` < `[Bug]`).
- 메모리 릭 후보는 Gate 1 대신 **Gate 1-L** 로 판정한다. 나머지 게이트(3·5)와 단정 강도 축은 동일 적용.
- 특수 신호 충돌(draft + force-push + revert 동시) 시 **더 엄격한 게이트 우선** — force-push 후 재리뷰는 이전 finding 재확인 우선, draft 는 사용자 확인 우선.

### 유입 여부 판별 (pre-existing 구분)

deep 모드는 worktree 전체 트리를 읽으므로 **이번 diff 가 만들지 않은 기존 버그**가 표면화된다.
게이트를 통과한 finding 은 확정 직전 "이 실패 경로가 **이번 diff 로 새로 생겼는가**"를 판별한다.

- **이번 diff 유입**: 변경된 라인이 실패 경로를 만들거나 여는 경우 → 등급·템플릿 그대로.
- **기존(pre-existing)**: 실패 경로가 변경 전에도 존재했고 이번 diff 는 인접·무관 → **등급(심각도)은 그대로 매기되 차단하지 않는다.** 제목에 `(기존)` 마커를 붙이고, `확인한 범위`에 "이번 PR 유입 아님 — 기존 코드" 한 줄. 리뷰어가 회귀로 오독하지 않게 하는 것이 목적.
  - 무인 검증 노드에서는 `findings` 가 아니라 `advisory` + `non_blocking_reason: C1_pre_existing` 으로 낸다 ([`review-grading.md`](review-grading.md) "차단 판정의 전제"). 기존 p0 를 차단으로 두면 이번 티켓이 남의 결함으로 영구히 막히고, 반대로 p4 로 낮추면 후속 티켓에서 위험을 잃는다 — 심각도는 유지하고 차단만 뗀다.
- 유입/기존이 코드로 확정 안 되면 단정하지 말고 `추론 / 가정` 또는 `질문`에 "유입 시점 미확인".
- 기존 버그라도 이번 PR 범위와 무관하면 **무관한 기존 버그를 대량 발굴하지 않는다**(scope creep).

### 최종 self-check

출력 전 아래를 확인하고, 위반 시 action 을 적용한다.

| 확인 | 위반 시 action |
|---|---|
| 각 finding 이 변경 코드에 연결되는가 | 연결 안 되면 drop. 관련은 있으나 근거가 약하면 `질문`/follow-up 으로 이동 |
| `[Bug]` 는 코드 경로로 실패가 확인됐는가 | 미확인이면 `[질문]` 강등 또는 drop |
| 공존 상태·이벤트 순서 의존 finding 은 가드를 코드에서 찾아 도달가능성을 확인했는가 (Gate 5) | 가드가 막으면 drop, 가드 부재를 못 보이면 `[질문]` 강등. 가드 위치(`file:line`) 를 사유에 남긴다 |
| 쿼리/스트림 finding 은 예시 데이터 2건 이상 대입했는가 | 대입 후 재판정. 대입 불가면 `[질문]` 강등 + 사유 명시 |
| 장수명 스코프의 상태/자원/구독에 해제 주체를 확인했는가 (Gate 1-L) | 해제 주체 부재 + 누적 경로 확인 시 측정값 없이 `[Bug]`. 해제 주체를 찾았으면 위치를 남기고 drop |
| 운영 행동 전제가 있는가 | 사용자 확인 가치면 `[질문]`, hygiene 만이면 `[정리]` |
| style/nit/broad refactor 가 섞였는가 | 사용자가 명시 요청하지 않은 항목은 drop |
| 이번 diff 유입인가 기존 코드인가 판별했는가 | 기존이면 `(기존)` 마커 + `확인한 범위` 명시. 무관한 기존 버그 대량 발굴이면 scope creep 으로 drop |
| 보안 표면을 건드리는데 체크리스트를 적용했는가 | 인증·인가·입력·쿼리·외부호출·역직렬화·시크릿·암호화 표면이면 §보안 체크리스트 로드 후 taint-to-sink 점검 |
| 오탐 SSOT 대조를 마쳤는가 | [`review-false-positives.md`](review-false-positives.md) A·B·D 절 대조. 기각 건수와 근거 FP-ID 를 집계 |
| 위치 line number 를 최종 checkout 기준으로 재확인했는가 | 재계산. 정확한 line 을 모르면 line suffix 를 생략하고 심볼 기준으로 설명 |
| 컴파일/테스트 실행 없이 "검증했다"고 쓰지 않았는가 | 정적 리뷰 전용 — 실행하지 않은 검증은 사유를 한 줄 남긴다 |

---

## §출력 템플릿

리뷰 결과를 **어떤 형식으로 쓸 것인가**의 단일 정본이다. [[custom-pr-review]] Step 5 가 이 문서를 권위로 따른다.
**등급 결정 자체는 §검산 게이트 가 권위**이며, 본 문서는 결정된 등급을 템플릿으로 옮기는 매핑만 정의한다.

### 대원칙

- **콘솔 응답이 primary deliverable.** 리포트 파일을 열어야만 이해되게 만들지 않는다.
- **설명 깊이 우선.** 독자가 diff 를 다시 열지 않고도 risk·근거·영향·수정 방향을 이해하도록 풀어 쓴다. 목표는 `짧게`가 아니라 `끊어서 읽기 쉽게`.
- 단, 깊이는 **확정된 finding 에만** 쓴다. 약한 우려를 길게 늘려 finding 처럼 보이게 하거나, 영역 밖 코멘트를 더해 분량을 만들지 않는다.
- 한국어로 쓰되 파일 경로·명령·PR URL·티켓 키·코드 심볼은 원문 유지.
- 리뷰 본문은 일반 markdown 으로 쓴다. 전체를 코드펜스로 감싸지 않는다 — 펜스는 댓글 초안·명령·로그 원문에만.

### 등급 → 템플릿 매핑

| 등급 | 템플릿 |
|---|---|
| `[Bug]` | Full (9 label) |
| `[질문]` | Light (5 label) |
| `[정리]` | Light (5 label) |

각 finding 제목은 **반드시 `[Bug]`·`[질문]`·`[정리]` 중 하나로 시작**한다.
pre-existing 이면 등급 뒤에 마커 — `### 1. [Bug] (기존) {제목}` (판별 기준은 게이트 문서 §유입 여부 판별).

### Full template — `[Bug]` 전용

| label | 필수 여부 |
|---|---|
| `위치` | required |
| `관련 위치` | conditional (보조 근거가 있을 때만) |
| `코드로 확인한 사실` | required |
| `왜 문제가 되는지` | required |
| `추론 / 가정` | conditional (외부 계약·FE·배포·운영 데이터·운영 행동 전제·문서 해석에 의존하면 **반드시**) |
| `영향` | required |
| `권장 수정 위치` | required |
| `수정 방향` | required |
| `댓글 초안` | conditional (`--post` 게시 요청 시) |

required label 만으로 `[Bug]` 는 self-contained 해야 한다. conditional 이 빠지는 건 누락이 아니라 정상.

label 별 작성 규칙:

- `코드로 확인한 사실` — **실제로 읽은** 코드/diff/테스트/설정 사실만. 가능하면 호출 흐름 순서대로. 중요 finding 은 구체적 근거 3개 이상. 판단은 여기 쓰지 않는다.
- `왜 문제가 되는지` — 코드 사실에서 실패 경로로 이어지는 reasoning bridge. "회귀 위험" 같은 추상 표현만 쓰지 말고 어떤 값·상태·분기가 어떤 결과를 만드는지 쓴다.
- `추론 / 가정` — 코드로 확정 못 한 연결. 외부 시스템·FE·배포 순서·운영 데이터 의존은 반드시 여기 또는 `질문`으로 분리.
- `영향` — 어떤 요청·데이터·사용자 동작·운영 상황이 잘못되는지. 확정 못 한 범위는 단정 금지.
- `권장 수정 위치` — 조건/가드/검증/매핑/테스트를 어느 파일·함수 근처에 추가할지. 문제 위치와 같아도 별도 표기. 프로덕션과 테스트 둘 다 의미 있으면 둘 다.
- `수정 방향` — 한 줄 처방 금지. 무엇을 검증·테스트할지 포함.

### Light template — `[질문]`·`[정리]` 전용

| label | 내용 |
|---|---|
| `위치` | file link |
| `코드로 확인한 사실` | 확인한 코드/diff 사실 |
| `왜 문제가 될 수 있는지` | 단정하지 않는 risk 연결. `[정리]` 는 hygiene 이유, `[질문]` 은 확인이 필요한 이유 |
| `영향` | 잠재 영향만. Full 보다 짧게, 단정 금지 |
| `권장` | `[정리]` 는 cleanup 방향, `[질문]` 은 사용자에게 확인 요청할 내용 |

**5 label 로 의미가 안 서면 finding 자체가 약하거나 등급이 잘못된 것** — 보류하거나 등급을 재평가한다.

### file link 규칙

- 클릭 가능한 markdown 링크, target 은 **절대 경로**, 라인을 알면 `:line`.
- deep 모드는 리뷰 대상 코드가 worktree 에 있으므로 **worktree 절대경로**로 건다(리뷰 종료 후 worktree 를 지우면 링크가 깨지므로, 남길 리포트에는 GitHub permalink 를 보조로 병기한다).
- 중요한 위치는 바로 아래 `line {n}: {짧은 스니펫}` 을 함께 — 라인이 밀려도 찾을 수 있게.
- **approximate line 금지.** 정확한 라인을 모르면 `:line` 을 생략하고 함수/클래스명으로 설명한다.
- 최종 응답 직전 현재 checkout 기준으로 재확인한다: `rg -n "{symbol}" {file}` 또는 `nl -ba {file} | sed -n '{n-3},{n+3}p'`.

### 출력 길이 budget

| 항목 | 분량 |
|---|---|
| finding 본문 (lead + label 전체) | 권장 15–30줄, 최대 40줄 |
| `변경의 흐름` 단락 | 5–10줄 |
| `요약` bullet 합계 | 5줄 이내 |
| `질문` 한 건 | 2–4줄 |
| `확인한 범위` | 5–10 bullet, 각 1–3줄 |

넘으면 (a) 하위 bullet 로 끊거나 (b) finding 을 분리하거나 (c) raw 출력을 리포트 파일로 옮긴다.
budget 은 **상한 쪽을 적극 활용**한다 — 줄 수를 아끼려 근거를 생략하지 않는다. 단 이는 확정된 finding 에만 적용되며 finding 수를 늘리는 근거가 아니다.

### 리포트 골격

1부(PR 이해)의 작성 규칙은 §PR 이해 브리핑 가 권위다. 본 골격은 순서만 고정한다.

~~~md
# PR Review — #<N> <title>

> ⚠ (해당 시) 이 PR 은 `.agent/**`·`.claude/**`·`orchestration/**` 또는 `**/CLAUDE.md` 를 변경한다 — 리뷰 기준 변경으로 간주, 아래 "기준 문서 변경"에서 별도 검토.

### 1부. 이 PR 이해하기

#### 목적
- 티켓: <KEY | 미확보>  ·  base: <baseRefName>  head: <headRefName>  draft: <yes/no>
- 무엇을 / 왜 / 어떻게 (AC 미확보면 "AC 미확보 — PR 본문 기준")
- 요구사항 ↔ 변경 매핑

#### 배경 (이 도메인을 처음 보는 팀원 기준)
#### 변경 지도 — (a) 디렉토리 트리  (b) 모듈·레이어 flowchart
#### 핵심 흐름 (다이어그램 라우팅표에 따라 1–2개)
#### 변경 내러티브 (논리 순서 5–8스텝)

### 2부. 리뷰 결과

#### 영향 모듈
- <module> — 구조: <레이어드/DDD/헥사고날>, JVM <ver>

#### 변경의 흐름
(비동기 흐름·상태 전이 분기 PR 에서만. 단일 함수 변경이면 생략)

#### 리뷰 의견
##### 1. [Bug] {제목}
{독자가 diff 를 열지 않아도 상황을 이해할 lead 한 문장 — 어떤 입력/상태/행동에서 문제가 드러나는지}
- 위치 / 코드로 확인한 사실 / 왜 문제가 되는지 / 영향 / 권장 수정 위치 / 수정 방향 …

#### 질문
- {단정 못 하지만 사용자 확인 가치가 있는 사항}

#### 선택적 보강
- {실패 경로가 확정되지 않은 테스트 보강·작은 회귀 가드. 최대 2개}

#### 기존 부채 / 확인 필요 (diff 밖 — 단정 아님)
#### 기준 문서 변경 (해당 시 별도 검토)

#### 오탐 필터
- SSOT 대조로 기각/강등: <count> 건 (근거 FP-ID)

#### 확인한 범위
- {확인한 파일·함수·테스트·문서와, finding 으로 올리지 않은 이유(가드 위치·대입 결과·게이트 미통과 사유)}

#### 검증
- 정적 리뷰 전용 — 실행한 검증 없음. 사유: <한 줄>

### 결론
- Blocking: <count> — <근거>   ← Blocking = `[Bug]` 건수 (`(기존)` 마커 붙은 것은 제외하되 건수와 함께 별도 언급)
- 다음 단계: (게시 원하면 `--post`)
~~~

섹션 처리:

- `질문`·`선택적 보강`·`기존 부채`·`기준 문서 변경` 은 비면 **섹션 생략**.
- `확인한 범위` 는 **생략 금지**. finding 이 적을수록 "무엇을 어디까지 봤는지"로 신뢰를 준다.
- `검증` 은 정적 리뷰 전용이므로 실행하지 않은 이유를 한 줄 남긴다 — **컴파일·테스트 실행으로 "검증했다"고 쓰지 않는다.**

### no-finding 형식

`리뷰 의견` 이 비면 "리뷰 의견: 코드 경로상 위험 finding 없음" 한 줄로 대체하되,
**`확인한 범위` 에 점검한 영역과 각 후보를 왜 올리지 않았는지(게이트 미통과 사유·오탐 FP-ID·가드 위치)를 bullet 로 반드시 채운다.** 섹션 자체 생략 금지.

---

## §PR 이해 브리핑

리뷰 리포트 **1부("이 PR 이해하기")** 의 작성 규칙이다. [[custom-pr-review]] Step 2.5 가 이 문서를 권위로 따른다.

브리핑은 **두 번 쓰인다**:

1. **팬아웃 입력** — Step 3 에서 각 리뷰 에이전트에게 인라인 전달한다. 변경 파일 목록만 받은 에이전트와 도메인 맥락까지 받은 에이전트의 판단 품질은 다르다.
2. **리포트 1부** — 사용자가 읽는 결과물의 앞부분. finding 만 나열된 리포트는 그 도메인을 아는 사람만 읽을 수 있다.

> **작성 주체는 오케스트레이터**다. 서브에이전트에게 위임하지 않는다 — 브리핑은 팬아웃보다 **먼저** 존재해야 한다.

### 언제 쓰는가

| 조건 | 브리핑 |
|---|---|
| deep 모드 + 비-trivial PR | **작성** (기본) |
| `--light` 모드 | 축약 — 목적·변경 지도(트리)만, 배경·다이어그램 생략 |
| trivial 조기 종료 | 작성하지 않음 |

### 눈높이 — 독자는 "이 도메인을 처음 보는 팀원"

- 전문 용어를 **빼는** 게 아니라 **처음 등장할 때 설명하고 쓰는 것**이 원칙.
- 2부(리뷰 결과)는 리뷰 정밀도가 우선이라 눈높이를 낮추지 않는다 — **눈높이 조정은 1부에서만** 한다.

### 구성

#### ① 목적

무엇을 / 왜 / 어떻게. 티켓 키·링크, base·head·draft 여부, 요구사항 ↔ 변경 매핑.
AC 를 확보 못 했으면 "AC 미확보 — PR 본문 기준"을 명시한다(추측으로 채우지 않는다).

#### ② 배경 (처음 보는 사람용)

이 PR 을 이해하는 데 필요한 전제 지식 **3~5개**를 먼저 풀어 쓴다.

1. 관련 시스템의 역할을 **일상 비유**로 소개한다 — 예: 메일 파이프라인 = 택배(보내는 쪽 / 중계소 / 우체국).
2. 도메인의 핵심 개념(상태 머신·식별자·흐름)을 **다이어그램 1개**로 보여준다.
3. **"이 PR 이전의 문제"를 그 그림 위에서** 서술한다 — 별도 문단으로 떼지 않는다.
4. **용어 미니 사전** — 본문에 등장하는 도메인 용어(토픽명·상태명·규칙명·권한 키)를 한 줄씩 정의한다.

#### ③ 변경 지도 — 두 그림을 붙여 쓴다

**문자 그대로의 위치(트리)와 개념적 관계(flowchart)는 서로 다른 정보라 하나가 다른 하나를 대체하지 않는다.**

**(a) 디렉토리 트리** — `git diff --name-status $BASE_REF...HEAD` 의 경로를 **그대로** 트리로 그린다. 발명하지 않는다.

- 각 leaf 뒤에 `[신규]`/`[수정]`/`[삭제]` 태그.
- 트리 아래에 최상위 디렉토리 1~2개마다 역할을 한 줄로 — 예: `business/domain/` — 순수 도메인 모델·상태 규칙, 프레임워크 의존 없음.
- 고정폭 코드블록으로 렌더링.
- 트리가 길어지면(대략 25줄 초과) 같은 디렉토리의 나머지는 `… 외 N개` 로 접고, 리뷰와 무관한 생성 파일(빌드 산출물·`generated-*`)은 제외한다.

**(b) 모듈·레이어 flowchart (mermaid)** — (a) 의 파일들이 논리적으로 어떻게 호출·의존하는지의 멘탈 모델.

#### ④ 핵심 흐름 — 다이어그램 라우팅

PR 성격에 따라 아래에서 **첫 매치 1~2개**만 그린다.

| PR 성격 | mermaid 유형 |
|---|---|
| API 추가/변경 | `sequenceDiagram` (요청→controller→usecase→저장/외부호출) |
| 버그픽스 | `flowchart` AS-IS(결함 경로)와 TO-BE(수정 경로) — **별도 소스 2개, 세로로 쌓기** |
| 비동기/코루틴 흐름 | 호출→디스패처→상태 갱신 흐름도 (취소·예외 경로 주석) |
| 마이그레이션/배치 | 단계 파이프라인 + 데이터 상태 전이 |
| 리팩토링/코드 이동 | 모듈 의존 그래프 before/after |
| 상태머신 변경 | `stateDiagram-v2` (변경된 전이 강조) |

#### ⑤ 변경 내러티브

**파일 순서가 아니라 논리 순서로 5~8스텝.** "A 에서 값을 만들고 → B 가 그것을 저장하고 → C 가 소비한다" 식으로, 리뷰어가 읽는 순서를 정해준다.

### 절제 규칙 (다이어그램 품질의 핵심)

- diff 전체가 아니라 **리뷰어가 머리에 그려야 할 멘탈 모델**만 그린다. **노드 상한 ~15.**
- 변경 노드만 강조색(`classDef changed`), 미변경 컨텍스트는 회색.
- **flowchart 는 세로(`TD`) 우선.** 페이지 폭이 고정이라 가로(`LR`)는 hop 이 많을수록 축소돼 글자가 안 읽힌다 — 노드 4개 이하의 짧은 체인일 때만 `LR`.
- **비교(AS-IS/TO-BE, before/after)를 하나의 flowchart 안에 `subgraph` 2개로 나란히 묶지 않는다.** 연결 없는 subgraph 는 mermaid 가 가로로 auto-layout 하며 폭에 맞춰 둘 다 축소한다 — 각 흐름을 **별도 mermaid 소스로 완전히 분리**하고 figure 를 세로로 쌓는다(TD, AS-IS 먼저 → TO-BE 다음).
- finding 이 걸린 노드는 심각도 색으로 칠하고, caption 에 "노란 노드 = finding 2" 식으로 명시한다.
- caption 은 기호 읽는 법(실선/점선·색 의미)을 밝힌다.
- **넣을 게 없으면 억지로 그리지 않는다** — 해당 figure 를 제거하고 내러티브만 남긴다.

### mermaid 문법 검증

리포트에 넣기 전에 렌더링을 한 번 돌려 문법을 검증한다. 깨진 소스를 그대로 넣으면 뷰어에서 그림이 안 나온다.

```bash
npx -y @mermaid-js/mermaid-cli -i d.mmd -o d.svg -b transparent   # 실행 자체가 문법 검증
```

실패하면 소스를 고친 뒤 넣는다. 검증만이 목적이므로 산출 SVG 는 버려도 된다(스크래치패드에서 작업).

### 가설 중립성 (팬아웃 전달 시 필수)

브리핑에 의심 가설을 담을 때는 **"이 버그를 확인하라"가 아니라 "이 가설을 반증할 가드를 코드에서 먼저 찾아 refute 를 시도하고, 못 찾을 때만 finding 으로 올려라"** 로 중립 제시한다.

- 반증 대상 가드: 상태 전이 조건·precondition·락·유니크 제약·도메인 불변식.
- 특정 결론을 확신 쪽으로 심으면 리뷰어가 확증 시나리오 구성으로 끌려 **Gate 5(도달가능성)를 건너뛴다**.
- 팬아웃은 N개 에이전트로 동시에 나가므로, 오케스트레이터가 심은 확신이 **N배로 증폭**된다. 단일 리뷰어보다 이 규칙이 더 중요하다.

---

## §보안 체크리스트

[[custom-pr-review]] 의 보안 축이 참조하는 단일 정본. **보안 관련 표면을 건드리는 diff일 때만** 로드해 적용한다(progressive disclosure — 비보안 PR 은 로드하지 않는다). 등급 판정·게이트 정의는 다시 풀어 적지 않고 §검산 게이트 를 권위로 따른다.

### 언제 로드하는가 (보안 관련 표면 신호)

diff 가 아래 표면 중 하나라도 건드리면 이 체크리스트를 적용한다. 하나도 안 건드리면 로드하지 않는다.

- 인증/인가 경로 (로그인, 토큰 발급·검증, 권한 판정, `@PreAuthorize`/필터/인터셉터, 소유자 검증)
- 사용자 입력을 받는 진입점 (Controller 파라미터·body, 쿼리스트링, 헤더, 파일 업로드, 메시지 consumer)
- 외부 명령·경로 조립 (셸 인자, 파일 경로 결합, 동적 정렬·필터)
- 외부 호출 (HTTP client, redirect, 파일 경로·URL을 입력에서 받아 접근)
- 직렬화/역직렬화 (Jackson polymorphic, `ObjectInputStream`, YAML/XML 파서, `eval`류)
- 시크릿/PII (자격증명·토큰·개인정보의 저장·로깅·응답 노출)
- 암호화 (해시·암호 알고리즘 선택, 키 관리, 난수, 인증서 검증)

### 방법론 — 3단계 (context → 비교 → 데이터 흐름)

보안 축 안에서 순차로 적용한다. 별도 리뷰 축이 아니라 한 축 안의 3단계다.

1. **context**: 레포가 이미 쓰는 보안 프레임워크·sanitization·검증 유틸을 먼저 읽는다. 레포가 문서화한 보안 규칙(예: 모듈/루트의 `security-authorization.md`, `CLAUDE.md`)이 있으면 그것이 이 체크리스트보다 우선한다 — 컨벤션 대조와 짝으로 본다.
2. **비교**: 신규 코드를 레포의 기존 패턴과 대조해 **일탈**과 **새로 생긴 공격 표면**만 flag한다. 기존 코드베이스 전반의 hardening 부재를 새로 지적하지 않는다(이번 diff 가 유입한 것만 — 유입 여부 판별은 §검산 게이트).
3. **데이터 흐름 (taint-to-sink)**: 신뢰할 수 없는 입력(source)에서 민감 연산(sink)까지 값의 흐름을 추적한다. 경계에서 sanitization·검증·권한 확인이 빠졌는지 코드로 확인한다. 흐름이 코드로 확인돼야 finding — Gate 1(실패 경로 확인).

### 카테고리 taxonomy (10종)

각 항목은 "source → sink 경로가 코드로 확인될 때만" finding. 확인 안 되면 `[질문]`.

| 카테고리 | 확인할 것 |
| --- | --- |
| **Injection** | SQL/JPQL/native 문자열 연결, 동적 정렬 컬럼·`LIKE` 패턴, command/LDAP/XPath/NoSQL, XXE(외부 엔티티 허용 파서) |
| **인증/인가** | 인가 우회 로직, 권한 상승, **IDOR(소유자 검증 없이 id로 리소스 접근)**, 세션·JWT 검증 결함, 만료·서명 미검증 |
| **데이터 노출** | 하드코딩 시크릿, PII·자격증명 로깅, 에러 응답·API 응답에 민감정보 유출 |
| **암호화** | 약한 알고리즘(MD5/SHA1/DES), 키 하드코딩·재사용, 안전하지 않은 난수(`Random` vs `SecureRandom`), 인증서·호스트명 검증 우회 |
| **입력 검증** | 신뢰 경계에서 타입·범위·형식 검증 부재 (단, 영향 없는 단순 검증 부재는 §하드 제외) |
| **비즈니스 로직** | race/TOCTOU (확인 후 사용 사이의 상태 변화) — 단 **도달 가능성**은 Gate 5로 검증 |
| **설정** | 안전하지 않은 기본값, 과도하게 열린 CORS, 보안 헤더 부재, 디버그·actuator 노출 |
| **공급망** | 오타 스쿼팅 의존성, 신뢰할 수 없는 소스의 라이브러리 추가 |
| **코드 실행(RCE)** | 역직렬화 가젯(Jackson `enableDefaultTyping`/polymorphic, `ObjectInputStream`, SnakeYAML), `eval`/reflection으로 입력 실행 |
| **XSS** | 반사·저장·DOM. 서버 렌더 템플릿의 이스케이프 우회. FE는 §precedent 참조 |

### grep 신호 (스캔 시작점)

```
"SELECT " + / createQuery( + 문자열 연결 / Sort.by( 입력값                → 파라미터 바인딩인가 연결인가
@PathVariable / @RequestParam id → repository.findById                    → 소유자·권한 검증 있나 (IDOR)
enableDefaultTyping / @JsonTypeInfo / ObjectInputStream / new Yaml(        → polymorphic·gadget 허용?
MessageDigest.getInstance("MD5"|"SHA-1") / new Random() / DES             → 약한 알고리즘·난수
password / secret / token / apiKey  (하드코딩 리터럴, log.info(...))        → 노출·로깅
RestTemplate / WebClient ... 입력에서 받은 URL / new File(입력)            → SSRF·path traversal (host/protocol 통제 여부)
setAllowedOrigins("*") / permitAll() / .csrf().disable()                  → 설정 완화
```

grep은 후보를 좁히는 신호일 뿐. 매치가 떴다고 finding이 아니라, checkout된 트리에서 source→sink 경로와 가드 부재를 코드로 확인해야 한다.

### FP precedent (흔한 오탐 해소 규칙)

아래는 "위험처럼 보이지만 finding 아님"의 확정 판례다. Gate 3(PR 본문은 proof 아님)의 보안 버전.

- **시크릿 로깅 = 버그, URL 로깅 = 안전**. 로그에 토큰·비밀번호·PII면 finding, 단순 URL·요청 경로는 아님.
- **UUID는 추측 불가** — UUID 식별자에 대한 추가 검증 부재만으로 finding 화하지 않는다(IDOR은 *권한* 검증 부재이지 id 추측 가능성이 아니다).
- **env var·CLI flag·설정 파일 값은 신뢰 입력** — 운영자가 통제하므로 injection source로 보지 않는다.
- **FE 프레임워크(React/Angular/Vue)는 기본 XSS-safe** — 예외는 `dangerouslySetInnerHTML`, `bypassSecurityTrustHtml`, `v-html`, `innerHTML` 직접 대입.
- **client-side auth 체크는 취약점 아님** — 서버가 책임진다. FE의 권한 분기는 UX이지 보안 경계가 아니다.
- **command injection은 구체적 untrusted 경로가 있을 때만** — 상수·env로만 조립되면 아님.
- **SSRF는 host/protocol을 통제할 때만** — 입력이 경로(path)만 바꾸고 호스트·스킴은 고정이면 강등.

### 하드 제외 (finding으로 올리지 않는다)

노이즈 억제 목록. 아래는 **보안 finding 으로 보고하지 않는다**(별도 요청·확정 영향 증거가 없는 한).

- DOS·자원 고갈·rate-limiting 부재 (확정 악용 경로 없이)
- 이론적 race/timing 공격 (Gate 5 도달 가능성 미확인)
- 단순 hardening 부재 (보안 헤더·심층 방어 권고 수준)
- 영향이 증명되지 않은 non-critical 입력 검증
- 오래된 의존성 자체 (advisory ID + 취약 경로의 실제 호출이 확인될 때만)
- 감사 로그 부재, 로그 스푸핑
- ReDoS/정규식 injection (확정 악용 입력 없이)
- 테스트 전용 파일·문서(`*.md`)의 패턴
- **주의**: 공식 `/security-review`의 "memory-safe 언어의 메모리 안전 이슈 제외"는 **이식하지 않는다** — 이 리뷰는 JVM 메모리 릭을 1급 위험으로 다룬다(Gate 1-L, §메모리 릭 체크리스트). 메모리 릭은 이 제외 목록의 예외다.

### 등급 매핑

- source→sink 경로 + 가드 부재가 코드로 확인 → `[Bug]`. Gate 1·5 통과 필요.
- 경로는 plausible하나 외부 계약(FE 처리·게이트웨이 필터·downstream 검증)에 의존 → `[질문]` + `추론 / 가정`에 의존 지점 명시.
- 위 §하드 제외·§FP precedent에 해당 → 보고하지 않거나 `확인한 범위`에 왜 제외했는지 한 줄.
- HIGH/MEDIUM/LOW 같은 별도 severity 축은 쓰지 않는다 — `[Bug]`/`[질문]`/`[정리]` 단정 강도 축이 권위다.

### 모듈 노트

- 이 앱은 서버 인가가 없다 — 대신 **자격증명 취급**([`../rules/credential-handling.md`](../rules/credential-handling.md))과 **파괴적 Git 연산의 확인 절차**를 본다. 레포가 문서화한 규칙이 이 체크리스트보다 우선한다.

---

## §메모리 릭 체크리스트

§검산 게이트 Gate 1-L 의 근거 문서. 패턴 카탈로그·grep 신호·정적 확인 한계를 정의한다. 등급 판정·게이트 정의는 다시 풀어 적지 않고 게이트 문서를 권위로 따른다.

### 핵심 렌즈

릭 후보를 만나면 단 하나를 묻는다:

> **이 객체/엔트리는 *언제* 해제되는가? 해제 주체가 코드에 있는가? 요청·입력·반복에 비례해 무한히 자라는 경로가 있는가?**

①장수명 스코프 결박 ②반복마다 누적 ③해제 부재 — 세 조건이 코드로 확인되면 측정값 없이도 `[Bug]` 자격(Gate 1-L). 릭은 "단일 경로 실패"가 아니라 "반복 실행 누적"의 축이므로 Gate 1·Gate 4 의 일반 강등 규칙을 그대로 적용하지 않는다.

### 패턴 카탈로그

#### A. 장수명 스코프에 묶인 무한 컬렉션 (가장 흔함)
- `@Component`/`@Service`/`object`/`companion object` 같은 **싱글톤 필드**의 `mutableMapOf`/`ConcurrentHashMap`/`mutableListOf`에 `put`/`add`만 있고 evict·TTL·size bound 없음.
- "직접 만든 캐시" — `ConcurrentHashMap`을 캐시로 쓰는데 maximumSize 없음.
- `@Cacheable` / Caffeine·EhCache builder에 `maximumSize`/`expireAfter` 미설정 (로컬 캐시).

#### B. ThreadLocal / MDC 미정리 (스레드 풀에서 치명적)
- 필터·인터셉터·`@Async`에서 `ThreadLocal.set` 또는 `MDC.put` 후 `finally`의 `remove()`/`clear()` 없음. Tomcat 워커 스레드가 재사용되므로 값 + 그 값이 참조하는 객체 그래프가 다음 요청까지 생존.

#### C. Kotlin 코루틴 / 스코프 누수
- `GlobalScope.launch` — 취소 주체 없음, 무한.
- `CoroutineScope(...)`를 만들어 두고 `cancel()` 안 함 (특히 빈 필드로 보관).
- `Flow` collect / `Channel`을 닫지 않음. 요청 수명을 넘기는 구독.

#### D. 닫지 않는 자원
- `Files.list/walk/lines` (Stream 반환 → close 필수), `InputStream`/`Reader`/`ResultSet`/JDBC `Connection`, Reactor `Flux`/`WebClient` response body, gRPC channel — `.use {}` / try-with-resources 없음.
- 메서드 안에서 `Executors.newFixedThreadPool`/`Timer()` 생성 후 `shutdown()` 없음 (스레드 + 큐 누수).
- 싱글톤이어야 할 `WebClient`/`OkHttpClient`/connection pool을 요청마다 생성.

#### E. 리스너 / 구독 미해제
- 리스너 등록 후 해제 없음, 코루틴 `Job` 취소 안 함, JGit `Repository`/`RevWalk` close 안 함.

#### F. 커밋 이력 전량 적재
- 페이징 없이 `RevWalk` 전체를 리스트로 수집 → 수만 커밋 저장소에서 힙 폭발. diff 를 미리 전부 계산하는 것도 같은 축.

#### H. Compose 상태 누적
- `mutableStateListOf` 에 계속 append 만 하고 비우지 않음, 화면 이탈 시 수집 코루틴 미취소 → 백그라운드 갱신이 계속 살아 있음.

### grep 신호 (스캔 시작점)

```
싱글톤/companion/object 필드의 mutableMapOf / ConcurrentHashMap / mutableListOf  → put/add만? remove/evict 있나?
ThreadLocal< / MDC.put                                                          → finally의 remove()/clear() 짝 있나?
GlobalScope / CoroutineScope(                                                    → cancel() 있나? 빈 필드인가?
Executors.new / Timer( / newScheduled                                            → 메서드 지역 생성? shutdown 짝 있나?
Files.list / Files.walk / Files.lines / .stream() / InputStream / ResultSet      → .use{} / try-with-resources 있나?
@Cacheable / Caffeine.newBuilder / CacheBuilder                                  → maximumSize/expireAfter 있나?
.tag( / Tags.of(                                                                 → 값이 동적 id인가?
배치/대량 루프 안의 repository.save / saveAll                                     → flush()/clear() / chunk 있나?
```

grep은 후보를 좁히는 신호일 뿐이다. 매치가 떴다고 finding이 아니라, checkout된 트리에서 해제 주체(remove/close/cancel/evict)의 부재를 코드로 확인해야 Gate 1-L을 통과한다.

### 정적 확인 한계 — `[질문]`으로 남길 것

- 미묘한 참조 그래프 retention(예: 람다가 enclosing `this`를 캡처해 정적 레지스트리에 보관), off-heap(`ByteBuffer.allocateDirect`), ClassLoader 릭은 정적 리뷰로 단정하기 어렵다 → `[질문]` + "확인 방법: 힙 덤프 / Pyroscope".
- 외부 라이브러리 내부 동작에 의존하는 누적(커넥션 풀·캐시 라이브러리 설정값)은 설정값을 코드/`application.yml`에서 확인하지 못하면 `[질문]`.

### 모듈 노트

- 이 프로젝트는 Kotlin/JVM 단일 스택이다 — 위 카탈로그를 그대로 적용한다. coroutine·`Flow`·`use {}` 는 Kotlin 컨벤션 축([[custom-kotlin-desktop-engineer]]), 나머지는 버그·정합성 축에서 본다.

---

## §HTML 리포트

markdown 리포트(record)를 브라우저에서 읽는 **파생 뷰**의 작성 규칙이다. [[custom-pr-review]] Step 5.5 가 이 문서를 권위로 따른다.

> **기본 OFF — opt-in 전용.** `--html` 플래그(또는 "HTML 리포트도 만들어줘" 명시 요청)가 있을 때만 생성한다.
> 요청이 없으면 이 문서를 로드하지도, HTML 을 만들지도 않는다. trivial/light 리뷰에는 요청이 있어도 만들지 않는다(재료인 1부 브리핑이 없다).

### 파생 뷰 원칙 — record(md)가 SSOT

- **finding 본문은 record 와 100% 동일**해야 한다. HTML 에만 있는 판단·문구를 만들지 않는다.
- HTML 이 더하는 것은 **표현**뿐이다: 1부 브리핑의 다이어그램을 렌더링된 그림으로, finding 을 심각도 카드로.
- mermaid 소스·디렉토리 트리는 record md 에도 코드블록으로 동일하게 존재해야 한다 — HTML 이 없어도 record 가 완결되도록.

### 산출 위치·명명

record 옆에 같은 basename 으로: `.claude-local/reviews/{TICKET|NO_TICKET}_pr-<N>.html`
재리뷰 시 record 는 append 지만 HTML 은 **전체 재생성**한다(파생 뷰라 이력을 안 가진다 — 최신 차수 기준).

### 템플릿

- [`../skills/custom-pr-review/assets/report-template.html`](../skills/custom-pr-review/assets/report-template.html) 을 Read 하고 `<!-- {{SLOT}} -->` 주석 자리(20개)에 내용을 채워 Write 한다.
- 템플릿의 CSS(다크·라이트 대응, 다이어그램 카드 흰 배경, 심각도 배지 `[Bug]`=빨강·`[질문]`=노랑·`[정리]`=파랑)는 **수정하지 않고 그대로** 쓴다.
- 슬롯 구성은 리포트 2부제와 1:1 — 1부(목적 카드·배경·변경 지도·핵심 흐름·내러티브)는 §PR 이해 브리핑 산출물을 옮기고, 2부(FINDINGS·QUESTIONS·SCOPE·VERIFY)는 record 본문을 옮긴다.

### mermaid — 사전 렌더링 SVG 인라인 (CDN 금지)

뷰어 환경에서 외부 스크립트 로드가 막힐 수 있으므로 mermaid.js CDN 에 의존하지 않는다.

```bash
npx -y @mermaid-js/mermaid-cli -i d.mmd -o d.svg -b transparent   # scratchpad 에서, 실행 자체가 문법 검증
```

- 렌더링된 `<svg>` 를 HTML 에 **인라인 임베드**하고, 원문 소스는 `<details>` 로 접어 함께 넣는다.
- 실패하면 소스를 고친 뒤 임베드한다. `npx`/node 가 없는 환경이면 SVG 렌더링을 생략하고 소스 코드블록만 넣은 뒤 리포트에 "SVG 미렌더(node 부재)" 한 줄을 남긴다.
- 다이어그램 내용·절제 규칙(노드 상한·TD 우선·AS-IS/TO-BE figure 세로 분리)은 §PR 이해 브리핑 가 권위 — 여기서 재정의하지 않는다.
- finding 이 걸린 노드는 심각도 색으로 칠하되, 사전 렌더링 SVG 라 click 앵커 동작이 보장되지 않으므로 caption 에 "노란 노드 = finding N" 식으로 명시한다.

### 대용량 텍스트는 스크립트로 스플라이스 (직접 재타이핑 금지)

렌더링된 `.svg` 나 긴 디렉토리 트리를 Write 파라미터에 통째로 재입력하면 단일 응답이 비대해져 스트리밍이 끊길 수 있다(실측: Connection closed mid-response).

- Bash(python3/sed)로 템플릿의 `<!-- {{SLOT}} -->` 자리에 **파일 내용을 파일에서 파일로** 주입한다.
- 모델은 슬롯 채우기 스크립트와 프로즈(내러티브·caption 등 짧은 텍스트)만 직접 작성한다.

### 완성 후

- **`open` 자동 실행 금지** — 절대 경로를 콘솔 응답에 링크로 제시하고 사용자가 원할 때 직접 연다.
- **공유 주의**: 로컬 파일이 기본. 리뷰 본문에 코드·도메인 맥락이 들어가므로 Artifact 등 외부 퍼블리시는 사용자가 명시 요청할 때만.

---

## 참조

- [`review-false-positives.md`](review-false-positives.md) — 오탐 SSOT (기각 우선, prompts 동기화 앵커)
- [`../rules/`](../rules/README.md) — 코드 작성 규칙 (모듈 CLAUDE.md 가 최종 SSOT)
- [`../skills/custom-pr-review/assets/report-template.html`](../skills/custom-pr-review/assets/report-template.html) — §HTML 리포트 템플릿 자산
- [[custom-pr-review]] — 본 정본을 적용하는 리뷰 스킬
