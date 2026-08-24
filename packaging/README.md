# 패키징 · 배포

네이티브 배포본을 만들고 설치하는 절차. 설정의 SSOT 는 `app/build.gradle.kts` 의
`nativeDistributions` 블록이고, 버전은 `gradle.properties` 의 `undine.version` 하나다.

## 만들기

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # gradle.properties 의 undine.jvm 과 일치
./gradlew packageDistributionForCurrentOS
```

**현재 OS 형식만 생성된다** — jpackage 는 크로스 빌드를 하지 않아 다른 OS 의 태스크는 `SKIPPED` 로
지나간다. 산출물 위치:

| OS | 산출물 |
|---|---|
| macOS | `app/build/compose/binaries/main/dmg/Undine-<버전>.dmg` |
| Windows | `app/build/compose/binaries/main/msi/Undine-<버전>.msi` |
| Linux | `app/build/compose/binaries/main/deb/undine_<버전>.deb` |

설치 없이 실행해 보려면 `createDistributable` 산출물을 바로 띄운다.

```bash
./gradlew createDistributable
open app/build/compose/binaries/main/app/Undine.app        # macOS
```

## 아이콘

`packaging/undine.{icns,ico,png}` 는 **생성물**이다. 모양을 바꾸려면 생성기를 고치고 다시 만든다.

```bash
python3 packaging/make-icons.py
```

표준 라이브러리만 쓰며(PIL·ImageMagick 불필요), `icns` 변환만 macOS 의 `sips`·`iconutil` 을 쓴다.
아이콘이 없으면 `verifyPackagingAssets` 가 패키징 **시작 전에** 멈추고 이 명령을 알려준다 —
jpackage 가 한참 뒤 알아보기 어려운 오류로 실패하는 것을 막는다.

## 런타임 모듈

`nativeDistributions.modules(...)` 에 빠진 모듈은 **빌드가 아니라 실행 시점에** 터진다. 목록의 근거:

```bash
./gradlew suggestRuntimeModules
```

이 명령(jdeps 정적 분석)이 준 여섯 개에 `jdk.crypto.ec` 를 더했다. TLS ECDHE 곡선 구현은 서비스
제공자라 정적 참조가 없어 분석에 잡히지 않고, 없으면 **https 원격 접속이 핸드셰이크에서 실패한다.**
의존성을 추가하면 이 명령을 다시 돌려 목록을 갱신한다.

## macOS 첫 실행 — Gatekeeper

**서명·공증은 하지 않는다** (개인 사용 목적). 그래서 처음 열 때 macOS 가
"개발자를 확인할 수 없어 열 수 없습니다" 로 막는다. 통과 방법은 두 가지다.

1. **Finder 에서 우클릭 → 열기** — 대화상자에 "열기" 버튼이 생긴다. 한 번만 하면 다음부터는 그냥 열린다.
2. **격리 속성 제거** — 위 방법이 막히는 경우(최신 macOS 에서 다운로드 경로에 따라 다르다):

   ```bash
   xattr -d com.apple.quarantine /Applications/Undine.app
   ```

두 방법 모두 **자기가 빌드한 앱**에만 쓴다. 출처를 모르는 앱에 격리 속성을 지우는 것은 Gatekeeper 를
끄는 것과 같다.

## 버전

`gradle.properties` 의 `undine.version` 이 SSOT 다. 빌드가 이 값으로

- 번들 메타데이터(`CFBundleShortVersionString`·`CFBundleVersion`)를 채우고,
- `dev.undine.BuildInfo.VERSION` 을 생성해 창 제목에 붙인다.

`BuildInfoSpec` 이 둘의 일치와 형식(`MAJOR.MINOR.PATCH`, MAJOR ≥ 1)을 검증한다. macOS jpackage 가
major 0 을 거부하므로 `0.x` 로는 dmg 를 만들 수 없다 — 릴리즈 태그(`/custom-release-tagger` 의
`vX.Y.Z`)와 같은 값을 유지한다.
