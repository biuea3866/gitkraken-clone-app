# [UND-79] Undo 기록을 wave 1~7 변경 연산에 채운다

> wave 9 · 사이즈 L · 의존 UND-38 · UND-51 · 소유 `application/staging/` · `application/cherrypick/` · `application/sidebar/` · `application/conflict/` · `application/rebase/`

## 작업 내용 (설계 의도)

UND-38 이 Undo 스택을 만들었지만 **그 전에 만들어진 UseCase 들에는 소급 적용되지 않았다.**
`OperationRecorder` 를 생성자로 받는 것은 wave 8 산출물 넷(graphops · reflog · submodule ·
worktree)뿐이고, 나머지는 기록 경로가 없다.

기록되지 않는 연산 — `GitOperationKind` 에 값은 있는데 아무도 남기지 않는다:

`COMMIT` · `CHECKOUT` · `BRANCH_CREATE` · `MERGE` · `REBASE` · `CHERRY_PICK` ·
`STASH_PUSH` · `PUSH` · `HARD_RESET` · `STASH_DROP`

**이것이 Undo 패널의 신뢰를 정한다.** 사용자는 모든 동작이 되돌려진다고 믿는데 일부만 기록되면,
그 믿음이 깨지는 순간이 가장 나쁜 시점이다 — 되돌리려던 바로 그때다.

### 왜 UND-51 이 아니라 별도인가

배선(DI)만으로는 안 된다. 각 UseCase 의 **생성자와 실행 경로**를 바꿔야 하고, 그러면 닫힌 티켓
다섯 패키지의 파일을 건드린다. UND-51 은 배선 티켓이고 사이즈 M 이다 — 여기에 넣으면 배선이
아니라 재작성이 된다.

### 지켜야 할 계약

- **변경과 기록을 한 `NonCancellable` 단위로 묶는다** (결정 A-L2). 변경 성공 뒤 취소되면 기록만
  빠져 되돌릴 수 없는 변경이 이력에 남지 않는다.
- **기록 실패를 성공으로 접지 않는다** (`.agent/rules/exception-handling.md` 규칙 8). 변경 결과는
  성공으로 돌려주되 기록 실패 사유를 결과에 실어 화면이 reflog 경로를 안내하게 한다.
- **기준 상태는 변경과 같은 순간에 확정한다** (UND-73). `OperationRecorder.record` 가 baseline 을
  인자로 받으므로, 변경 연산이 임계 구역 안에서 캡처한 값을 넘긴다.
- 되돌릴 수 없는 연산(`PUSH` · `STASH_DROP` 등)은 `recordIrreversible` 로 **사유와 함께** 남긴다 —
  기록 자체를 건너뛰면 사용자가 그 동작이 있었다는 사실도 모른다.

**롤백**: 기록 추가는 기존 동작을 바꾸지 않는다 — revert 로 끝난다. 다만 되돌리기 대상이 늘어나므로
각 전략의 되돌리기가 실제로 복구하는지 확인한 뒤 머지한다.

## 테스트 케이스

- 커밋하면 `COMMIT` 항목이 이력에 남고 되돌리기가 그 직전 상태로 복구한다
- 체크아웃·브랜치 생성·병합·리베이스·cherry-pick 각각이 대응 항목을 남긴다
- stash push·drop 이 각각 기록되고, drop 은 되돌릴 수 없다는 사유와 함께 남는다
- push 는 되돌릴 수 없다는 사유와 함께 기록된다
- 변경이 성공한 직후 호출자가 취소돼도 기록이 정확히 한 건 남는다
- 기록만 실패하면 변경은 성공으로 돌아오고 그 사실이 결과에 실린다
- `GitOperationKind` 의 모든 값이 최소 한 곳에서 기록된다 (연산 목록 1:1 대조)
