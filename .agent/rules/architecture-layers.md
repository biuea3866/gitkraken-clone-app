---
name: architecture-layers
description: 레이어 경계와 의존 방향 — domain/application/infrastructure/presentation
paths:
  - "src/main/kotlin/**/*.kt"
---

# 레이어 경계

Undine 은 Git 이라는 **외부 시스템을 감싸는 데스크톱 앱**이다. 레이어는 "Git 구현을 도메인에서 밀어내는" 목적으로 존재한다.

```
presentation → application → domain ← infrastructure
```

| 레이어 | 책임 | 허용 의존 | 금지 |
|---|---|---|---|
| **presentation** | Compose UI, 상태 보유(ViewModel/StateHolder), 사용자 입력 → Command 변환 | application | 비즈니스 판단, JGit 직접 호출 |
| **application** | UseCase 단위 오케스트레이션, 코루틴 디스패처 결정 | domain | Gateway 구현체 참조 |
| **domain** | 순수 모델·규칙(레인 배치, 상태 전이), Gateway **interface 정의** | (없음) | JGit·Compose·코루틴 프레임워크 import |
| **infrastructure** | domain interface 의 JGit 구현, 파일 시스템·설정 저장 | domain | UI 상태 참조 |

## 강제 규칙

1. **domain 은 아무것도 import 하지 않는다.** `org.eclipse.jgit`·`androidx.compose`·`kotlinx.coroutines` 가 domain 에 등장하면 p1 이다.
   도메인 모델은 JGit 타입(`RevCommit`·`ObjectId`)이 아니라 자체 타입(`CommitId`·`Commit`)을 쓴다.
2. **Gateway interface 는 domain 에, 구현은 infrastructure 에.** 이름은 `~Gateway.kt` / `~GatewayImpl.kt`.
   Git 은 외부 시스템이므로 `Repository` 가 아니라 **`Gateway`** 다 (`Repository` 는 JGit 자체 타입명과 충돌한다).
3. **presentation 은 UseCase 만 호출한다.** Gateway 를 직접 주입받으면 p1.
4. **UseCase 는 얇게.** 조회·검증·실행 순서를 엮는 것까지가 책임이고, 규칙 판단은 domain 에 있다.

## 패키지 배치

```
app/src/main/kotlin/dev/undine/
├── domain/          # 모델 + Gateway interface (순수 Kotlin)
├── application/     # ~UseCase.kt
├── infrastructure/  # ~GatewayImpl.kt (JGit), 설정 저장
└── presentation/    # Compose 화면·컴포넌트·상태 홀더
```

도메인 패키지끼리의 교차 참조는 금지한다 — 공통 타입은 `domain/common` 에 둔다.
