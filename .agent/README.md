# .agent — 하네스 SSOT (벤더 중립)

Undine 의 AI 협업 자산 **단일 진실 공급원**. 어느 LLM 에이전트로 작업하든 여기를 읽고
여기를 고친다. 벤더 디렉토리(`.claude/` · `.codex/`)는 여기서 **생성되는 투영본**이다.

```
.agent/
├── README.md                    # 본 문서
├── HARNESS.md                   # 하네스 진입점 + 진단 rubric
├── onboarding.md                # 팀원 셋업 가이드
├── agents/                      # specialized subagents (개수 SSOT: agents/README.md)
├── skills/                      # slash command + auto-invoke (개수 SSOT: skills/README.md)
├── rules/                       # 코드 작성 규칙 (개수 SSOT: rules/README.md)
├── hooks/                       # 가드/리마인더 셸 스크립트 (개수 SSOT: hooks/README.md)
├── docs/                        # 컨벤션·SSOT 맵·워크플로우 정본
├── guidelines/                  # LLM 협업 원칙
├── templates/                   # 산출물 템플릿
├── scripts/                     # 하네스 유지보수 스크립트 (validate-harness 등)
├── orchestration/               # 멀티 에이전트 워크플로우 → orchestration/README.md
└── tools/
    └── sync-vendors.py          # .agent/ → .claude/·.codex/ 투영 생성기
```

## 벤더 투영 — 편집 대상과 생성물

| 경로 | 성격 | 편집 |
|---|---|---|
| `.agent/**` | SSOT | ✅ 여기를 고친다 |
| `.claude/agents/` · `.claude/skills/` · `.claude/rules/` | 생성물 (그대로 복사) | ❌ 훅이 차단 |
| `.codex/agents/*.toml` | 생성물 (md → TOML 변환) | ❌ 훅이 차단 |
| `.claude/settings.json` | 벤더 전용 손유지 (권한·훅 등록) | ✅ 직접 편집 |
| `.codex/config.toml` · `.codex/hooks/` · `.codex/lib/` | 벤더 전용 손유지 | ✅ 직접 편집 |

투영이 필요한 이유: Claude Code 는 `.claude/{agents,skills,rules}` 만 자동 탐색하고, Codex 는
`.codex/agents/*.toml` 만 읽는다. 본문을 손으로 복제하면 반드시 어긋나므로 **생성**한다.

```bash
.agent/tools/sync-vendors.py              # 재생성 (.agent/ 를 고친 뒤 반드시 실행)
.agent/tools/sync-vendors.py --check      # 드리프트 판정 (exit 1 = 재생성 필요)
bash .agent/scripts/validate-harness.sh   # 카운트·목록·훅 배선 + 투영 동기 상태 전수 검증
```

훅 스크립트는 투영하지 않는다 — `.claude/settings.json` 과 `.codex/config.toml` 이
`.agent/hooks/*.sh` 를 **경로로 가리킨다**. 사본이 없으므로 드리프트도 없다.

`.claude/agents` 같은 생성 경로를 편집하려 하면 `custom-block-generated-edit.sh` 훅이 차단하고
대응 SSOT 경로를 알려준다.

## 멀티 에이전트 오케스트레이션

DAG 워크플로우·러너·벤더 어댑터는 `orchestration/` 에 있다 — 상세는
[orchestration/README.md](orchestration/README.md).

```bash
.agent/orchestration/runner/run-graph.py .agent/orchestration/workflows/harness-audit.toml --dry-run
```

새 LLM 에이전트를 붙이려면 `orchestration/runner/adapters/` 에 TOML 1개를 추가한다 (러너 코드 무수정).

## 자산 추가/갱신 순서

1. `.agent/` 아래 해당 디렉토리에 파일을 추가·수정한다.
2. 그 디렉토리 `README.md` 의 개수 헤딩과 목록 표에 행을 추가한다 (카운트 SSOT).
3. 훅을 추가했다면 `.claude/settings.json` matcher 와 `.codex/config.toml` 에 등록한다.
4. `.agent/tools/sync-vendors.py` 로 투영을 재생성한다.
5. `bash .agent/scripts/validate-harness.sh` 로 정합을 확인한다 (경고 0 이어야 커밋).
