# [UND-87] 전체 실행에서만 깨지는 `AppAssemblySpec` 을 잡는다

> wave 12 · 사이즈 M · 의존 없음 · 소유 `app/src/test/kotlin/dev/undine/presentation/AppAssemblySpec.kt` · 테스트 실행 설정(`app/build.gradle.kts`)

## 작업 내용 (설계 의도)

`AppAssemblySpec > 저장소를 바꾸면 이전 저장소의 Undo 이력이 따라오지 않는다` 가
**전체 실행에서만 산발적으로** 실패한다.

```
java.lang.IndexOutOfBoundsException at InlineClassHelper.kt:42
  Caused by: java.lang.IndexOutOfBoundsException at InlineClassHelper.kt:42
```

`InlineClassHelper` 는 Compose 내부다. **관측된 빈도: 전체 빌드 4회 중 1회.** 같은 커밋에서
`--tests '*AppAssemblySpec*'` 로 격리 실행하면 통과한다.

**산발적 실패는 검사를 무력화한다.** 빨간불이 코드 때문인지 운 때문인지 모르게 되면, 사람은
곧 "다시 돌려 보자" 로 대응하고 **진짜 실패도 그렇게 넘긴다.** 통과율이 아니라 신뢰가 문제다.

### 같은 성질의 다른 관측

이 레포의 테스트는 **동시 실행에 안전하지 않다.** gradle 빌드 셋을 동시에 돌렸을 때
`ProcessSigningCommandRunnerSpec` 의 "임시 파일을 남기지 않는다" 계열 셋이 함께 깨졌고,
하나씩 돌리면 통과했다. 공유 임시 경로를 보는 단언으로 보인다.

두 현상이 같은 뿌리인지(테스트 간 공유 상태) 다른지 먼저 가른다.

### 하는 일

1. **재현 조건을 좁힌다** — 실행 순서·병렬도(`maxParallelForks`)·앞선 Compose 테스트의 잔여
   상태 중 무엇이 트리거인지. 재현되지 않으면 고쳤는지 알 수 없으므로 이것이 먼저다.
2. 원인에 맞게 고친다 — 테스트가 공유 상태를 남기면 그 정리를, Compose scene 수명 문제면
   그 격리를. **`@Ignore` 나 재시도로 덮지 않는다** — 그것은 검사를 끄는 것이다.
3. `ProcessSigningCommandRunnerSpec` 의 동시 실행 취약성도 같은 기준으로 본다.

**롤백**: 테스트 수정이라 revert 로 끝난다.

## 의존
- 없음

## 테스트 케이스
- 재현 조건을 찾은 뒤, 그 조건에서 **고치기 전에는 실패하고** 고친 뒤에는 통과한다
- 전체 실행을 연속 10회 돌려 실패 0회다 (빈도가 1/4 이므로 10회면 신호가 나온다)
- 같은 저장소 전환 시나리오의 검증 의도는 그대로다 — 단언을 약화해 통과시키지 않는다
- gradle 빌드를 동시에 여러 개 돌려도 서명 테스트가 깨지지 않는다 (또는 그 취약성이 별개임을 근거와 함께 기록한다)
