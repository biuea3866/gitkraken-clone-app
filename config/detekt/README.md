# detekt 설정

| 파일 | 역할 |
|---|---|
| `detekt.yml` | 기본 설정(`buildUponDefaultConfig = true`) 위에 얹는 의도적 예외 |
| `detekt-baseline-test.xml` | **테스트 소스**의 기존 위반 고정 (아래 참조) |

메인 소스는 `:app:detekt` 가, 테스트 소스는 `:app:detektTest` 가 본다. 둘 다 `check` 에 걸려
있으므로 `./gradlew build` 가 실행하며, CI 는 그 `build` 를 그대로 돌린다 — 별도 워크플로
설정이 없다.

## 테스트 소스 baseline (`detekt-baseline-test.xml`)

`detektTest` 는 타입 해석까지 도는 대신 기본으로 `check` 에 걸려 있지 않았다. 테스트 소스가
사실상 검사 밖에 있었고, **검사를 안 도는 코드가 검사의 근거**인 상태였다 (UND-86).

기존 위반을 한 번에 고치면 이 변경의 크기를 넘고, 고치는 과정에서 테스트 의미를 바꿀 위험이
있다. 그래서 기존 위반만 baseline 으로 고정하고 **신규 위반부터 빌드를 실패**시킨다.

- **baseline 항목 수: 74** — detekt 가 세는 159 weighted issues 를 규칙+시그니처 단위로 묶은
  수다. 두 숫자는 세는 단위가 달라서 일치하지 않는다.
- `TestSourceAnalysisBaselineSpec` 이 이 문서의 숫자와 실제 XML 항목 수를 대조한다. baseline 을
  다시 만들면 이 숫자도 같이 고쳐야 테스트가 통과한다 — **빚이 조용히 늘지 않게** 하는 장치다.

### 규칙별 내역

| 규칙 | 항목 | 무엇인가 |
|---|---|---|
| `InjectDispatcher` | 69 | 테스트가 `Dispatchers.Unconfined`·`Default`·`IO` 를 직접 쓴다 |
| `IgnoredReturnValue` | 3 | 반환값을 쓰지 않고 호출한다 |
| `UseOrEmpty` | 1 | `?: ""` 를 `orEmpty()` 로 쓸 수 있다 |
| `HasPlatformType` | 1 | 플랫폼 타입을 명시적으로 선언하지 않았다 |

### 줄이는 방법

baseline 은 **영구 면제가 아니라 빚 목록**이다. 다음 순서로 줄인다.

1. **단건부터 없앤다** — `UseOrEmpty`·`HasPlatformType` 은 각 1건이고 테스트 의미를 바꾸지
   않는다. 고치고 해당 `<ID>` 줄을 지운다.
2. **`IgnoredReturnValue` 3건** — 반환값을 실제로 검증하거나, 검증할 것이 없으면 호출 자체가
   필요한지 본다. 반환값을 버리는 호출은 테스트가 무엇을 보는지 흐린다.
3. **`InjectDispatcher` 69건** — 가장 크다. 테스트가 dispatcher 를 직접 고르는 대신 픽스처가
   주입받게 바꾼다. 파일 단위로 나눠 진행하고, 한 파일을 정리하면 그 파일의 `<ID>` 줄을
   모두 지운다.
4. baseline 을 **다시 만들지 말고 줄을 지운다** — 재생성은 그 사이 생긴 새 위반까지 함께
   고정해 버린다. 손으로 지우면 줄어든 만큼만 줄어든다.
5. 지운 뒤 이 문서의 **항목 수와 규칙별 내역**을 함께 고친다.

전부 비면 baseline 설정(`app/build.gradle.kts` 의 `testDetektBaseline`)과 이 절을 지운다.

### 다시 만들어야 할 때

규칙 세트를 올려 기존 위반의 분류 자체가 바뀐 경우에만 재생성한다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:detektBaselineTest
```

재생성은 **그 시점의 모든 위반을 고정**한다 — 새로 들어온 위반이 섞이지 않았는지 diff 로
확인하고, 이 문서의 항목 수를 고친다.
