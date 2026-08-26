# .agent/rules — 코드 작성 규칙 (7개)

Undine 코드를 **작성·리뷰할 때** 따르는 규칙 모음. `paths` frontmatter 로 해당 파일 작업 시 자동 로드된다.

## 자동 로드 경로

| 규칙 | paths |
|---|---|
| architecture-layers | `src/main/kotlin/**/*.kt` |
| kotlin-idioms | `**/src/main/**/*.kt` |
| jgit-usage | `**/infrastructure/**/*.kt` |
| compose-ui | `**/presentation/**/*.kt` |
| credential-handling | `**/*.kt` |
| exception-handling | `**/*Exception.kt` · `**/*ErrorHandler.kt` · `**/application/**/*.kt` |
| testing | `**/src/test/**/*.kt` |

## 목록

| 규칙 | 범위 | 관련 |
|---|---|---|
| [`architecture-layers.md`](architecture-layers.md) | 레이어 경계·의존 방향·Gateway 배치·패키지 구조 | [[custom-kotlin-desktop-engineer]] · [[custom-pr-call-graph-reviewer]] |
| [`kotlin-idioms.md`](kotlin-idioms.md) | null·불변·sealed·스코프 함수·코루틴·로깅 | [[custom-kotlin-desktop-engineer]] |
| [`jgit-usage.md`](jgit-usage.md) | JGit 자원 수명(`use {}`)·스레드·페이징·파괴적 연산 | [[custom-kotlin-desktop-engineer]] |
| [`compose-ui.md`](compose-ui.md) | 상태 끌어올리기·리컴포지션·`LazyColumn` key·디자인 토큰 | [[custom-kotlin-desktop-engineer]] |
| [`credential-handling.md`](credential-handling.md) | SSH 키·토큰 저장·로그 마스킹·호스트 키 검증 | [[custom-pr-review]] |
| [`exception-handling.md`](exception-handling.md) | 도메인 예외 번역·실패 종류 구분·취소 전파 | [[custom-silent-failure-hunter]] |
| [`testing.md`](testing.md) | Kotest 통일·실제 임시 저장소 강제·경계값 | [[custom-affected-test-runner]] |

## 다른 SSOT 와의 경계

| 문서 | 다루는 것 |
|---|---|
| `.agent/docs/conventions.md` | 프로세스/머지 게이트 (p1 판정 근거) |
| `.agent/docs/review-grading.md` | 등급(p0~p5)·verdict 산출 |
| `.agent/rules/` | 코드 작성 규칙 (p1·p2 판정 근거) |
