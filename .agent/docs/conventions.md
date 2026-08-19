# 프로세스 컨벤션 — 작업 전 자가 점검 4개 룰

이 4개 룰은 **프로세스/머지 게이트**다. PR/커밋 전에 자가 점검하며, 위반 시 harness 훅이 막는다.

> **코드 작성 컨벤션**(레이어·JGit·Compose·테스트 형태)은 여기가 아니라 [`.agent/rules/`](../rules/README.md) 참조.

## Rule 1 — JDK 정합

`./gradlew` 실행 전 JDK 가 `gradle.properties` 의 `undine.jvm` 과 일치해야 한다.

- `custom-gradlew-jvm-guard.sh` 훅이 미스매치를 차단한다.
- `gradle.properties` 의 `undine.jvm` 이 **단일 진실 공급원**이다 — 훅에 하드코딩하지 않는다.
- macOS 전환: `export JAVA_HOME=$(/usr/libexec/java_home -v <ver>)`

## Rule 2 — 티켓 접두사 필수

커밋·PR 제목의 기본 포맷:

- `[UND-NN] - {type}: {작업 내용}`

예: `[UND-14] - feat: 커밋 그래프 레인 렌더링`

타입 키워드: `feat` / `fix` / `refactor` / `chore` / `docs` / `test` / `perf` / `build`.

**예외**: 티켓 없는 작업은 메시지에 `no-ticket` 명시 (예: `no-ticket: docs only`),
PR 제목은 `[NO-TICKET] - docs: 작업 내용`.

`custom-check-commit-prefix.sh` 훅이 커밋·`gh pr create` 단계에서 검증한다.
콜론 앞 공백(`feat :`)과 다중 접두사(`[A][B]`)도 함께 차단된다.

## Rule 3 — 티켓 범위와 파일 소유

**한 파일은 한 티켓만 수정한다.** 같은 wave 의 두 티켓이 같은 파일을 건드리면 머지 충돌이 난다.

- 티켓 착수 전 `tickets/README.md` 의 wave 표에서 **자기 티켓의 소유 패키지**를 확인한다.
- 소유 밖 파일을 고쳐야 하면 → 그 파일을 소유한 티켓이 먼저 끝나야 한다. 임의로 고치지 않는다.
- 공통 파일(빌드 스크립트·DI 배선·앱 진입점) 수정은 **통합 티켓**에만 허용된다.

위반은 [[custom-self-code-review]] Axis 1 에서 잡는다.

## Rule 4 — 파괴적 변경 명시

되돌릴 수 없는 변경이 포함된 PR 은 본문 `### 추가 유의사항` 에 다음을 명시한다 (없으면 머지 금지):

| 변경 | 명시할 것 |
|---|---|
| 사용자 설정 파일 스키마 변경 | 구버전이 신 스키마를 만났을 때 동작 + 역방향 경로 |
| 파괴적 Git 연산 도입 (`reset --hard`·`clean -fd`·force push) | 사용자 확인 절차 + 되돌리기 경로 |
| 자격증명 저장 위치·형식 변경 | 기존 저장분의 이관 방법 |
| 기본 단축키·기본 동작 변경 | 기존 사용자가 겪을 차이 |

## Quick Reference

| 작업 직전 호출 | 스킬/에이전트 |
|---|---|
| `git commit` | `custom-check-commit-prefix.sh` (자동 — 차단) |
| 변경 범위 테스트 | `/custom-affected-test-runner` |
| `git push` 직전 | `/custom-self-code-review` (5축 자가 점검) |
| `**/infrastructure/**` 편집 후 | `custom-kotlin-desktop-engineer` (JGit 자원·스레드) |
| 에러 처리 편집 후 | `custom-silent-failure-hunter` |
| `~Gateway.kt` 편집 후 | `custom-pr-call-graph-reviewer` (interface↔구현 비대칭) |
| PR 생성 직전 | `/custom-pr-create` |
| 올라간 PR 리뷰 | `/custom-pr-review <N>` |
| 신규 기능 착수 | `custom-design-doc-author` → `custom-ticket-decomposer` |
| 릴리즈 | `/custom-release-tagger` |
