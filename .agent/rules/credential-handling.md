---
name: credential-handling
description: Git 자격증명(SSH 키·토큰) 취급 규칙
paths:
  - "**/*.kt"
---

# 자격증명 취급

원격 저장소 접근에 필요한 자격증명은 **앱이 다루는 가장 민감한 데이터**다.

1. **평문 저장 금지.** 토큰·패스프레이즈를 설정 JSON 이나 로그 파일에 쓰지 않는다.
   OS 키체인(macOS Keychain / Windows Credential Manager)에 위임한다.
2. **로그·예외 메시지에 노출 금지.** JGit 예외를 그대로 UI 에 던지면 URL 에 포함된 토큰이 화면에 뜬다.
   원격 관련 예외는 도메인 예외로 감싸면서 자격증명 구간을 마스킹한다.
3. **SSH 키 파일을 읽어 메모리에 오래 들고 있지 않는다.** 사용 직후 참조를 버린다.
4. **기존 `~/.ssh/config` 와 credential helper 를 존중한다.** 사용자가 이미 설정한 인증 경로를
   앱이 우회하거나 덮어쓰지 않는다.
5. **호스트 키 검증을 끄지 않는다.** 편의를 위해 `StrictHostKeyChecking` 을 무력화하지 않는다.

위반은 p0 이다 — 자격증명 유출은 되돌릴 수 없다.
