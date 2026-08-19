# AGENTS.md — Codex 진입점

본 레포의 공통 지침은 **`CLAUDE.md` 가 단일 진실 공급원(SSOT)** 이다. 내용을 여기에 복제하지 않는다 (두 벌이 되면 반드시 어긋난다).

**작업 시작 전 반드시 읽을 것:**

| 문서 | 내용 |
|---|---|
| `CLAUDE.md` | 프로젝트 구성·빌드 전제·아키텍처·워크플로우·코드 규칙 인덱스 |
| `.agent/docs/conventions.md` | 프로세스/머지 게이트 (JDK·티켓 접두사·파일 소유·파괴적 변경) |
| `.agent/rules/*.md` | 코드 작성 규칙 (레이어·JGit·Compose·Kotlin·테스트) |
| `.agent/HARNESS.md` | 하네스 구조·변경 절차·SSOT 맵 |
| `tickets/README.md` | 티켓 목록·의존 DAG·wave 배치 (작업 단위 SSOT) |

하네스 자산의 SSOT 는 `.agent/` 다 — 레포 지식·규칙·에이전트·스킬·훅이 전부 거기 있고, Codex 도 그대로 따른다.
`.codex/agents/*.toml` 은 `.agent/agents/*.md` 에서 **생성된 투영본**이라 직접 고치지 않는다.

## Codex 고유 사항

- **훅**: `.agent/hooks/` 의 훅 중 Read 전용 `custom-secrets-read-guard.sh` 를 제외한 전부가 `.codex/config.toml` 에 등록돼 있다 (Codex 엔 Read 도구가 없고 `custom-block-env-read.sh` 가 커버). 커밋 접두사·mainline 머지·시크릿 읽기/편집을 차단한다.
  **원본 스크립트는 한 줄도 수정하지 않는다** — 파일 편집 계열만 `.codex/hooks/*-codex.sh` 래퍼가 payload 를 변환해 원본에 위임한다.
  **첫 실행 시 훅 신뢰(hook trust) 승인이 필요하다. 승인하지 않으면 가드가 조용히 비활성 상태로 돈다** — 반드시 승인 여부를 확인하고 시작할 것 (상세: `.codex/config.toml` 헤더).
- **파일 편집**: `apply_patch` 는 한 번에 여러 파일을 바꾼다. 시크릿 가드는 패치에 담긴 **모든** 대상 파일을 검사한다.
- **git worktree 에서 작업 중이라면**: Codex 는 메인 워크트리 루트의 `.codex/config.toml` 만 읽고, 신뢰되지 않은 경로에서는 프로젝트 설정을 조용히 무시한다.
- **서브에이전트**: `.codex/agents/*.toml` 은 생성물이므로 직접 수정하지 말고, `.agent/agents/*.md` 를 고친 뒤 `.agent/tools/sync-vendors.py` 를 돌린다 (개수는 `.agent/agents/README.md` 가 SSOT).
- **그래프 오케스트레이션**: `.agent/orchestration/workflows/*.toml` + `.agent/orchestration/runner/run-graph.py` — 노드마다 claude/codex 와 모델을 지정해 실행한다 (`.agent/orchestration/README.md`).
- **MCP**: 프로젝트 `.mcp.json` 은 Claude Code 포맷이다. Codex 에서 쓰려면 `codex mcp add` 로 개인 설정에 등록해야 한다.

## 하지 말 것

- `git push` · `gh pr merge` · mainline(`main`) 머지 — 훅이 승인을 요구하며, 헤드리스 실행에서는 승인할 사람이 없다. 사용자에게 넘긴다.
- `.env` / credentials / `*.pem` 류 읽기·편집 — 차단된다. 우회하지 말고 사용자에게 요청한다.
- JDK 를 맞추지 않고 `./gradlew` 실행 — `gradle.properties` 의 `undine.jvm` 과 `$JAVA_HOME` 이 다르면 훅이 차단한다.
- 티켓이 소유하지 않은 파일 수정 — 같은 wave 의 다른 티켓과 충돌한다 (`tickets/README.md` 확인).
