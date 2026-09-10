package dev.undine.testsupport

/**
 * 테스트가 push 대상으로 쓰는 원격 이름.
 *
 * `RemoteGateway.push` 는 **원격을 추측하지 않고 인자로 받는다** — 확인 문장이 말한 대상이
 * 그대로 실행까지 가야 그 확인이 근거를 갖기 때문이다 (UND-95). 그래서 테스트도 대상을 밝힌다.
 */
const val ORIGIN_REMOTE = "origin"
