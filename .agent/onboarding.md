# 온보딩 — Undine Harness

새 환경 셋업 가이드. **5분 안에** harness 동작 환경을 갖춘다.

## Step 1 — 사전 준비 (1분)

| 도구 | 확인 명령 | 비고 |
|---|---|---|
| Claude Code CLI | `claude --version` | 최신 설치: https://claude.com/claude-code |
| GitHub CLI | `gh --version` + `gh auth status` | PR/issue 자동화에 필요 |
| JDK 21 | `/usr/libexec/java_home -V` | 요구 버전 SSOT 는 `gradle.properties` 의 `undine.jvm` |
| Python 3 | `python3 --version` | 훅이 stdin JSON 파싱에, 러너가 DAG 실행에 사용 (3.11+ — `tomllib` 필요) |
| Node 20+ / `npx` | `node --version` + `npx --version` | `.mcp.json` 의 context7 MCP 가 `npx` 로 기동 |

Homebrew 자체가 미설치인 경우 먼저:
```bash
# macOS — 공식 설치 스크립트 (`brew --version` 확인 후 패스)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

JDK 누락 시:
```bash
brew install --cask corretto21
```

Node 누락 시 (nvm 권장):
```bash
nvm install 20 && nvm alias default 20
```

## Step 2 — clone (1분)

```bash
git clone <repo-url> <your-local-path>
cd <your-local-path>
```

이 명령 직후 다음이 모두 동작한다:
- `.agent/HARNESS.md` — harness 진입점
- `.claude/settings.json` — 권한 + 훅 자동 활성
- `.agent/skills/*` — 스킬 (모두 `custom-` 접두; 목록·개수는 `skills/README.md`)
- `.agent/agents/*` — 에이전트 (모두 `custom-` 접두; 목록·개수는 `agents/README.md`)
- `.agent/rules/*` — 코드 규칙 (`paths` frontmatter 로 자동 로드)

## Step 3 — 환경 변수 export (1분)

`.mcp.json` 이 참조하는 값이다. **미설정이면 MCP 서버가 조용히 기동 실패**하므로
`custom-session-start.sh` 훅이 세션 시작 시 경고한다.

```bash
# ~/.zshrc 또는 ~/.bashrc 에 영구 추가
export CONTEXT7_API_KEY="<발급받은 키>"        # context7 — 라이브러리 문서 라이브 조회

# JDK — gradle.properties 의 undine.jvm 과 일치해야 한다
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

`.env` 파일은 두지 않는다 — 훅과 `permissions.deny` 가 `.env*` 읽기를 차단하므로
셸 프로파일에 export 하는 것이 정상 경로다.

## Step 4 — Claude Code 실행 + MCP 승인 (1분)

```bash
claude
```

첫 실행 시 프로젝트 `.mcp.json` 승인을 묻는다 — **승인해야 MCP 도구가 붙는다.**

### (A) 프로젝트 `.mcp.json` 등록분

| 서버 | 용도 | 필요 env |
|---|---|---|
| `context7` | 라이브러리 문서 라이브 조회 (JGit·Compose API 확인) | `CONTEXT7_API_KEY` |

### (B) claude.ai connector 등록분 — 개인별 OAuth

GitHub·Slack·Gmail·Calendar·Drive·Figma 는 `.mcp.json` 이 아니라
claude.ai/customize/connectors 에서 개인 OAuth 로 연결한다 (`mcp__claude_ai_<Service>__*` 네임스페이스).
미연결이어도 harness 핵심 기능은 전부 동작한다.

> 이들 connector 의 **write 계열 호출은 `custom-confirm-mcp-write.sh` 훅이 승인 프롬프트를 띄운다.**

## Step 5 — 동작 확인 (1분)

```bash
# 1) 훅 문법 일괄 점검
for f in .agent/hooks/*.sh; do bash -n "$f" || echo "문법 실패: $f"; done

# 2) 하네스 cross-file 정합 (경고 0 이어야 정상)
bash .agent/scripts/validate-harness.sh

# 3) 벤더 투영 드리프트 (exit 0 이어야 정상)
.agent/tools/sync-vendors.py --check

# 4) 커밋 접두사 훅 — 차단 동작 확인
echo '{"tool_input":{"command":"git commit -m \"접두사 없는 메시지\""}}' \
  | .agent/hooks/custom-check-commit-prefix.sh && echo "통과(이상)" || echo "차단(정상)"

# 5) 워크플로우 dry-run — 노드 실행 없이 wave 계획만 검증
python3 .agent/orchestration/runner/run-graph.py \
  .agent/orchestration/workflows/harness-audit.toml --dry-run
```

빌드까지 확인하려면:
```bash
./gradlew build        # JDK 미스매치면 custom-gradlew-jvm-guard.sh 가 차단한다
```

## 자주 묻는 질문

### Q. 글로벌 `~/.claude/settings.json` 와 충돌하나?
아니다. 글로벌↔프로젝트 hooks 는 **병합(merge)** 된다. 프로젝트 훅이 글로벌 훅을 대체하지 않는다.

### Q. `.mcp.json` 에 토큰을 직접 넣어도 되나?
넣지 않는다. `.mcp.json` 은 git tracked 이므로 `${VAR}` 참조만 두고 값은 셸 프로파일에 export 한다.

### Q. 훅이 너무 빡빡한데 임시로 끄려면?
`.claude/settings.local.json`(gitignored)에서 개인 오버라이드를 둔다. 팀 공유 `settings.json` 은 고치지 않는다.
`--no-verify` 로 우회하지 않는다 — 훅은 되돌리기 어려운 사고를 막는 장치다.

### Q. `.env.example` 을 `.env` 로 복사하면 자동 로드되나?
아니다. Claude Code 는 `.env` 를 로드하지 않고, 훅이 오히려 읽기를 차단한다. 셸 export 가 유일한 경로다.

### Q. `.mcp.json` 의 `npx` 버전을 왜 고정했나?
버전을 고정하지 않으면 최신 릴리즈가 자동 적용돼 어제 되던 도구가 오늘 깨진다. 재현 가능성을 위해 핀한다.

### Q. 티켓은 어디서 보나?
`tickets/README.md` 가 전체 목록·의존 DAG·wave 배치의 SSOT 다. 착수 전 자기 티켓의 **소유 패키지**를 확인한다.

## 다음 단계

1. [`HARNESS.md`](HARNESS.md) — 하네스 구조와 진단 rubric
2. [`docs/conventions.md`](docs/conventions.md) — 프로세스 게이트 4개 룰
3. [`rules/README.md`](rules/README.md) — 코드 작성 규칙
4. `tickets/README.md` — 무엇부터 작업할지
