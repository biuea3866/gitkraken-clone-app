# .agent/hooks — 표준 hooks

각 훅은 git tracked 셸 스크립트. `.claude/settings.json`이 matcher로 묶어 등록한다.
모든 훅은 `bash -n` 통과를 전제로 한다. 추가 시 `chmod +x` 필수.

## 등록된 훅 (12개)

| 파일 | 시점 | matcher | 동작 | 차단? |
|---|---|---|---|---|
| `custom-session-start.sh` | SessionStart | `""` | 브랜치·JDK 정합·MCP env 점검 1회 안내 + LLM 협업 가이드라인 주입 | — |
| `custom-gradlew-jvm-guard.sh` | PreToolUse | `Bash` | `./gradlew` 직전 `gradle.properties` 의 `undine.jvm` vs `$JAVA_HOME` 비교 | ✅ 미스매치 시 |
| `custom-check-commit-prefix.sh` | PreToolUse | `Bash` | `git commit -m` 메시지에 `[UND-NN]` 강제 + **정밀 lint**(콜론 앞 공백 `feat :`·다중 prefix `[A][B]` 차단, `gh pr create --title` 에도 적용). 티켓 없는 작업은 `no-ticket` 명시로 면제 | ✅ |
| `custom-block-git-push.sh` | PreToolUse | `Bash` | `git push` 직전 사용자 승인 프롬프트(ask). --force / --force-with-lease 사용 시 reason 에 경고 노출 | 🔔 (ask) |
| `custom-block-mainline-merge.sh` | PreToolUse | `Bash` | `gh pr merge` / 보호 브랜치(`main\|stage-*`) 위에서의 `git merge\|rebase` 차단. 작업 브랜치에서 default branch 싱크는 허용 | ✅ |
| `custom-block-env-read.sh` | PreToolUse | `Bash` | `cat`/`grep`/`vi`/`code` 등 + `.env*`·`*.pem`·`id_rsa` / `echo $TOKEN` / `printenv` / `env` 차단 | ✅ |
| `custom-secrets-read-guard.sh` | PreToolUse | `Read` | Claude `Read` 도구로 `.env`·credentials·`*.pem` 등 읽기 차단 | ✅ |
| `custom-secrets-edit-guard.sh` | PreToolUse | `Edit\|Write\|MultiEdit` | `.env`·credentials·`*.pem` 등 편집 차단, application 설정 비밀 패턴 경고 | ✅ |
| `custom-block-generated-edit.sh` | PreToolUse | `Edit\|Write\|MultiEdit` | `.claude/{agents,skills,rules}`·`.codex/agents` 등 생성 투영 편집 차단 (SSOT 는 `.agent/`) | ✅ |
| `custom-confirm-mcp-write.sh` | PreToolUse | `mcp__claude_ai_(Slack\|GitHub\|Gmail\|Google_Calendar\|Google_Drive\|Figma)__...` | Slack 발송 / **GitHub Issue·PR·Comment·File·Branch·Release 변경** / Gmail·Calendar·Drive 쓰기 + Figma 파일 변경 직전 사용자 승인 프롬프트(ask). 각 connector 의 **read 동사(get_/list_/search_/find_)·OAuth 는 통과**. Approve → 호출 진행, Deny → 호출 취소(자동 재시도도 다시 프롬프트) | 🔔 (ask) |
| `custom-detekt-touch-reminder.sh` | PostToolUse | `Edit\|Write\|MultiEdit` | `.kt`/`.kts` 변경 후 `./gradlew detekt` 실행 안내 (JSON `additionalContext` 로 Claude 컨텍스트 주입) | — |
| `custom-detekt-async-run.sh` | PostToolUse | `Edit\|Write\|MultiEdit` | `.kt`/`.kts` 변경 시 `./gradlew detekt` **자동 실행** (`"async": true` 비차단 · 5분 디바운스 · 위반 검출 시에만 `additionalContext` 보고, detekt 미적용/환경 실패는 조용히 skip) | — |

> Analysis Workflow 의 read-only Bash(`date -u` 타임스탬프 · `ls context/` Step 4.5 게이트)는 훅이 아니라 `.claude/settings.json` 의 `permissions.allow`(`Bash(date:*)` / `Bash(ls:*)`)로 무승인 통과한다. 그 외 파일 검색은 내장 Grep/Glob/Read 도구로 수행한다(승인 프롬프트 없음).

### 비밀 가드 정책 (3-layer)

1. **`permissions.deny` (settings.json)** — Claude Code 도구 호출 레이어에서 일차 거부. 가장 강함. **`.env.*` 일괄 패턴은 두지 않는다** — `.env.example`/`.sample`/`.template` 같은 git tracked 온보딩 자산을 살리려는 목적이며, deny 가 hook allowlist 보다 먼저 평가되기 때문이다. 대신 알려진 환경 suffix(`.env.local`, `.env.{development,staging,production,test}[.local]`, `.env.private`, `.env.secret`)를 enumerate.
2. **`custom-secrets-read-guard.sh` (Read matcher)** — deny 에 enumerate 되지 않은 `.env.<unknown>` 변종을 이차 차단 + `.env.example` / `.env.sample` / `.env.template` 화이트리스트.
3. **`custom-block-env-read.sh` (Bash matcher)** — `cat`/`grep`/`echo $TOKEN` 등 셸 명령 차단.

세 레이어가 동시에 작동해 우회 가능성을 최소화한다. 정당한 사유로 1회 우회가 필요한 경우 Bash 명령에 `# !no-secret-guard` 주석을 명시 (감사 대상으로 남음). `.claude-local/` 디렉토리는 팀원 개인 용도라 차단 대상에서 **제외** — 단, `.claude-local/.env` 처럼 안에 든 비밀 파일은 별도 패턴이 잡음.

## 추가 시 절차

1. 스크립트 작성 — `set -euo pipefail` + stdin JSON parsing 컨벤션 유지.
2. `chmod +x .agent/hooks/<name>.sh`
3. `.claude/settings.json` 의 `hooks.<event>` matcher 에 등록.
4. 본 README 표에 한 줄 추가.
5. 검증: `for f in .agent/hooks/*.sh; do bash -n "$f"; done`

## 차단 vs 승인 요청 vs 안내 정책

- **차단(exit 2)** — 운영 사고로 직결되는 위반(인프라 write, prefix 누락, JDK 미스매치, 비밀 파일 편집). stderr 메시지가 Claude 컨텍스트로 전달돼 자기 수정 유도.
- **승인 요청(exit 0 + `permissionDecision: "ask"` JSON)** — PreToolUse 전용. Claude Code 가 사용자에게 호출 승인 프롬프트를 띄운다. Approve → Claude 가 직접 호출 진행, Deny → 호출 취소(자동 재시도도 동일 프롬프트 통과 필요). `custom-confirm-mcp-write.sh` / `custom-block-git-push.sh` 가 사용 (외부 변경 승인 정책의 SSOT 는 [`.agent/onboarding.md`](../onboarding.md) — connector 별 read 통과/write ask 기준):
    ```json
    {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"..."}}
    ```
- **안내(exit 0)** — 이벤트별로 컨텍스트 도달 방식이 다르다 (공식 문서 기준):
  - **SessionStart / UserPromptSubmit / UserPromptExpansion**: plain stdout 이 Claude 컨텍스트에 자동 주입 → `custom-session-start.sh` 는 plain stdout 으로 작성.
  - **PreToolUse / PostToolUse**: plain stdout 은 debug log 에만 기록되고 Claude 가 못 본다. Claude 에게 안내가 도달해야 하면 JSON 형식으로 출력해야 한다:
    ```json
    {"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"..."}}
    ```
    `custom-detekt-touch-reminder.sh` 가 이 형식을 사용한다.

stderr 는 사용자/Claude 양쪽에 보인다.
stdout/stderr 어디에도 비밀 값 자체를 출력하지 않는다.

## stdin 컨벤션

Claude Code 가 hook 에 JSON 을 stdin 으로 전달한다. 본 훅들은 다음 키를 사용:

| 도구 종류 | 사용 키 |
|---|---|
| Bash | `tool_input.command` |
| Edit/Write/MultiEdit | `tool_input.file_path` 또는 `tool_input.path`, `tool_input.new_string`/`tool_input.content` |

## 검증

```bash
# syntax (.sh)
for f in .agent/hooks/*.sh; do bash -n "$f" && echo "OK $f" || echo "FAIL $f"; done

# 정규식 매치 sanity check (예시)
echo '{"tool_input":{"command":"git commit -m \"feat: x\""}}' | .agent/hooks/custom-check-commit-prefix.sh && echo "통과(이상)" || echo "차단(정상)"
echo '{"tool_input":{"command":"git commit -m \"[UND-01] - fix: x\""}}' | .agent/hooks/custom-check-commit-prefix.sh && echo "통과(정상)" || echo "차단(이상)"
```
