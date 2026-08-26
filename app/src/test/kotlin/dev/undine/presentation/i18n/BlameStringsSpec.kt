package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class BlameStringsSpec : FunSpec({

    test("blame 문자열은 한국어와 영어 카탈로그에서 조회된다") {
        val catalog = builtInStringCatalog()

        catalog.stringsFor(Locale.KOREAN, devBuild = false).blame.ignoreWhitespace shouldBe "공백 무시"
        catalog.stringsFor(Locale.ENGLISH, devBuild = false).blame.ignoreWhitespace shouldBe "Ignore whitespace"
    }

    test("blame 키는 자기 네임스페이스에만 속한다") {
        BlameKeys.fileHistory.namespace shouldBe BLAME_NAMESPACE
        BlameKeys.unsupported.namespace shouldBe BLAME_NAMESPACE
    }
})
