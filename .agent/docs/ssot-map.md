# SSOT Map — 단일 진실 공급원 가이드

"어디서 무엇이 진실인지" 한 곳에서 본다. 의심스러우면 이 표를 우선 조회.

## 코드/빌드

| 정보 | SSOT |
|---|---|
| JDK 툴체인 버전 | `gradle.properties` 의 `undine.jvm` — 훅 가드(`custom-gradlew-jvm-guard.sh`)가 이 값을 읽는다 |
| 라이브러리 버전 핀 | `gradle/libs.versions.toml` (버전 카탈로그) — `build.gradle.kts` 에 버전 하드코딩 금지 |
| Gradle 모듈 명세 | `settings.gradle.kts` |
| 정적 분석 설정 | `config/detekt/detekt.yml` |
| 패키징(dmg/msi) 설정 | `app/build.gradle.kts` 의 `compose.desktop.application` 블록 |

## 애플리케이션

| 정보 | SSOT |
|---|---|
| 레이어 경계·패키지 배치 | `.agent/rules/architecture-layers.md` |
| Git 접근 계약 | `app/src/main/kotlin/dev/undine/domain/*Gateway.kt` (interface) — 구현은 `infrastructure/**/*GatewayImpl.kt` |
| 사용자 설정 파일 스키마 | `app/src/main/kotlin/dev/undine/domain/Settings.kt` — 위치·형식 변경 시 하위 호환 확인 필수 |
| 디자인 토큰 (색·간격·타이포) | `presentation/design/` — Composable 에 색 하드코딩 금지 |
| 단축키 매핑 | 단축키 정의 파일 1곳 — 화면별 분산 금지 |

## 작업 관리

| 정보 | SSOT |
|---|---|
| 티켓 정의·의존 DAG·wave 배치 | `tickets/README.md` + `tickets/UND-NN-*.md` |
| 티켓별 소유 패키지 (파일 충돌 방지) | `tickets/README.md` 의 wave 표 |
| 설계 문서 | `docs/design/` |
| PR 본문 포맷 | `.github/pull_request_template.md` |

## 워크플로 (사람 절차)

| 작업 | SSOT 절차 |
|---|---|
| 요구사항 → 설계 문서 | `custom-design-doc-author` 에이전트 |
| 설계 문서 → 티켓 분해 | `custom-ticket-decomposer` 에이전트 |
| 티켓 1건 개발 루프 | `/custom-develop-orchestrator UND-NN` 스킬 |
| 신규 Kotlin/Compose 코드 작성 / 리뷰 | `custom-kotlin-desktop-engineer` 에이전트 + `.agent/rules/` |
| 변경 범위 테스트 | `/custom-affected-test-runner` 스킬 |
| `git push` 직전 자가 점검 | `/custom-self-code-review` 스킬 |
| PR 생성 | `/custom-pr-create` 스킬 |
| 올라간 PR 리뷰 | `/custom-pr-review <N>` 스킬 |
| 릴리즈 태그 | `/custom-release-tagger` 스킬 |

## Harness 자체

| 정보 | SSOT |
|---|---|
| harness 구조/진단 rubric | `.agent/HARNESS.md` |
| 하네스 자산 전체 (agents·skills·rules·hooks·docs·templates) | `.agent/` — `.claude/{agents,skills,rules}`·`.codex/agents` 는 생성 투영본 |
| 벤더 투영 생성 규칙 | `.agent/tools/sync-vendors.py` (`--check` 로 드리프트 판정) |
| 멀티 에이전트 워크플로우·러너·벤더 어댑터 | `.agent/orchestration/README.md` |
| 노드 실행 구성 (vendor·model·effort·권한) | `.agent/orchestration/profiles.toml` — 워크플로우 노드는 `profile` 만 선언 |
| 티켓 의존·소유 (티켓 DAG 입력) | 각 `tickets/UND-NN-*.md` 의 헤더 줄 — `tickets/README.md` 표는 파생 |
| 권한 화이트리스트 + hooks 등록 | `.claude/settings.json` (Claude) · `.codex/config.toml` (Codex) — 스크립트 본체는 `.agent/hooks/` |
| 작업 가이드라인 | `.agent/guidelines/llm-collaboration.md` — **본문은 `custom-session-start.sh` hook 이 매 세션 echo** |
| 프로세스 게이트 4개 룰 | `.agent/docs/conventions.md` |
| 코드 작성 규칙 | `.agent/rules/` — 축별 파일 |
| 리뷰 등급·verdict | `.agent/docs/review-grading.md` |
| 리뷰 오탐 패턴 카탈로그 | `.agent/docs/review-false-positives.md` (충돌 시 **기각이 이긴다**) |
| 리뷰 규율(판정·출력·브리핑·체크리스트·HTML) | `.agent/docs/review-discipline.md` (HTML 템플릿 자산은 `.agent/skills/custom-pr-review/assets/`) |
| 5축 자가 리뷰 정의 | `.agent/skills/custom-self-code-review/SKILL.md` — 무인 검증 노드가 `role_file` 로 주입받는다 |
| 카운트·cross-file 정합 검증 | `.agent/scripts/validate-harness.sh` |
| 온보딩 | `.agent/onboarding.md` |

## 주의

- 본 SSOT Map 은 **링크/위치만** 가리킨다. 실제 값/내용은 가리킨 곳을 직접 조회한다.
- 위치가 바뀌면 **이 문서를 가장 먼저 갱신** — 하네스의 모든 에이전트가 본 map 을 가정한다.
- "정확한 위치를 모르겠다" 는 답변이 나오기 시작하면 SSOT Map 갱신 누락 신호다.
