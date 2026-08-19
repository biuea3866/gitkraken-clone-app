# Job Lifecycle — 잡 워크스페이스 라이프사이클

`<repo>/.claude-local/jobs/<job-name>/` 워크스페이스의 시작·종료·archiving·정리 정책을 정의한다.

> 정본 보충: [`collaboration-protocol.md`](collaboration-protocol.md) Job Lifecycle 섹션 + [`analysis-workflow.md`](analysis-workflow.md) 필수 이벤트.

---

## 1. 라이프사이클 정의

```
START → BRIEF → APPROVED → DISPATCHED → DONE(×N) → ACCEPTED → synthesizer DONE → retrospective DONE → CLOSE
```
> 위는 핵심 흐름 요약이다. collaboration.log 필수 이벤트 9종 전체는 §3 및 [`analysis-workflow.md`](analysis-workflow.md) §collaboration.log 필수 기록 이벤트 참조.

collaboration.log 필수 이벤트 9종은 [`analysis-workflow.md`](analysis-workflow.md) §collaboration.log 필수 기록 이벤트 참조.

### 경량 직접 처리 잡 (orchestrator-direct)

오케스트레이터가 서브에이전트 없이 직접 처리하는 소규모 잡(예: 검증 완료된 사실 기반 문서 정정 = doc-fix)은 위 9-이벤트 표준 흐름 대신 다음 경량 흐름을 따른다:

```
START → [VERIFIED] → DONE → retrospective DONE → CLOSE   (최소 5이벤트)
```

- BRIEF / APPROVED / DISPATCHED / ACCEPTED / synthesizer DONE 은 생략한다 (investigator/synthesizer 미사용).
- **VERIFIED (정정 잡 필수)**: 선행 분석(synthesis)의 결론을 후속 잡에서 정정·반영할 때는 **정정 전에 소스로 직접 재검증**하고 그 결과를 VERIFIED 이벤트로 남긴다. synthesis 의 INCORRECT 주장은 소스 확인 전에 고치지 않는다 (2026-06-16 doc-fix: synthesis 의 "payment WNO=OpenFeign" 주장이 소스 재검증에서 WebClient 로 정정됨 — 맹신 시 오류 전파).
- **context/**: 서브에이전트 산출물이 없으면 비어 있을 수 있다 — 사전 생성하지 않는다 ([`collaboration-protocol.md`](collaboration-protocol.md) §Workspace 생성: 첫 산출물 Write 시 자동 생성. 직접 처리 잡에서는 retrospective 보고서가 첫 context/ 산출물이 되는 경우가 많다).
- retrospective DONE → CLOSE 순서는 표준 잡과 동일하게 지킨다 (§3 CLOSE 게이트 (3)).

---

## 2. Archiving 정책

잡 종료 시 다음 산출물을 영속 위치로 이전한다.

| 산출물 | 정본 위치 | 명명 |
|---|---|---|
| synthesis-report (종합 분석 보고서) | `<repo>/analysis/{YYYY-MM-DD}-<job-name>.md` | 잡 시작일 또는 보고서 확정일 |
| retrospective (프로세스 회고) | `<repo>/retrospectives/{YYYY-MM-DD}-<job-name>.md` | 회고 작성일 |
| 외부 API spec / 1회성 reference | `<repo>/analysis/{YYYY-MM-DD}-<topic>.md` | spec 검증일 |

> **push 정책**: `<repo>/analysis/` 와 `<repo>/retrospectives/` 는 `.gitignore` 로 원격 push 에서 제외된다 (로컬 전용). 분석 보고서는 로컬 경로·자격증명 취급 등 개인 환경 사실을 담을 수 있어 원격 이력에 박제하지 않는다. **워크플로우 기계장치**(`.agent/**` SSOT · `.claude/`·`.codex/` 생성 투영 · settings)는 tracked 유지(clone 단독 동작). 특정 보고서를 의도적으로 공유할 때만 `git add -f` 로 개별 예외.
> 단 gitignore 여도 **로컬 Write 권한은 필요** — `permissions.allow` 의 `Write(/analysis/**)`·`Write(/retrospectives/**)` 가 이를 커버한다 (파일 생성 자체는 발생, commit/push 단계만 없음).

### Archiving 책임 주체

- `custom-analysis-synthesizer` 가 Step 5 완료 후 즉시 `analysis/` 작성.
- `custom-retrospective-analyst` 가 Step 7 에서 `retrospectives/` 작성.
- 오케스트레이터는 CLOSE 이벤트 기록 전에 아카이브 존재를 점검한다 (§3 CLOSE 게이트).

---

## 3. collaboration.log 검증 · CLOSE 게이트

CLOSE 이벤트 기록 전 오케스트레이터는 다음을 확인한다:

1. **9 이벤트 누락 여부**(START/BRIEF/APPROVED/DISPATCHED/DONE×N/ACCEPTED/synthesizer DONE/retrospective DONE/CLOSE).
2. **아카이브 존재 게이트**: `ls analysis/{YYYY-MM-DD}-<job-name>.md` 로 synthesizer 아카이브가 실제로 생성됐는지 확인한다 (§2). 없으면 synthesizer 에 아카이브 Write 를 재요청한 뒤 CLOSE 한다 — synthesizer 체크리스트가 있어도 9/9 미실행(2026-06-16 doc-verify 배치)된 구조적 누락의 최종 안전망.
3. **retrospective DONE 확인**: retrospective DONE 이벤트가 기록됐는가? 없으면 `custom-retrospective-analyst` 를 위임하고 **DONE 수신 후 CLOSE** 한다. retrospective 는 CLOSE 보다 먼저 와야 한다 (§1 라이프사이클 순서). CLOSE 를 먼저 기록하면 retrospective 가 누락·후행되는 역전이 반복된다 (2026-06-16 doc-fix + doc-verify 배치 9잡 = 10회 관측).

> **배치 모드 예외**: [`analysis-workflow.md`](analysis-workflow.md) §배치 모드로 처리한 잡은 (3) 개별 retrospective DONE 을 **배치 회고(`_<prefix>-retrospective` 잡)로 대체**하며, 그로 인한 9-이벤트 불완전을 정본 위반으로 보지 않는다. 단 (2) 아카이브 존재 게이트는 배치에서도 잡별로 필수다.

누락 발견 시:
- 회고 시 누락 사실을 retrospective 의 "프로세스 결함" 항목에 기재
- 소급 보정 X (collaboration.log 는 append-only, 사실 기록)
- 후속 잡 brief 작성 시 동일 누락 패턴 반복 방지

---

## 4. jobs/ 디렉토리 보관 기준

archiving + 회고 작성이 완료된 잡 디렉토리는 **삭제하지 않고 보관**한다 (`.claude-local/` 은 gitignore 되어 로컬에만 남으므로 원격 노이즈가 없다).

- collaboration.log 는 회고 추적성과 프로세스 개선의 1차 자료 (보존 가치)
- finding-* / 중간 산출물 / decisions-pending 등은 후속 분석 brief 작성 시 인용 자료
- 명시적 삭제는 사용자 요청 시에만 수행한다.

### 예외 (유지)

- 후속 잡이 동일 job-name 을 재사용하는 진행 중 잡 (동일 job 재사용 원칙)
- 답변 대기 중(decisions-pending) 잡
