# CLAUDE.md

Undine — Kotlin + Compose Desktop + JGit 로 만드는 데스크톱 Git 클라이언트.
본 문서가 이 레포 공통 지침의 **단일 진실 공급원(SSOT)** 이다. Codex 진입점 `AGENTS.md` 는 여기를 가리킨다.

## 프로젝트 구성

| 항목 | 값 |
|---|---|
| 언어 · 런타임 | Kotlin / JVM (버전 SSOT: `gradle.properties` 의 `undine.jvm`) |
| UI | Compose Multiplatform for Desktop |
| Git 접근 | JGit (순수 Java 구현 — 시스템 `git` 바이너리에 의존하지 않는다) |
| 빌드 | Gradle (버전 핀은 `gradle/libs.versions.toml` 카탈로그) |
| 테스트 | Kotest (JUnit 신규 사용 금지) |
| 정적 분석 | detekt |

단일 Gradle 프로젝트다. 모듈을 늘릴 때는 `settings.gradle.kts` 가 명세의 SSOT 다.

## 빌드 전제 조건 (필수)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # gradle.properties 의 undine.jvm 과 일치해야 한다
./gradlew build
```

`custom-gradlew-jvm-guard.sh` 훅이 `./gradlew` 실행 직전 JDK 정합을 검사하고 미스매치를 **차단**한다.
요구 버전은 훅에 하드코딩돼 있지 않고 `gradle.properties` 에서 읽는다 — 툴체인을 올릴 때 한 곳만 고친다.

## 아키텍처 (레이어)

```
presentation → application → domain ← infrastructure
```

- **domain** 은 프레임워크(JGit·Compose·코루틴)를 자유롭게 쓴다. 대신 **다른 레이어**(application·infrastructure·presentation)를 import 하면 p1 이다.
- Git 은 외부 시스템이므로 계약 이름은 `~Gateway.kt`(domain interface) / `~GatewayImpl.kt`(infrastructure) 다.
- presentation 은 UseCase 만 호출한다. Gateway 직접 주입은 위반이다.

상세는 [`.agent/rules/architecture-layers.md`](.agent/rules/architecture-layers.md).

## 작업 단위 — 티켓

**모든 코드 변경은 티켓 1건에 대응한다.** 착수 전 `tickets/README.md` 에서 자기 티켓의
**소유 패키지**를 확인한다 — 같은 wave 의 다른 티켓과 파일이 겹치면 머지 충돌이 난다.

```
tickets/README.md          # 목록 · 의존 DAG · wave 배치 (작업 단위 SSOT)
tickets/UND-NN-<slug>.md   # 개별 티켓 (작업 내용 · 다이어그램 · 테스트 케이스)
```

브랜치·커밋 접두사는 `[UND-NN]` 이다 (`custom-check-commit-prefix.sh` 훅이 강제).

## 개발 워크플로우 (Development)

티켓 1건을 스펙 → 구현 → 검증까지 한 루프로 돈다.

```
/custom-develop-orchestrator UND-NN   → ① 스펙  ② [승인 게이트]  ③ 구현
                                         ④ 1차 5축 병렬 검증 → verdict
                                         ⑤ REQUEST_CHANGES 면 수정·2차 검증
                                         ⑥ [최종 검토 게이트]
/custom-affected-test-runner          → 변경 범위 테스트 실제 실행
/custom-self-code-review              → push 전 5축 자가 점검
/custom-pr-create                     → Draft PR
/custom-pr-review <N>                 → 머지 전 다축 리뷰
```

- **사람 게이트가 워크플로우 경계**다. 무인 노드는 커밋·push·머지를 하지 않는다.
- 5축 정의의 SSOT 는 [`.agent/skills/custom-self-code-review/SKILL.md`](.agent/skills/custom-self-code-review/SKILL.md) 하나다 —
  대화형 자가 리뷰와 무인 검증 노드가 같은 기준을 쓴다.
- 등급(p0~p5)·verdict 산출은 [`.agent/docs/review-grading.md`](.agent/docs/review-grading.md) 가 정본이다.

## 분석 워크플로우 (Analysis Workflow)

코드 변경 없이 정보 수집·결론 도출이 목적인 요청은 **Analysis Workflow** 로 처리한다.
레포 내부에서 자체 구동되며 글로벌 `~/.claude/` 의존 없이 동작한다 (clone 단독 동작).

### 분기 판단

- **Analysis**: "분석해봐", "조사해줘", "파악해줘", "찾아봐", "확인해봐", "가능한지 확인", "원인이 뭔지", "왜 발생하는지", "전체 흐름", "어떻게 동작하는지" → 아래 워크플로우.
- **Development**: "수정해줘", "구현해줘", "추가해줘", "만들어줘", "리팩토링해줘" → 위 개발 워크플로우.

모호하면 사용자에게 묻지 않고 요청의 주된 의도로 오케스트레이터가 판단한다.

### 절차 (요약 — 상세 정본: [`.agent/docs/analysis-workflow.md`](.agent/docs/analysis-workflow.md))

```
Step 1   오케스트레이터                investigation-brief 작성
Step 2   [사용자 승인]
Step 3   custom-research-investigator  N개 병렬 조사
Step 4   [필요 시 연쇄 조사]
Step 4.5 검증 게이트                   finding 파일 존재 확인
Step 5   custom-analysis-synthesizer   종합 분석 보고서 → analysis/ 아카이브
Step 6   [코드 변경 필요 시 → Development 잡 전환]
Step 7   custom-retrospective-analyst  프로세스 회고 → retrospectives/ 아카이브 (job close)
```

- **Job workspace**: `.claude-local/jobs/<job-name>/` (collaboration.log + context/). gitignore 로 격리, `permissions.allow` 로 무승인 편집.
- **협업 규약**(로그 형식·필수 이벤트·산출물): [`.agent/docs/collaboration-protocol.md`](.agent/docs/collaboration-protocol.md)
- **라이프사이클·아카이브**: [`.agent/docs/job-lifecycle.md`](.agent/docs/job-lifecycle.md)
- **결과 산출물**(`analysis/`·`retrospectives/`)은 gitignore 로 로컬 전용. 기계장치(`.agent/**`·`.claude/**`)는 tracked.

## 프로세스 게이트 4개 룰

[`.agent/docs/conventions.md`](.agent/docs/conventions.md) 가 정본이다.

1. **JDK 정합** — `gradle.properties` 의 `undine.jvm` 과 `$JAVA_HOME` 일치
2. **티켓 접두사** — `[UND-NN] - <type>: <요약>` (티켓 없으면 `no-ticket` 명시)
3. **파일 소유** — 한 파일은 한 티켓만 수정. 공통 파일은 통합 티켓 전용
4. **파괴적 변경 명시** — 설정 스키마 변경·파괴적 Git 연산·자격증명 취급 변경은 PR 본문에 명시

## 코드 작성 규칙 (rules)

코드를 **작성·리뷰하기 직전** 해당 축의 규칙을 참조한다 — `.agent/rules/` ([README](.agent/rules/README.md)).
`paths` frontmatter 로 대상 파일 작업 시 자동 로드된다.

- [`architecture-layers.md`](.agent/rules/architecture-layers.md) — 레이어 경계·의존 방향·Gateway 배치·패키지 구조
- [`kotlin-idioms.md`](.agent/rules/kotlin-idioms.md) — null·불변·sealed·스코프 함수·코루틴·로깅
- [`jgit-usage.md`](.agent/rules/jgit-usage.md) — 자원 수명(`use {}`)·스레드·페이징·파괴적 연산
- [`compose-ui.md`](.agent/rules/compose-ui.md) — 상태 끌어올리기·리컴포지션·`LazyColumn` key·디자인 토큰
- [`credential-handling.md`](.agent/rules/credential-handling.md) — SSH 키·토큰 저장·로그 마스킹·호스트 키 검증
- [`exception-handling.md`](.agent/rules/exception-handling.md) — 도메인 예외 번역·실패 종류 구분·취소 전파
- [`testing.md`](.agent/rules/testing.md) — Kotest 통일·실제 임시 저장소 강제·경계값

## 가이드라인

LLM 협업 가이드라인 5룰(먼저 읽기 / 코딩 전 생각 / 단순함 우선 / 외과적 변경 / 목표 주도 실행)과
이름 붙인 실패 모드는 `custom-session-start.sh` 훅이 매 세션 시작 시 본문을 출력한다.
원문: [`.agent/guidelines/llm-collaboration.md`](.agent/guidelines/llm-collaboration.md).

## 하네스

하네스 자산의 SSOT 는 `.agent/` 다 — `.claude/{agents,skills,rules}` 와 `.codex/agents` 는
`.agent/tools/sync-vendors.py` 가 만드는 **생성 투영본**이며 직접 편집하면 훅이 차단한다.

```bash
.agent/tools/sync-vendors.py              # .agent/ 를 고친 뒤 반드시 실행
bash .agent/scripts/validate-harness.sh   # 카운트·배선·wikilink 정합 (경고 0 이어야 커밋)
```

구조·변경 절차·진단 rubric: [`.agent/HARNESS.md`](.agent/HARNESS.md)
어디서 무엇이 SSOT 인지: [`.agent/docs/ssot-map.md`](.agent/docs/ssot-map.md)
셋업: [`.agent/onboarding.md`](.agent/onboarding.md)
