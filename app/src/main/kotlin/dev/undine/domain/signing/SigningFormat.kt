package dev.undine.domain.signing

/**
 * git `gpg.format` 이 말하는 서명 형식.
 *
 * [X509] 를 목록에 남기는 이유는 **모르는 형식과 다루지 않는 형식을 구분**하기 위해서다.
 * 설정에 x509 가 들어 있는데 목록에 없으면 화면은 "설정이 잘못됐다" 로 오해하지만,
 * 실제로는 git 이 아는 정상 설정이고 이 앱이 아직 다루지 않을 뿐이다.
 */
enum class SigningFormat {

    /** GPG. 기존 `gpg-agent` 와 `gpg` 프로그램에 위임한다. */
    OPENPGP,

    /** SSH. 기존 `ssh-agent` 와 `ssh-keygen` 에 위임한다. */
    SSH,

    /** git 은 지원하지만 이 앱은 서명·검증을 수행하지 않는다. */
    X509,
}
