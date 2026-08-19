# Collaboration Protocol

에이전트 간 협업을 위한 공유 워크스페이스 구조, 로그 형식, 산출물 규약을 정의한다.

---

## Job Workspace

모든 작업은 job 단위로 관리되며, 각 job은 고유한 workspace를 갖는다.

```
.claude-local/jobs/<job-name>/
├── collaboration.log              # 행동 로그 (append-only)
└── context/                       # 에이전트 산출물
```

> **경로 정책**: workspace 는 `<repo>/.claude-local/jobs/` 에 둔다. `.claude-local/` 은 `.gitignore` 로 자동 격리되는 개인 작업 공간이라 collaboration.log·중간 산출물이 원격에 push 되지 않는다. 남길 가치가 있는 것은 중간 산출물이 아니라 **최종 아카이브**(`<repo>/analysis/`, `<repo>/retrospectives/`)다. `.claude/settings.json` 의 `permissions.allow` 에 `Edit(/.claude-local/jobs/**)` 가 등록되어 있어 승인 프롬프트 없이 동작한다.

### Job 이름 규칙

- kebab-case 사용 (예: `commit-graph-perf-analysis`)
- 작업 내용을 간결하게 표현
- 날짜 prefix 권장 (예: `2026-06-11-<job>`) — 디렉토리 정렬·아카이브 명명과 일치

### Job Lifecycle

job 은 다음 라이프사이클을 따른다:

| 단계 | 상태 | 트리거 |
|------|------|--------|
| Open | 활성 — 산출물·로그 추가 가능 | workspace 생성 |
| Bridged | Analysis 완료, Development 진입 | analysis-synthesizer 가 `## Implementation Bridge` 와 `## Development Handoff` 작성 |
| Closed | 종료 — 추가 산출물 작성 금지 | retrospective-analyst 의 DONE 로그 기록 |

**Analysis → Development 전환 시 동일 job 을 재사용한다.** 즉 한 job workspace 안에 분석 산출물(`scope-*.md`, `synthesis-report.md`) 과 구현 산출물이 함께 누적된다.

**job close 이후의 후속 작업은 새 job 을 생성한다.** 이미 종료된 job 에 추가 산출물을 쓰지 않는다 (이력 추적성·git 변화 추적의 명료성을 위함).

REQUEST_CHANGES 루프는 job close 이전에 발생하므로 동일 job 안에서 처리된다.

### Workspace 생성

오케스트레이터가 워크플로우 시작 시 **Write 도구로** workspace 를 생성한다. `mkdir`/`touch` Bash 명령을 사용하지 않는다 — Write 도구는 상위 디렉토리를 자동 생성하며, `/.claude-local/jobs/**` 는 `permissions.allow` 에 등록되어 있어 승인 프롬프트 없이 실행된다 (Bash mkdir 는 매번 승인 유발).

1. 타임스탬프 취득: 단독 Bash `date -u +%Y-%m-%dT%H:%M:%SZ` 1회 (`permissions.allow` 의 `Bash(date:*)` 로 자동 허용)
2. **Write 도구**로 `.claude-local/jobs/<job-name>/collaboration.log` 를 START 엔트리 1줄과 함께 생성 → 디렉토리 자동 생성
3. `context/` 디렉토리는 별도로 만들지 않는다 — 첫 산출물(brief 등) Write 시 자동 생성

---

## Collaboration Log

### 형식

`collaboration.log` 의 모든 줄은 **오케스트레이터(메인 세션)가 단독으로 기록**한다 (single-writer 모델). 서브에이전트는 로그를 직접 쓰지 않고, 작업 완료 시 DONE 요약 1줄을 **응답 텍스트로 반환**하며 오케스트레이터가 이를 받아 기록한다. 한 줄 형식:

```
[timestamp] agent-name | STATUS | summary
```

> **single-writer 근거**: 서브에이전트(investigator/synthesizer/retrospective)에는 `Edit` 도구가 grant 되어 있지 않아 append-only 로그를 안전히 기록할 수 없고, 병렬 investigator 가 같은 로그를 동시 Write(전체 덮어쓰기) 하면 lost update 가 발생한다. 기록 주체를 오케스트레이터로 일원화해 중복 DONE·동시 기록 경합·기록 누락을 원천 차단한다. STATUS 표의 "행위 주체" 는 그 줄이 누구의 행위를 나타내는지(논리적 주체)를 뜻하며, **물리적 기록자는 항상 오케스트레이터**다.

### 기록 방법 (도구)

**오케스트레이터가** Edit 도구로 로그를 append 한다. Bash `echo "..." >> collaboration.log` 를 사용하지 않는다 — `$(date ...)` 명령 치환이 포함된 echo 는 매번 승인 프롬프트를 유발하지만, `/.claude-local/jobs/**` 경로의 Edit/Write 는 allowlist 로 무승인 실행된다. **서브에이전트는 collaboration.log 를 직접 쓰지 않는다** — DONE 요약 1줄을 응답으로 반환하고, 오케스트레이터가 수신 직후 기록한다.

1. 타임스탬프 취득: 단독 Bash `date -u +%Y-%m-%dT%H:%M:%SZ` 1회 (`permissions.allow` 의 `Bash(date:*)` 로 자동 허용)
2. collaboration.log 의 끝부분을 Read (전체 또는 tail 일부)
3. **Edit 도구**: `old_string` = 마지막 줄, `new_string` = 마지막 줄 + 개행 + 새 엔트리
4. **병렬 라운드 기록**: 병렬 investigator 가 동시에 끝나면 오케스트레이터가 각 DONE 요약을 수신해 한 번의 Edit 로 묶어 기록한다 (각 엔트리 타임스탬프는 실제 완료 시각 — 사후 임의 재구성 금지). 단일 기록자이므로 "file modified since read" 경합은 발생하지 않는다.

### STATUS 값

| Status | 의미 | 행위 주체 (기록자는 항상 오케스트레이터) |
|--------|------|---------|
| `COMPLETED` / `DONE` | 정상 완료 | 에이전트 (DONE 요약 반환 → 오케스트레이터 기록) |
| `FAILED` | 실패 (원인을 summary에 기록) | 에이전트 (응답으로 보고 → 오케스트레이터 기록) |
| `BLOCKED` | 선행 조건 미충족으로 대기 | 에이전트 (응답으로 보고 → 오케스트레이터 기록) |
| `DELEGATED` | 다른 에이전트에게 위임 | 오케스트레이터 |
| `ACCEPTED` | 에이전트 DONE 을 오케스트레이터가 수신·검증 완료 | 오케스트레이터 (DONE 중복 회피용) |
| `RESCUE-{ID}` | 에이전트 인라인 출력 누락 보존 Write 등 복구 작업 | 오케스트레이터 |
| `SUMMARY` | 단계 종합 요약 (예: "α/β/γ/δ 4 finding 모두 ACCEPTED") | 오케스트레이터 |

### 규칙

- **append-only**: 기존 줄을 수정하거나 삭제하지 않는다.
- **타임스탬프**: ISO 8601 UTC 형식 (예: `2026-04-16T14:30:00Z` — `date -u +%Y-%m-%dT%H:%M:%SZ` 출력 그대로)
- **1 에이전트 = 1줄 이상**: 오케스트레이터는 각 에이전트 완료마다 최소 DONE 1줄을 기록한다. REQUEST_CHANGES 루프 시 매 실행마다 추가.
- **DONE 중복 방지**: 단일 기록자 모델이므로 "에이전트 자기기록 + 오케스트레이터 기록" 이중 기록이 구조적으로 발생하지 않는다. 오케스트레이터는 동일 actor + 동일 phase 의 `DONE` 을 1 회만 기록하고, 수신·검증 표기는 `DONE` 재기록이 아니라 `ACCEPTED` (또는 단계 종합 시 `SUMMARY`) 로 한다. 인라인 출력 누락 보존 등 복구 작업은 `RESCUE-{ID}` 사용.
- **ACCEPTED 기록 타이밍**: 멀티 Stage/라운드 잡에서 `ACCEPTED` 는 해당 라운드의 모든 병렬 에이전트 `DONE` 수신 + 산출 파일 확인 직후에 기록한다. 에이전트 `DONE` 을 오케스트레이터가 사후 일괄 기록(타임스탬프 재구성)하지 않는다 — 라운드 간 타임스탬프 역전으로 회고 타임라인 추적이 불가능해진다.

### 예시

```
[2026-04-16T14:00:00Z] orchestrator | START | job=commit-graph-perf-analysis workflow=analysis
[2026-04-16T14:02:00Z] orchestrator | BRIEF | investigation-brief-1..3 작성 완료
[2026-04-16T14:03:00Z] orchestrator | APPROVED | 사용자 승인 — Scope A/B/C 확정
[2026-04-16T14:03:30Z] orchestrator | DISPATCHED | investigator-A/B/C 병렬 dispatch
[2026-04-16T14:10:00Z] custom-research-investigator-A | DONE | finding 산출 (7 사실)
[2026-04-16T14:10:00Z] custom-research-investigator-B | DONE | finding 산출 (5 사실)
[2026-04-16T14:11:00Z] custom-research-investigator-C | DONE | finding 산출 (4 사실)
[2026-04-16T14:11:30Z] orchestrator | ACCEPTED | 3 finding 검증 완료 (Step 4.5 게이트 통과)
[2026-04-16T14:18:00Z] custom-analysis-synthesizer | DONE | synthesis-report.md 산출 (6 섹션, OQ 2)
[2026-04-16T14:22:00Z] custom-retrospective-analyst | DONE | 회고 작성 완료, 이슈 0건
[2026-04-16T14:23:00Z] orchestrator | CLOSE | 잡 종료
```

---

## Context 파일

### 산출물 목록

| 에이전트 | 파일명 | 내용 |
|---------|--------|------|
| 오케스트레이터 | `investigation-brief-{N}.md` | 조사 지시서 (범위별) |
| research-investigator | `scope-{ID}-{topic}.md` | 범위별 조사 결과 (예: `scope-A-dormant-detection.md`) |
| analysis-synthesizer | `synthesis-report.md` | 종합 분석 보고서 |
| requirements-analyst | `requirements-analyst-plan.md` | 전체 계획 (Development 전환 시) |
| retrospective-analyst | `retrospective-analyst-report.md` | 회고 분석 및 개선 제안 |

### 읽기/쓰기 규칙

- 각 에이전트는 작업 시작 시 `context/`를 스캔하여 **선행 에이전트의 산출물을 읽는다**.
- 자신의 산출물만 **쓰기** 가능. 다른 에이전트의 산출물을 수정하지 않는다.
- REQUEST_CHANGES 루프 시 자신의 산출물을 **덮어쓴다** (최신 상태가 정본).

### Difficulties 섹션

모든 에이전트 산출물에 선택적으로 `## Difficulties` 섹션을 포함할 수 있다:

```markdown
## Difficulties
- [WORKAROUND] 설명 | 원인 | 임시 해결
- [BLOCKER] 설명 | 해결 여부: YES/NO
- [FRICTION] 설명 | 영향: 시간 지연/품질 저하
```

이 섹션은 `custom-retrospective-analyst`가 수집하여 프로세스 개선에 활용한다.

---

## 에이전트 간 데이터 흐름 (Analysis Workflow)

```
investigation-brief-{1..N}.md
  ↓ (custom-research-investigator 가 각각 1개씩 읽음)
scope-{ID}-{topic}.md  (ID 는 A/B/C..., topic 은 kebab-case)
  ↓ (custom-analysis-synthesizer 가 전체를 읽음)
synthesis-report.md  → 아카이브: <repo>/analysis/{YYYY-MM-DD}-<job>.md
  ↓ (custom-retrospective-analyst 가 전체를 읽음)
retrospective-analyst-report.md → 아카이브: <repo>/retrospectives/{YYYY-MM-DD}-<job>.md
```
