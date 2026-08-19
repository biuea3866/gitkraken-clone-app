# .agent/skills — 스킬 (7개)

슬래시 명령 또는 자동 발화로 호출되는 워크플로우. `.claude/skills/` 는 여기서 생성되는 투영본이다.

| 스킬 | 언제 | 산출 |
|---|---|---|
| [`custom-develop-orchestrator`](custom-develop-orchestrator/SKILL.md) | "UND-NN 작업해줘/구현해줘", `/custom-develop-orchestrator UND-NN` | 티켓 1건 스펙→구현→검증→정리 1-루프 (스펙 승인·최종 검토 2 게이트) |
| [`custom-orchestrate`](custom-orchestrate/SKILL.md) | `/custom-orchestrate <workflow>`, "harness-audit 돌려줘" | DAG 워크플로우 dry-run 검증 → 승인 → 실행 → 산출물 요약 |
| [`custom-self-code-review`](custom-self-code-review/SKILL.md) | `git push` 직전, PR 본문 작성 직전 | 5축 자가 리뷰 (의도·테스트·사이드이펙트·빌드의존성·롤백) — **축 정의 SSOT** |
| [`custom-affected-test-runner`](custom-affected-test-runner/SKILL.md) | push 직전 "이 변경 테스트 돌았나" | 변경 범위 대응 테스트 실행 |
| [`custom-pr-create`](custom-pr-create/SKILL.md) | "PR 만들어줘" | 템플릿 기반 PR 본문 + `gh pr create` (기본 Draft) |
| [`custom-pr-review`](custom-pr-review/SKILL.md) | "이 PR 리뷰해줘", `/custom-pr-review <N>` | 로컬 다축 정적 리뷰 리포트 (기본 로컬, 게시는 opt-in) |
| [`custom-release-tagger`](custom-release-tagger/SKILL.md) | "릴리즈 태그 달아줘" | semver 태그 + 릴리즈 노트 초안 (승인 후 push) |

## 개발 사이클에서의 순서

```
1. /custom-develop-orchestrator UND-NN   → 스펙 → 구현 → 5축 검증
2. /custom-affected-test-runner          → 변경 범위 테스트
3. /custom-self-code-review              → push 전 5축 자가 점검
4. /custom-pr-create                     → Draft PR
5. /custom-pr-review <N>                 → 머지 전 다축 리뷰
6. /custom-release-tagger                → 릴리즈 태그
```

## 추가 시

1. `skills/<name>/SKILL.md` 를 만든다 (frontmatter `name`·`description` 필수).
2. 위 표에 행을 추가한다 (카운트 SSOT — 헤딩의 개수도 함께 갱신).
3. `.agent/tools/sync-vendors.py` 로 투영을 재생성한다.
4. `bash .agent/scripts/validate-harness.sh` 로 정합을 확인한다.
