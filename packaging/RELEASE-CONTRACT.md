# 릴리즈 발행 계약

자동 업데이트 클라이언트(UND-48)가 **코드로 읽는** 계약이다. 릴리즈 페이지를 눈으로 보고 추측하지
않도록 여기에 고정한다. 값이 흔들리면 업데이트는 예외를 던지지 않고 **조용히 아무것도 찾지 못한다.**

발행하는 쪽은 `.github/workflows/release.yml` 이고, 계약 판단(태그 대조·자산명·체크섬·멱등 발행)은
`packaging/release-assets.sh` · `packaging/publish-release.sh` 에 있다. 두 스크립트는
`app/src/test/kotlin/dev/undine/packaging/` 의 Kotest 가 실제로 실행해 검증한다.

## 조회 좌표

| 항목 | 값 |
|---|---|
| 저장소 | `biuea3866/gitkraken-clone-app` |
| 태그 형식 | `vX.Y.Z` (`X`·`Y`·`Z` 는 정수) |
| 릴리즈 | 태그 하나에 릴리즈 하나 |

버전 SSOT 는 `gradle.properties` 의 `undine.version` 이다. 태그의 `X.Y.Z` 가 이 값과 다르면
**발행이 중단된다** — 어긋난 채 올라가면 업데이트가 잘못된 버전을 설치한다.

`MAJOR ≥ 1` 이어야 한다. 두 값이 같더라도 `v0.x.y` 는 태그 검증에서 거부된다 —
**macOS jpackage 가 major 0 을 거부해 dmg 를 만들 수 없기 때문이다**
([`packaging/README.md`](README.md) 의 "버전" 절, `BuildInfoSpec` 이 같은 형식을 검증한다).
통과시키면 태그 검증은 지나가고 macOS 패키징 잡에서 실패해 릴리즈가 만들어지지 않는다.

### 좌표는 클라이언트에서 한 곳에만 둔다

**발행 쪽(워크플로·스크립트)은 이 문자열을 쓰지 않는다.** GitHub Actions 는 실행 중인 저장소를
스스로 안다(`github.repository`) — 워크플로가 레포 이름을 박아 두면 포크에서 엉뚱한 곳을 본다.

**UND-48(클라이언트)은 다르다.** 실행 중인 앱은 자기가 어느 저장소에서 왔는지 모르므로 좌표가
어딘가에 박혀야 한다. 그때 **한 곳에서만 정의한다** — `dev.undine.BuildInfo` 가 이미 빌드 시점에
`undine.version` 을 담고 있으니 같은 자리가 자연스럽다. 문자열을 여러 파일에 흩으면 저장소를
옮길 때 하나가 남는다.

> `BuildInfo` 에 실제로 값을 심는 것은 UND-48 의 일이다 (`app/build.gradle.kts` 는 UND-48 소유).
> 이 계약은 "여기서 읽어라" 를 정하는 데까지만 한다.

## 자산 이름

```
undine-<version>-<os>.<ext>
```

`<version>` 은 태그에서 `v` 를 뗀 값이다. 러너 이름(`ubuntu-latest` 등)이 아니라 아래 **OS 토큰**
세 개만 쓴다.

| OS 토큰 | 확장자 | 자산 이름 | 예 (`1.0.0`) |
|---|---|---|---|
| `macos` | `dmg` | `undine-<version>-macos.dmg` | `undine-1.0.0-macos.dmg` |
| `windows` | `msi` | `undine-<version>-windows.msi` | `undine-1.0.0-windows.msi` |
| `linux` | `deb` | `undine-<version>-linux.deb` | `undine-1.0.0-linux.deb` |

Gradle 산출물 이름(`Undine-1.0.0.dmg` · `undine_1.0.0-1_amd64.deb`)은 이 계약명과 **다르다.**
발행 전에 `release-assets.sh stage` 가 계약명으로 옮긴다 — 클라이언트는 계약명만 알면 된다.

## 무결성 — `checksums.txt`

| 항목 | 값 |
|---|---|
| 알고리즘 | SHA-256 |
| 자산 이름 | `checksums.txt` (릴리즈마다 하나) |
| 줄 형식 | `<64자리 소문자 hex><공백 2개><파일명>` |

`shasum -a 256` / `sha256sum` 의 텍스트 모드 출력 그대로다. 파일명은 경로 없는 자산 이름이다.

```
3e62c6b05e1130129263e602136a6318db0d93e2fccf8cc4807d409d24d9c952  undine-1.0.0-macos.dmg
1d1dd9a0c14cb4f0b823b22a1a20e70889886edfbfeeb06b93a6b01bfb64cc6f  undine-1.0.0-windows.msi
c6ced9f772ab08b591a1d3a1057bf4fd267ab64b1536d170f61722afb16677de  undine-1.0.0-linux.deb
```

`checksums.txt` 자신은 목록에 들어가지 않는다.

## 발행 보장

- **부분 발행이 없다.** 세 OS 패키징이 모두 성공한 뒤에만 릴리즈를 만든다. 하나라도 실패하면
  릴리즈 자체가 만들어지지 않는다 — 자산이 반만 붙은 릴리즈는 클라이언트에게 "내 OS 것만 없다"
  로 보여 조용히 실패한다.
- **재실행이 멱등이다.** 같은 태그로 워크플로를 다시 돌려도 그 태그의 릴리즈는 하나다. 이미 있으면
  **없는 자산만 올리고 이미 붙은 자산은 건드리지 않는다** — 지웠다 올리는 경로가 없어야 그 사이
  실패로 원본이 사라지지 않는다. 자산을 바꿔야 하면 사람이 릴리즈를 내리고 다시 태그한다.
- **서명·공증은 하지 않는다.** UND-25 가 범위 밖으로 선언했고 이 계약이 되살리지 않는다.
  클라이언트의 무결성 근거는 위 SHA-256 이다.

## 미검증 면적

`release.yml` 은 **실제 태그로 실행해 본 적이 없다.** 계약 판단은 스크립트로 빼서 Kotest 가
검증하지만, GitHub Actions 배선(러너·아티팩트 전달·`gh` 인증) 자체는 첫 태그 push 에서 처음 돈다.
