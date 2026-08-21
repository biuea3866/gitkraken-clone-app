package dev.undine.infrastructure.git.remote

import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File

/**
 * SSH 원격 전송에 쓸 세션 팩토리를 만들고 JGit 에 등록한다. core JGit 만으로는 `git@` URL 에 붙을 수 없다.
 *
 * - 사용자의 `~/.ssh/config` 와 키를 **그대로** 쓴다. 앱이 별도 키를 만들거나 설정을 덮어쓰지 않는다.
 * - 호스트 키 검증은 JGit 기본 동작(`known_hosts` 확인)을 그대로 둔다. **끄지 않는다.**
 * - ssh-agent 위임은 JGit 의 기본 커넥터가 처리한다. agent 가 없고 키도 못 읽으면 인증은 실패하며,
 *   [GitCredentialHelperProvider] 가 익명 접근으로 대체하지 않는다.
 *
 * JGit 은 [SshSessionFactory.getInstance] 로 전역 팩토리를 찾으므로, 등록하지 않으면 SSH 원격이
 * 동작하지 않는다. 등록을 앱 배선 티켓까지 미루면 그때까지 SSH 위임 자체가 성립하지 않아
 * [RemoteGatewayImpl] 이 **첫 원격 작업에서 한 번** [installOnce] 를 호출한다.
 */
object UndineSshSessionFactory {

    /**
     * 등록을 **끝낸 뒤에** 완료로 표시해야 한다 — 플래그를 먼저 세우면 그 사이에 들어온 원격 작업이
     * 아직 바뀌지 않은 기본 팩토리로 전송한다. `lazy` 는 초기화가 끝날 때까지 다른 스레드를 대기시켜
     * 그 창을 없앤다.
     */
    private val installation: Unit by lazy { SshSessionFactory.setInstance(create()) }

    /**
     * 전역 SSH 세션 팩토리를 등록한다. 앱 전역 상태를 건드리므로 **중복 등록하지 않는다** —
     * 이미 등록했으면 아무 일도 하지 않고, 등록 중이면 끝날 때까지 기다린다.
     */
    fun installOnce() {
        installation
    }

    fun create(
        homeDirectory: File = File(System.getProperty("user.home")),
        sshDirectory: File = File(homeDirectory, ".ssh"),
    ): SshdSessionFactory =
        SshdSessionFactoryBuilder()
            .setHomeDirectory(homeDirectory)
            .setSshDirectory(sshDirectory)
            .build(null)
}
