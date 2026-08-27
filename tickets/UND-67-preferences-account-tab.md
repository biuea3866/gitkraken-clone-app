# [UND-67] 설정 — 계정 탭 (identity 프로필)

> wave 8 · 사이즈 M · 의존 UND-40 · UND-74 · UND-37 · 소유 `presentation/preferences/AccountPreferences.kt` · `presentation/preferences/AccountPreferencesState.kt`

## 작업 내용 (설계 의도)

UND-37 이 만든 identity 프로필을 사용자가 관리하는 탭이다.

- 프로필 목록 — 이름 · 이메일 · 서명 키
- 추가 · 수정 · 삭제
- **저장소별 매핑** — 현재 저장소에 어느 프로필을 쓸지 지정하거나 해제한다

**삭제는 확인을 받는다.** 되돌릴 수 없는 연산이므로 확인 없이 지우지 않는다.

**"이 프로필을 쓰는 저장소 수" 표시는 후속 티켓이다 (UND-76).** `IdentityGateway` 가 저장소를
훑지 않아 그 수를 셀 계약이 없다. **이메일 형식 검증도 후속이다 (UND-76)** — domain 어디에도
검증이 없어 이 화면이 만들면 검증이 presentation 에 생긴다.
> **이 wave 에서는 범위 밖이다.** 해당 계약이 아직 없어 탭 티켓이 만들면 소유 밖으로 나간다 — 후속 티켓이 계약을 세운 뒤 이 화면에 붙인다.


이 탭은 UseCase 만 호출한다. identity 검증·저장 규칙은 UND-37 의 계약을 그대로 쓰고 새로 만들지 않는다.

**범위 밖**: 탭 셸 · 공통 행 · 문자열 키 (UND-40). identity 계약·저장 (UND-37).

**롤백**: 프로필 삭제는 되돌릴 수 없다 — 확인 게이트가 롤백을 대신한다.

## 다이어그램

### 처리 흐름

```mermaid
sequenceDiagram
    participant User as 사용자
    participant Tab as AccountPreferences
    participant State as AccountPreferencesState
    participant UC as IdentityUseCase
    Tab->>State: 프로필 목록 조회
    State->>UC: list()
    UC-->>State: 프로필 · 저장소 매핑
    User->>Tab: 프로필 삭제
    Tab->>State: requestDelete(id)
    State-->>Tab: 사용 중 저장소 수
    User->>Tab: 확인
    State->>UC: delete(id)
    UC-->>State: 결과
```

### 클래스 의존

```mermaid
flowchart LR
    subgraph pref["presentation/preferences"]
        Account[AccountPreferences]
        AState[AccountPreferencesState]
        Row[PreferencesRow]
    end
    subgraph app["application/identity"]
        UC[IdentityUseCase]
    end
    Account --> AState
    Account --> Row
    AState --> UC
```

## 테스트 케이스

- 프로필을 추가하면 목록에 즉시 나타나고 저장된다
- 현재 저장소에 프로필을 매핑하면 다음 커밋의 author 가 그 프로필이 된다
- 확인 전에는 삭제되지 않는다
- 매핑을 해제하면 전역 git 설정으로 돌아간다
