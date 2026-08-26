# [UND-66] 설정 — Git 탭

> wave 8 · 사이즈 S · 의존 UND-40 · UND-11 · UND-36 · 소유 `presentation/preferences/GitPreferences.kt` · `presentation/preferences/GitPreferencesState.kt`

## 작업 내용 (설계 의도)

Git 동작 기본값을 다루는 탭이다.

| 항목 | 값 |
|---|---|
| 기본 브랜치명 | 새 저장소·클론 시 쓸 이름 |
| pull 방식 | merge · rebase |
| fetch 주기 | 끔 · N 분 |
| 커밋 서명 활성화 | 켬 · 끔 (UND-36) |

**실효값 출처를 반드시 표시한다.** 이 탭의 항목은 저장소의 git 설정이 앱 설정을 이기는 경우가 있다 —
앱에서 바꿨는데 안 먹는 이유를 화면에서 알 수 있어야 한다. 어느 쪽이 적용 중인지 UND-40 의 공통 행이
표시하고, 이 탭은 각 항목의 git 설정 값을 조회해 넘긴다.

커밋 서명은 켜기만 하고 서명 키가 없으면 **켜지지 않는다** — 키가 없다는 사실과 어디서 설정하는지를
같이 알린다.

**범위 밖**: 탭 셸 · 공통 행 · 문자열 키 (UND-40). 서명 키 관리 자체 (UND-36).

## 다이어그램

### 처리 흐름

```mermaid
sequenceDiagram
    participant User as 사용자
    participant Tab as GitPreferences
    participant State as GitPreferencesState
    participant UC as SettingsUseCase
    Tab->>State: 실효값 조회
    State->>UC: 앱 설정 · git 설정
    UC-->>State: 두 값 + 적용 중인 쪽
    User->>Tab: pull 방식 변경
    Tab->>State: selectPullMode(mode)
    State->>UC: 즉시 저장
    UC-->>State: 반영 결과
```

### 클래스 의존

```mermaid
flowchart LR
    subgraph pref["presentation/preferences"]
        Git[GitPreferences]
        GState[GitPreferencesState]
        Row[PreferencesRow]
        PState[PreferencesState]
    end
    Git --> GState
    Git --> Row
    GState --> PState
```

## 테스트 케이스

- pull 방식을 rebase 로 바꾸면 즉시 저장된다
- git 설정이 앱 설정을 이기는 항목은 실효값 출처가 "git 설정" 으로 표시된다
- 서명 키가 없는 상태에서 커밋 서명을 켜면 켜지지 않고 사유가 표시된다
- fetch 주기를 끔으로 두면 주기 값 입력이 비활성화된다
- 기본 브랜치명을 빈 문자열로 두면 저장하지 않고 입력 오류를 표시한다
