---
name: custom-self-code-review
description: >
  git push 전에 자가 코드 리뷰를 5축으로 수행한다 — (1) 변경 의도와 티켓 정합성, (2) 테스트 커버,
  (3) 사이드 이펙트 (레이어 경계·호출 그래프·자원 수명), (4) 빌드·툴체인 의존성, (5) 롤백 가능성.
  custom-block-git-push.sh 훅이 push 직전 본 skill 호출을 권장한다.
  Use when:
  - `git push` 직전 — 사용자가 본 skill 호출 또는 hook 메시지 안내로 호출
  - PR 본문 작성 직전 변경 자가 점검
  - 사용자가 "self review 해줘" / "/custom-self-code-review" 호출
---

# custom-self-code-review

`git push` 전 5축 자가 점검. **차단(block) 이 아닌 가이드** — 위반 발견 시 사용자가 수정 후 다시 push 결정한다.

본 문서의 5축 정의는 **무인 검증 노드와 공유하는 SSOT** 다. `.agent/orchestration/workflows/develop-*.toml`
의 축 노드가 `role_file` 로 이 파일을 주입받으므로, 축을 고치면 대화형 자가 리뷰와 무인 검증이 함께 바뀐다.

## when to use

- `git push` 직전 (hook 메시지에서 권장)
- PR 본문 작성 직전 — `/custom-pr-create` 호출 전 사전 검증
- "내가 빠뜨린 거 없나" 자가 점검

## how to apply

### Step 1: diff 수집

```bash
git diff --name-only origin/<base>..HEAD
git diff --stat origin/<base>..HEAD
git log --oneline origin/<base>..HEAD
```

base 는 **PR `baseRefName` 우선**(`gh pr view --json baseRefName`), 불명 시 `main` 가정.

### Step 2: 5축 점검

> **선행 — 오탐 SSOT 대조**: 5축 지적을 적기 전에 [`.agent/docs/review-false-positives.md`](../../docs/review-false-positives.md)
> 를 먼저 읽고, 각 finding 이 그 문서의 오탐/의도된 패턴에 해당하면 **기각/강등**한다.
> 등급(p0~p5)과 verdict 산출은 [`.agent/docs/review-grading.md`](../../docs/review-grading.md) 가 정본이다.

#### Axis 1 — 변경 의도와 티켓 정합성

| 점검 | 검증 |
|---|---|
| 커밋/브랜치명의 `UND-NN` 과 변경 파일이 일치 | `git log` + `git diff` 교차 확인 |
| 티켓 md 의 "작업 내용"·"테스트 케이스"가 코드에 반영 | `tickets/UND-NN-*.md` 대조 |
| 스코프 초과 변경 (티켓 무관 리팩터링) 포함 여부 | `git diff` 의 무관 파일 식별 |
| 티켓이 선언한 소유 패키지 밖을 수정했는가 | 같은 wave 티켓과의 파일 충돌 위험 |

#### Axis 2 — 테스트 커버

| 점검 | 검증 |
|---|---|
| 신규 클래스/함수에 대응 테스트 추가 | `*Spec.kt` 짝 존재 확인 |
| 버그 fix 의 경우 재현 테스트 추가 | 회귀 방지 |
| 티켓 md 의 테스트 케이스가 전부 코드로 존재 | 해피·실패·엣지 각각 |
| Git 연산 테스트가 임시 저장소를 실제로 만들었는가 | Mock 만으로 통과하는 테스트는 근거가 없다 |
| `./gradlew test` 실제 실행 | 출력 첨부 — "통과했다" 단언만은 무효 |

#### Axis 3 — 사이드 이펙트 (레이어 경계 · 호출 그래프 · 자원 수명)

| 점검 | 검증 |
|---|---|
| 레이어 의존 방향 위반 | `domain` 이 다른 **레이어**를 import 하는가 (프레임워크 import 는 허용) |
| 변경 함수의 1-hop 호출 영향 | `custom-pr-call-graph-reviewer` 호출 권장 |
| JGit 자원 수명 | `Repository`·`RevWalk`·`TreeWalk`·`ObjectReader` 가 `use {}` 로 닫히는가 |
| UI 스레드 점유 | Git I/O 가 `Dispatchers.IO` 밖에서 실행되는가 (Compose 프레임 드랍) |
| 상태 변경의 원자성 | 코루틴 취소 시 중간 상태가 남는가 |

#### Axis 4 — 빌드 · 툴체인 의존성

| 점검 | 검증 |
|---|---|
| Gradle 의존성 추가 시 버전 카탈로그 경유 | `gradle/libs.versions.toml` 밖 하드코딩 금지 |
| JDK 툴체인 변경 | `gradle.properties` 의 `undine.jvm` 과 훅 가드 동시 갱신 |
| 패키징 영향 (`dmg`/`msi` 구성 변경) | 네이티브 배포 설정 회귀 |
| 사용자 설정 파일 스키마 변경 | 기존 설정 파일을 읽던 버전과의 하위 호환 |

#### Axis 5 — 롤백 가능성

| 점검 | 검증 |
|---|---|
| 커밋 단위로 `git revert` 가능한가 | 무관 변경이 섞이면 되돌릴 수 없다 |
| 사용자 설정 파일 마이그레이션의 역방향 | 구버전이 신 스키마를 만나면 크래시하는가 |
| 파괴적 Git 연산의 가드 | `reset --hard`·`clean -fd`·force push 에 확인 절차가 있는가 |
| 데이터 보존 | 롤백 시 사용자의 stash·설정 유실이 없는가 |

### Step 3: 리포트 markdown 출력

```markdown
# Self Code Review — <branch>

## 변경 요약
- 파일: <count> / 커밋: <count>
- 핵심 의도: <1줄>

## 5축 점검
### 1. 의도와 티켓 정합성
- [x] / [ ] 티켓 작업 내용 반영
- [x] / [ ] 스코프 초과 없음
- 메모: ...

### 2. 테스트 커버
- [x] / [ ] 신규 클래스 테스트 존재
- [x] / [ ] `./gradlew test` 출력 첨부
- 메모: ...

### 3. 사이드 이펙트
- 레이어 위반: <none | ...>
- JGit 자원 해제: <ok | ...>
- 메모: ...

### 4. 빌드·툴체인 의존성
- 의존성 추가: <none | 카탈로그 경유>
- 설정 스키마 변경: <none | 하위 호환 확인>
- 메모: ...

### 5. 롤백 가능성
- revert 단위: <ok | 혼재>
- 파괴적 연산 가드: yes/no
- 메모: ...

## 권장 다음 단계
- (위반 0) `git push` 진행
- (위반 1+) 수정 후 본 skill 재실행
- PR 본문은 `/custom-pr-create` 로 작성
```

## 안티패턴

- ❌ "내가 봤다" 만으로 5축 패스 — 항상 grep/diff 명령 결과 첨부
- ❌ `./gradlew test` 를 돌리지 않고 "테스트 통과" 단언
- ❌ JGit 자원을 `use {}` 없이 열어 두고 파일 핸들 누수 방치
- ❌ Git I/O 를 UI 코루틴에서 직접 호출
- ❌ 티켓 범위 밖 리팩터링을 같은 커밋에 섞어 revert 불가로 만들기

## 관련

- [[custom-pr-call-graph-reviewer]] — Axis 3
- [[custom-silent-failure-hunter]] — Axis 3
- [[custom-affected-test-runner]] — Axis 2
- [[custom-pr-create]] — push 통과 후 PR 본문
