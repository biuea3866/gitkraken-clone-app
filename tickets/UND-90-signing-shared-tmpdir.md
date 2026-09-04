# [UND-90] 서명 테스트가 남의 빌드 임시 파일까지 세지 않게 한다

> wave 13 · 사이즈 S · 의존 없음 · 소유 `app/src/test/kotlin/dev/undine/infrastructure/git/signing/ProcessSigningCommandRunnerSpec.kt`

## 작업 내용 (설계 의도)

`ProcessSigningCommandRunnerSpec#temporarySigningFiles` 가 **시스템 공용 임시 디렉터리**
(`java.io.tmpdir`)를 통째로 스캔해 접두사가 맞는 파일을 센다
(`ProcessSigningCommandRunnerSpec.kt:384`).

```kotlin
Files.list(Path.of(System.getProperty("java.io.tmpdir")))
    .filter { name -> name.startsWith(TEMPORARY_FILE_PREFIX) }
```

**자기 것만 보지 않는다.** Gradle 빌드를 동시에 여러 개 돌리면 다른 빌드가 만든 파일이 이 집합에
들어와, "임시 파일을 남기지 않는다" 류 단언 셋이 함께 깨진다. UND-87 조사 중 실제로 재현했다 —
빌드 셋을 동시에 돌리면 깨지고 하나씩 돌리면 통과한다.

**UND-87 과 뿌리가 다르다.** 그쪽은 Compose scene 잔재가 스펙 사이로 새는 것이고, 이것은 테스트가
**남의 프로세스 산출물을 자기 것으로 세는 것**이다. UND-87 의 태스크 분리로는 고쳐지지 않는다
(결정 G47).

### 왜 지금 고치는가

CI 가 한 대에서 여러 빌드를 돌리거나 사람이 워크트리 둘을 동시에 빌드하면 **아무 잘못 없이
빨간불이 뜬다.** 그때 사람은 "다시 돌려 보자" 로 대응하고, 그 습관이 진짜 실패까지 넘긴다 —
UND-87 이 막으려던 것과 같은 손상이다.

### 하는 일

검사 범위를 **그 테스트가 만든 것**으로 좁힌다. 공용 임시 디렉터리를 훑는 대신 이 실행이 쓸
디렉터리를 지정하거나(`java.io.tmpdir` 를 테스트 전용 경로로 주입), 파일명에 이 실행의 고유
표식을 넣어 그것만 센다.

**전체를 훑고 접두사로 거르는 방식을 유지하지 않는다** — 접두사가 같은 다른 프로세스가 언제든
생길 수 있고, 그때 다시 같은 증상이 난다.

**롤백**: 테스트 수정이라 revert 로 끝난다.

## 의존
- 없음

## 테스트 케이스
- Gradle 빌드를 **동시에 둘 이상** 돌려도 서명 테스트가 깨지지 않는다
- 서명이 실제로 임시 파일을 남기면 여전히 실패한다 — 범위를 좁히느라 검사를 무르게 만들지 않는다
- 강제 종료 경로(응답 없는 프로세스)에서도 남긴 파일을 잡는다
