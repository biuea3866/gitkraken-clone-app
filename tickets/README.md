# tickets — 작업 단위 SSOT

Undine 구현을 **52개 티켓 / 10개 wave** 로 분해한 결과다.
**착수 전 자기 티켓의 소유 패키지를 확인한다** — 같은 wave 의 다른 티켓과 파일이 겹치면
머지 충돌이 난다 (`.agent/docs/conventions.md` Rule 3).

## 범위 구분

| 범위 | wave | 티켓 | 내용 |
|---|---|---|---|
| **1차 — 일상 사용** | 1~6 | 28건 | 저장소·그래프·diff·스테이징·브랜치·원격·병합·충돌·리베이스·검색 |
| **2차 — 완성도** | 2, 7~10 | 25건 | cherry-pick·blame·reflog·patch·submodule·LFS·worktree·bisect·서명·undo·설정 화면·드래그&드롭·탭·접근성·자동 업데이트 |

**1차만 끝나도 매일 쓸 수 있다.** 2차는 "GitKraken 과 비슷해지는" 구간이며,
필요 없다고 판단되는 티켓은 지워도 1차 결과물이 깨지지 않도록 의존을 설계했다.

> **여전히 재현하지 않는 것**: GitHub/GitLab PR·이슈 연동, Jira/Trello 연동, GitKraken Workspaces,
> Cloud Patches, Focus View, Insights 등 **클라우드 서비스 영역**. 앱을 따라 만든다고 생기지 않으며
> 개인 도구에는 대부분 불필요하다.

## 티켓 목록 — 1차

| ID | 제목 | 사이즈 | wave | 의존 | 소유 패키지 |
|---|---|---|---|---|---|
| [UND-01](UND-01-scaffolding-contracts.md) | 프로젝트 스캐폴딩 · 공통 계약 정의 | L | 1 | — | 루트 빌드 · `domain/` 전체 계약 |
| [UND-02](UND-02-repository-open-status.md) | 저장소 열기 · 워킹트리 상태 조회 | M | 2 | UND-01 | `infrastructure/git/repository/` |
| [UND-03](UND-03-commit-history.md) | 커밋 이력 조회 (페이징) | M | 2 | UND-01 | `infrastructure/git/history/` |
| [UND-04](UND-04-graph-lane-layout.md) | 커밋 그래프 레인 배치 알고리즘 | M | 2 | UND-01 | `domain/graph/` |
| [UND-05](UND-05-diff-computation.md) | Diff 계산 (파일 · hunk · word-level) | M | 2 | UND-01 | `infrastructure/git/diff/` |
| [UND-06](UND-06-staging-commit.md) | 스테이징 · 커밋 | M | 2 | UND-01 | `infrastructure/git/staging/` |
| [UND-07](UND-07-ref-management.md) | 브랜치 · 태그 관리 | M | 2 | UND-01 | `infrastructure/git/ref/` |
| [UND-08](UND-08-remote-sync-auth.md) | 원격 동기화 (clone/fetch/pull/push) · 인증 | L | 2 | UND-01 | `infrastructure/git/remote/` |
| [UND-09](UND-09-stash-reset-revert.md) | Stash · Reset · Revert | M | 2 | UND-01 | `infrastructure/git/worktreeops/` |
| [UND-10](UND-10-design-system.md) | 디자인 시스템 · 테마 | M | 2 | UND-01 | `presentation/design/` |
| [UND-11](UND-11-app-settings-persistence.md) | 앱 설정 · 최근 저장소 영속화 | S | 2 | UND-01 | `infrastructure/settings/` |
| [UND-12](UND-12-app-shell-layout.md) | 앱 셸 3분할 레이아웃 | M | 3 | UND-10 | `presentation/shell/` |
| [UND-13](UND-13-sidebar-ref-tree.md) | 사이드바 레퍼런스 트리 | M | 3 | UND-07 · UND-09 · UND-10 | `presentation/sidebar/` |
| [UND-14](UND-14-commit-graph-view.md) | 커밋 그래프 뷰 렌더링 | L | 3 | UND-03 · UND-04 · UND-10 | `presentation/graph/` |
| [UND-15](UND-15-commit-detail-panel.md) | 커밋 상세 패널 | M | 3 | UND-03 · UND-05 · UND-10 | `presentation/commitdetail/` |
| [UND-16](UND-16-diff-viewer.md) | Diff 뷰어 | L | 3 | UND-05 · UND-10 | `presentation/diff/` |
| [UND-17](UND-17-staging-commit-panel.md) | 스테이징 · 커밋 작성 패널 | M | 3 | UND-06 · UND-10 | `presentation/staging/` |
| [UND-18](UND-18-toolbar-remote-progress.md) | 툴바 · 원격 작업 진행 표시 | M | 3 | UND-08 · UND-10 | `presentation/toolbar/` |
| [UND-19](UND-19-repo-open-clone-screen.md) | 저장소 열기 · 클론 화면 | M | 3 | UND-02 · UND-08 · UND-10 · UND-11 | `presentation/welcome/` |
| [UND-20](UND-20-commit-search-filter.md) | 커밋 검색 · 필터 | M | 3 | UND-03 · UND-10 | `presentation/search/` |
| [UND-21](UND-21-merge-rebase-execution.md) | 병합 · 리베이스 실행 | L | 3 | UND-07 | `domain/merge/` · `application/merge/` |
| [UND-22](UND-22-command-palette-shortcuts.md) | 커맨드 팔레트 · 단축키 | M | 3 | UND-10 | `presentation/palette/` |
| [UND-23](UND-23-conflict-resolution-editor.md) | 충돌 해결 에디터 UI | L | 4 | UND-16 · UND-21 | `presentation/conflict/` |
| [UND-24](UND-24-interactive-rebase-ui.md) | 대화형 리베이스 UI | L | 4 | UND-10 · UND-21 | `presentation/rebase/` |
| [UND-25](UND-25-packaging-distribution.md) | 패키징 · 배포 | M | 4 | UND-01 | `build.gradle.kts` · `packaging/` |
| [UND-26](UND-26-app-wiring-di.md) | 앱 통합 와이어업 · DI | M | 5 | UND-12 · UND-13 · UND-14 · UND-15 · UND-16 · UND-17 · UND-18 · UND-19 · UND-20 · UND-21 · UND-22 · UND-23 · UND-24 | `presentation/App.kt` · `di/` |
| [UND-27](UND-27-e2e-scenario-tests.md) | E2E 시나리오 테스트 | M | 6 | UND-26 | `app/src/test/kotlin/.../scenario/` |
| [UND-49](UND-49-i18n-string-resources.md) | i18n 문자열 리소스 기반 | M | 2 | UND-01 | `presentation/i18n/` |

## 티켓 목록 — 2차

| ID | 제목 | 사이즈 | wave | 의존 | 소유 패키지 |
|---|---|---|---|---|---|
| [UND-28](UND-28-cherry-pick.md) | Cherry-pick 실행 | M | 7 | UND-21 | `domain/cherrypick/` · `infrastructure/git/cherrypick/` |
| [UND-29](UND-29-blame-file-history.md) | Blame · 파일 이력 조회 | M | 7 | UND-01 | `infrastructure/git/blame/` |
| [UND-30](UND-30-reflog-recovery.md) | Reflog 조회 · 복구 | M | 7 | UND-01 | `infrastructure/git/reflog/` |
| [UND-31](UND-31-patch-create-apply.md) | Patch 생성 · 적용 | M | 7 | UND-05 | `infrastructure/git/patch/` |
| [UND-32](UND-32-submodule-management.md) | Submodule 관리 | M | 7 | UND-02 | `infrastructure/git/submodule/` |
| [UND-33](UND-33-git-lfs.md) | Git LFS 연동 | M | 7 | UND-08 | `infrastructure/git/lfs/` |
| [UND-34](UND-34-worktree-management.md) | Worktree 관리 | M | 7 | UND-02 | `infrastructure/git/worktree/` |
| [UND-35](UND-35-bisect-session.md) | Bisect 세션 | M | 7 | UND-03 | `domain/bisect/` · `infrastructure/git/bisect/` |
| [UND-36](UND-36-commit-signing.md) | 커밋 서명 (GPG / SSH) | M | 7 | UND-06 | `infrastructure/git/signing/` |
| [UND-37](UND-37-git-identity-profiles.md) | Git identity 프로필 | S | 7 | UND-06 · UND-11 | `domain/identity/` · `infrastructure/identity/` |
| [UND-38](UND-38-operation-history-undo.md) | 실행 이력 · Undo 스택 | L | 7 | UND-09 · UND-21 | `domain/undo/` · `application/undo/` |
| [UND-39](UND-39-external-diff-merge-tool.md) | 외부 diff/merge 도구 연동 | S | 7 | UND-05 · UND-11 | `infrastructure/externaltool/` |
| [UND-40](UND-40-preferences-screen.md) | 설정 화면 | M | 8 | UND-10 · UND-11 · UND-37 · UND-39 | `presentation/preferences/` |
| [UND-41](UND-41-blame-history-view.md) | Blame 뷰 · 파일 이력 화면 | M | 8 | UND-10 · UND-29 | `presentation/blame/` |
| [UND-42](UND-42-graph-drag-drop.md) | 그래프 드래그&드롭 조작 | L | 8 | UND-14 · UND-21 · UND-28 | `presentation/graph/` (dnd 확장) |
| [UND-43](UND-43-undo-history-panel.md) | Undo 버튼 · 실행 이력 패널 | M | 8 | UND-10 · UND-38 | `presentation/undo/` |
| [UND-44](UND-44-multi-repo-tabs.md) | 다중 저장소 탭 | L | 8 | UND-02 · UND-12 | `presentation/tabs/` · `presentation/shell/` |
| [UND-45](UND-45-submodule-worktree-panel.md) | Submodule · Worktree 패널 | M | 8 | UND-10 · UND-32 · UND-34 | `presentation/submodule/` |
| [UND-46](UND-46-reflog-bisect-screen.md) | Reflog · Bisect 화면 | M | 8 | UND-10 · UND-30 · UND-35 | `presentation/recovery/` |
| [UND-47](UND-47-patch-screen.md) | Patch 화면 | M | 8 | UND-10 · UND-31 | `presentation/patch/` |
| [UND-48](UND-48-auto-update.md) | 자동 업데이트 | M | 8 | UND-25 | `infrastructure/update/` · `build.gradle.kts` |
| [UND-50](UND-50-accessibility-audit.md) | 접근성 감사 · 보강 | M | 9 | UND-40 · UND-41 · UND-42 · UND-43 · UND-44 · UND-45 · UND-46 · UND-47 | `presentation/**` (감사 결과 보강) |
| [UND-51](UND-51-wiring-phase2.md) | 2차 통합 와이어업 | M | 9 | UND-40 · UND-41 · UND-42 · UND-43 · UND-44 · UND-45 · UND-46 · UND-47 · UND-48 | `presentation/App.kt` · `di/` · `presentation/palette/` (등록) |
| [UND-52](UND-52-e2e-scenario-phase2.md) | 2차 E2E 시나리오 테스트 | M | 10 | UND-51 | `app/src/test/kotlin/.../scenario2/` |

> **UND-49(i18n)만 wave 2 에 있다.** 2차 범위에서 추가됐지만, 나중에 넣으면 이미 작성된 전 화면의
> 문자열을 추출해야 해 거대한 retrofit 티켓이 된다. 구현 착수 전인 지금 선행 티켓으로 옮겨
> **모든 UI 티켓이 처음부터 문자열 리소스를 쓰게** 했다. 비용이 거의 0이 되는 유일한 시점이다.

사이즈 기준: S ≈ 200줄 · M ≈ 400줄 · L ≈ 800줄 (구현 코드 기준, 테스트 제외).

## 공통 규약 (전 티켓 적용)

각 티켓 md 에 반복해 적지 않고 여기에 한 번만 둔다.

1. **UI 티켓은 문자열을 하드코딩하지 않는다** — UND-49 의 문자열 리소스를 통해서만 표시 문자열을 쓴다.
2. **UI 티켓은 색을 하드코딩하지 않는다** — UND-10 의 디자인 토큰을 통해서만 색을 쓴다.
3. **모든 변경 연산은 UND-38 Undo 스택에 기록한다** — 되돌릴 수 없는 연산은 사유와 함께 기록한다.
4. **주요 동작에는 키보드 경로가 있어야 한다** — 드래그·마우스 전용 동작을 만들지 않는다 (UND-50 이 감사).
5. **JGit `AutoCloseable` 은 `use {}` 로만 연다** — [`jgit-usage`](../.agent/rules/jgit-usage.md).

## 의존 DAG (wave 수준)

```mermaid
flowchart LR
    subgraph P1["1차 — 일상 사용"]
        W1["wave 1<br/>스캐폴딩·계약<br/>(1)"]
        W2["wave 2<br/>Gateway·디자인·i18n<br/>(11)"]
        W3["wave 3<br/>UI·병합엔진·검색<br/>(11)"]
        W4["wave 4<br/>충돌·리베이스UI·패키징<br/>(3)"]
        W5["wave 5<br/>통합 와이어업<br/>(1)"]
        W6["wave 6<br/>E2E<br/>(1)"]
    end
    subgraph P2["2차 — 완성도"]
        W7["wave 7<br/>Gateway 확장<br/>(12)"]
        W8["wave 8<br/>화면·업데이트<br/>(9)"]
        W9["wave 9<br/>접근성·2차 와이어업<br/>(2)"]
        W10["wave 10<br/>2차 E2E<br/>(1)"]
    end
    W1 --> W2
    W2 --> W3
    W3 --> W4
    W4 --> W5
    W5 --> W6
    W6 --> W7
    W7 --> W8
    W8 --> W9
    W9 --> W10
```

티켓 단위 의존은 위 목록 표의 "의존" 열이 정본이다 — 다이어그램 노드는 15개 이하로 유지한다.

## 위상정렬 시뮬레이션 (강제 게이트)

| wave | 티켓 | 너비 |
|---|---|---|
| 1 | UND-01 | 1 |
| 2 | UND-02, UND-03, UND-04, UND-05, UND-06, UND-07, UND-08, UND-09, UND-10, UND-11, UND-49 | 11 |
| 3 | UND-12, UND-13, UND-14, UND-15, UND-16, UND-17, UND-18, UND-19, UND-20, UND-21, UND-22 | 11 |
| 4 | UND-23, UND-24, UND-25 | 3 |
| 5 | UND-26 | 1 |
| 6 | UND-27 | 1 |
| 7 | UND-28, UND-29, UND-30, UND-31, UND-32, UND-33, UND-34, UND-35, UND-36, UND-37, UND-38, UND-39 | 12 |
| 8 | UND-40, UND-41, UND-42, UND-43, UND-44, UND-45, UND-46, UND-47, UND-48 | 9 |
| 9 | UND-50, UND-51 | 2 |
| 10 | UND-52 | 1 |

- **너비 분포**: [1, 11, 11, 3, 1, 1, 12, 9, 2, 1]
- **평균 wave 너비**: 5.20
- **판정: 통과** — 모든 wave 너비가 1~2 인 직선형 DAG 가 아니다.
  wave 2·3·7·8 에서 각각 11·11·12·9 개가 동시에 열린다.

꼬리 wave(5·6·10)의 너비 1은 **의도**다. 와이어업과 E2E 는 앞선 결과 전체를 전제로 하는
단일 책임이라 쪼개면 오히려 충돌을 만든다.

> **wave 7 의 실제 시작 시점**: wave 7 티켓의 진짜 의존은 대부분 wave 1~3 에 있어 **1차 완료 전에도
> 착수 가능**하다. 여기서 wave 7 로 둔 것은 "1차를 먼저 끝낸다" 는 일정상의 선택이지 기술적 제약이 아니다.

## 병목 분석

후행 의존이 3건 이상인 티켓 (분해 재검토 트리거):

| 티켓 | 후행 의존 수 | 제목 |
|---|---|---|
| UND-10 | 17 | 디자인 시스템 · 테마 |
| UND-01 | 14 | 프로젝트 스캐폴딩 · 공통 계약 정의 |
| UND-21 | 6 | 병합 · 리베이스 실행 |
| UND-02 | 4 | 저장소 열기 · 워킹트리 상태 조회 |
| UND-03 | 4 | 커밋 이력 조회 (페이징) |
| UND-05 | 4 | Diff 계산 (파일 · hunk · word-level) |
| UND-11 | 4 | 앱 설정 · 최근 저장소 영속화 |
| UND-06 | 3 | 스테이징 · 커밋 |
| UND-08 | 3 | 원격 동기화 (clone/fetch/pull/push) · 인증 |

- **UND-01** 은 불가피한 병목이다. 빌드 골격 없이는 어떤 계약도 컴파일되지 않으므로
  빌드 설정과 도메인 계약은 **연관 병목**이며 한 티켓으로 묶었다. 쪼개면 wave 1→2 가 직렬 사슬이 된다.
- **UND-10**(디자인 시스템)·**UND-49**(i18n)는 UND-01 과 **독립적인** 병목이라 분리해 wave 2 를 넓혔다.
  서로 다른 패키지를 만들어 교집합이 없다.
- **UND-26 / UND-51**(와이어업)은 후행이 아니라 **선행이 많다**. UI 티켓이 공통 파일(`App.kt`·DI 배선)을
  건드리지 않도록 미뤄 둔 통합 티켓이며, 각 단계 마지막에 단독 배치했다.

## 파일 교집합 검증 (Single Writer per File)

| wave | 검증 |
|---|---|
| 1 | 티켓 1개 — 교집합 없음 |
| 2 | `infrastructure/git/<하위>` 7종 · `domain/graph` · `infrastructure/settings` · `presentation/design` · `presentation/i18n`. **교집합 ∅** |
| 3 | `presentation/<하위>` 10종 · `domain/merge`+`application/merge`. **교집합 ∅** |
| 4 | `presentation/conflict` · `presentation/rebase` · 빌드 스크립트. **교집합 ∅** |
| 5 | 티켓 1개 (`App.kt`·`di/`) — 1차 공통 파일 수정을 여기로 모았다 |
| 6 | 티켓 1개 (테스트 신규 패키지) |
| 7 | `infrastructure/git/<하위>` 7종 · `domain/<하위>` 4종 · `infrastructure/identity` · `infrastructure/externaltool`. **교집합 ∅** |
| 8 | `presentation/<하위>` 6종 · `presentation/graph`(dnd) · `presentation/tabs`+`shell` · `infrastructure/update`. **교집합 ∅** |
| 9 | UND-50 은 컴포넌트 파일 보강, UND-51 은 `App.kt`·DI·레지스트리. **교집합 ∅** |
| 10 | 티켓 1개 (테스트 신규 패키지) |

교차 wave 재수정 파일:

| 파일 | 최초 | 재수정 |
|---|---|---|
| `build.gradle.kts` | UND-01 (w1) | UND-25 (w4) · UND-48 (w8) |
| `presentation/App.kt` · `di/` | UND-01 (w1) | UND-26 (w5) · UND-51 (w9) |
| `presentation/shell/` | UND-12 (w3) | UND-44 (w8) |
| `presentation/graph/` | UND-14 (w3) | UND-42 (w8) |

전부 **서로 다른 wave** 라 동시 수정이 발생하지 않는다.

## 티켓 md 규약

**AC(인수 조건)와 생성/수정 파일 목록은 넣지 않는다** — 파일 목록의 기준은 코드베이스다.

```markdown
# [UND-NN] 제목
> wave · 사이즈 · 의존 · 소유 패키지

## 작업 내용 (설계 의도)
## 다이어그램 (처리 흐름 sequenceDiagram + 클래스 의존 flowchart LR)
## 테스트 케이스   ← 해피·실패·엣지 최소 3개
```

파괴적 변경·상태 전이가 있는 티켓은 **롤백 방법을 1줄 명시**한다.

## 작업 흐름

```
/custom-develop-orchestrator UND-NN   # 스펙 → 승인 게이트 → 구현 → 5축 검증
/custom-affected-test-runner          # 변경 범위 테스트 실행
/custom-self-code-review              # push 전 5축 자가 점검
/custom-pr-create                     # Draft PR
```

브랜치는 `feat/UND-NN-<slug>`, 커밋 접두사는 `[UND-NN]` 이다.
