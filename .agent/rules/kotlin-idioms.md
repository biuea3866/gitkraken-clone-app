---
paths:
  - "**/src/main/**/*.kt"
---

# Kotlin 언어 관용구 · 로깅 규칙

프로덕션 Kotlin 코드를 작성할 때 따른다. (테스트 코드는 [[testing]] 담당)

> **자동 로드**: `src/main` 하위 모든 `.kt`.
> 기준은 kotlinlang.org 공식 코딩 컨벤션이다. 신규·수정 코드 기준 — 기존 코드 소급 지적 없음.
> **역할 분리**: 포맷·import·라인 길이·매직넘버는 detekt 가 강제한다. 레이어 경계는
> [[architecture-layers]], JGit 자원은 [[jgit-usage]], UI 는 [[compose-ui]] — 중복 서술하지 않는다.

## 언어 관용구

1. **null 처리 — `!!` 금지.** 부재는 `?: throw 도메인예외` 가 표준이다.
   `requireNotNull`/`checkNotNull` 은 "이 시점엔 반드시 있어야 한다" 사전조건 단언용으로만 쓴다.
   nullable `Boolean?` 분기는 `if (value == true)` 로 명시 비교한다.
2. **불변 우선.** `val` 기본, 컬렉션은 읽기 전용 타입(`List`·`Map`)으로 노출한다.
   가변이 필요하면 내부에만 두고 외부에는 방어 복사 또는 읽기 전용 뷰를 준다.
3. **data class 는 값 객체에만.** 식별자로 동일성이 정해지는 모델에 `data class` 를 쓰면
   `equals` 가 전체 필드 비교가 돼 의도와 어긋난다.
4. **sealed 로 상태를 닫는다.** 로딩/성공/실패처럼 유한한 상태는 `sealed interface` 로 표현해
   `when` 이 exhaustive 해지도록 한다. `else ->` 로 새 케이스를 조용히 삼키지 않는다.
5. **확장 함수는 변환에.** 도메인 ↔ JGit 타입 변환은 확장 함수(`RevCommit.toCommit()`)로 두되,
   확장 함수가 도메인 규칙을 담지 않게 한다.
6. **스코프 함수는 목적에 맞게.** `let`(널 가드), `apply`(구성), `use`(자원), `also`(부수효과).
   중첩 2단계를 넘기면 이름 있는 지역 변수로 푼다.
7. **함수 시그니처** — 오버로드 대신 default parameter, Boolean·동일 타입 다수 인자엔 named arguments.
   계산이 싸고 throw 하지 않고 상태가 불변이면 property, 아니면 함수.
8. **매직 넘버·문자열 금지.** 상수 또는 enum 으로 이름을 준다.

## 코루틴

9. **`GlobalScope` 금지.** 화면 수명에 묶인 스코프를 쓴다.
10. **`runBlocking` 은 진입점 한정.** `main` 이나 테스트 밖에서 쓰지 않는다 — UI 를 멈춘다.
11. **디스패처를 명시한다.** Git I/O 는 `Dispatchers.IO`, 상태 갱신은 메인. 함수가 자기 디스패처를
    스스로 정하면(`withContext`) 호출부가 실수할 여지가 없다.
12. **취소를 존중한다.** 긴 루프(대량 커밋 순회)에서 `ensureActive()` 를 확인하고,
    `CancellationException` 을 삼키지 않는다.

## 로깅

13. **구조화 로깅.** 최소 event 이름과 대상(저장소 경로·참조명)을 남긴다.
14. **조용한 swallow 금지.** 빈 `catch` 는 금지하고, 의도된 무시는 주석으로 이유를 남긴다.
15. **자격증명·원격 URL 토큰을 로그에 넣지 않는다.** [[credential-handling]] 참조.
