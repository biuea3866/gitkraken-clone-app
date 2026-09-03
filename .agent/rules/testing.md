---
paths:
  - "**/src/test/**/*.kt"
---

# 테스트 규칙

**테스트 프레임워크는 Kotest 로 통일한다.** 신규 테스트에 JUnit(`@Test`·`org.junit.*`)을 쓰지 않는다.

| 대상 | 스타일 | 도구 |
|---|---|---|
| 도메인 모델·순수 로직 (레인 배치, 상태 전이) | `BehaviorSpec` / `FunSpec` | Kotest + MockK |
| UseCase | `BehaviorSpec` | Kotest + MockK (Gateway 모킹) |
| Gateway 구현 (JGit) | `FunSpec` | Kotest + **임시 실제 저장소** |
| Compose 화면 | `FunSpec` | Kotest + Compose UI 테스트 |
| 시나리오 (열기→스테이징→커밋→브랜치→병합) | `BehaviorSpec` | 임시 저장소 E2E |

## 규칙

1. **Git 연산 테스트는 실제 저장소를 만든다.** `Git.init()` 으로 임시 디렉토리에 저장소를 만들고
   커밋을 쌓아 검증한다. **JGit 을 Mock 으로 대체한 테스트는 근거가 없다** — 이 규칙 위반은 p2 다.
   임시 디렉토리는 `tempdir()` 로 만들어 테스트 종료 시 정리한다.
2. **네트워크를 타지 않는다.** fetch/push 테스트는 로컬 파일 경로를 원격으로 등록해 검증한다.
   실제 호스트에 붙는 테스트는 CI 에서 불안정해진다.
3. **단위 vs 통합 분리.** 순수 로직(레인 배치·정렬)은 저장소 없이 검증한다 — 빠르고 결정적이다.
4. **티켓의 테스트 케이스와 1:1 대응.** 티켓 md 에 적힌 해피·실패·엣지 케이스가 전부 코드로 존재해야 한다.
5. **경계값을 반드시 넣는다.** 빈 저장소(커밋 0건), 커밋 1건, 병합 커밋(부모 2개), 고아 브랜치,
   detached HEAD — Git 도메인은 경계에서 깨진다.
6. **테스트 이름은 행위를 서술한다.** "병합 커밋은 두 부모 레인을 잇는다" 처럼 읽히게 쓴다.
7. **시간·난수에 의존하지 않는다.** 커밋 타임스탬프는 고정값을 주입한다.

## 실행

```bash
./gradlew build                       # 전체 — test + composeTest + detekt
./gradlew test --tests '*GraphLane*'  # 선별
```

> **`./gradlew test` 는 전체가 아니다.** Compose scene 을 띄우는 스펙 21개는 별도 태스크
> `composeTest` 에서 **스펙별 JVM** 으로 돈다 — 한 JVM 에 몰아 넣으면 앞선 scene 의 잔재가
> 다음 스펙으로 새어 `AppAssemblySpec` 이 산발적으로 깨졌다 (UND-87). 전체를 보려면
> `./gradlew build`(또는 `check`)를 쓴다. 대상은 소스의 `runComposeUiTest` 사용 여부로
> 자동 판별하므로 목록을 관리하지 않는다.

"테스트 통과" 단언은 **실행 출력과 함께**만 유효하다 — [[custom-self-code-review]] Axis 2.
