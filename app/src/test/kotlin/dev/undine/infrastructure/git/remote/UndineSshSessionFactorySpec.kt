package dev.undine.infrastructure.git.remote

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.engine.spec.tempdir
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

private const val INSTALLERS = 16

class UndineSshSessionFactorySpec : FunSpec({

    test("사용자의 ~/.ssh 를 그대로 쓰는 세션 팩토리를 만든다") {
        val home = tempdir()
        val sshDirectory = File(home, ".ssh").also { it.mkdirs() }

        UndineSshSessionFactory.create(homeDirectory = home, sshDirectory = sshDirectory).use { factory ->
            factory.homeDirectory shouldBe home
            factory.sshDirectory shouldBe sshDirectory
        }
    }

    test("기본값은 사용자 홈의 ~/.ssh 다") {
        UndineSshSessionFactory.create().use { factory ->
            factory.sshDirectory shouldBe File(File(System.getProperty("user.home")), ".ssh")
        }
    }

    test("SSH 전송 팩토리를 JGit 전역에 등록한다") {
        UndineSshSessionFactory.installOnce()

        SshSessionFactory.getInstance().shouldBeInstanceOf<SshdSessionFactory>()
    }

    test("등록은 중복되지 않는다") {
        UndineSshSessionFactory.installOnce()
        val first = SshSessionFactory.getInstance()

        UndineSshSessionFactory.installOnce()

        SshSessionFactory.getInstance() shouldBeSameInstanceAs first
    }

    test("동시 등록에서도 installOnce 가 끝난 시점에는 전역 팩토리가 이미 바뀌어 있다") {
        val observed = coroutineScope {
            (1..INSTALLERS).map {
                async(Dispatchers.Default) {
                    UndineSshSessionFactory.installOnce()
                    SshSessionFactory.getInstance()
                }
            }.awaitAll()
        }

        // 하나라도 등록 전 팩토리를 봤다면 그 호출은 SSH 없이 전송했을 것이다.
        observed.forEach { factory -> factory.shouldBeInstanceOf<SshdSessionFactory>() }
        observed.distinct() shouldHaveSize 1
    }
})
