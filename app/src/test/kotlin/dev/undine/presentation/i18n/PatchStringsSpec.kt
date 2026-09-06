package dev.undine.presentation.i18n

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotStartWith
import java.util.Locale

/**
 * `patch.*` 문구 계약 — 접근자가 실제 키를 찾고, 인자 있는 문구가 값을 치환하는가.
 *
 * `StringCatalogSpec` 은 카탈로그 전체의 병합·폴백만 본다. 화면이 실제로 쓰는 접근자와 치환 인자는
 * 여기서 확인한다 — 키 오타나 치환 자리 누락은 병합 테스트를 그대로 통과하기 때문이다.
 */
class PatchStringsSpec : FunSpec({

    val catalog = builtInStringCatalog()
    val korean = catalog.stringsFor(Locale.KOREAN, devBuild = false).patch
    val english = catalog.stringsFor(Locale.ENGLISH, devBuild = false).patch

    fun createAccessorsOf(copy: PatchStrings): List<String> = listOf(
        copy.title,
        copy.create,
        copy.sourceWorkingTree,
        copy.sourceCommits,
        copy.scopeStaged,
        copy.scopeUnstaged,
        copy.scopeAll,
        copy.formatCombined,
        copy.formatPerCommit,
        copy.commitsLoading,
        copy.commitsEmpty,
        copy.commitsFailed,
        copy.commitSelectionHint,
        copy.commitSelectionEmpty,
        copy.prepare,
        copy.preparing,
        copy.prepareFailed,
        copy.includedFiles,
        copy.includedFilesEmpty,
        copy.save,
        copy.saving,
        copy.saveFailed,
        copy.saveRollbackFailed,
        copy.overwriteConfirm,
        copy.overwriteCancel,
        copy.openDialogTitle,
        copy.saveDialogTitle,
        copy.directoryDialogTitle,
    )

    fun applyAccessorsOf(copy: PatchStrings): List<String> = listOf(
        copy.apply,
        copy.chooseFile,
        copy.dropHint,
        copy.dropRejectedEmpty,
        copy.dropRejectedMultiple,
        copy.readingPatch,
        copy.readFailed,
        copy.dryRunRunning,
        copy.dryRunFailed,
        copy.changedFiles,
        copy.conflicted,
        copy.unsupportedThreeWay,
        copy.unsupportedReverse,
        copy.noChange,
        copy.applied,
        copy.applying,
        copy.modeWorkingTree,
        copy.modeIndex,
        copy.modeCommit,
        copy.commitAuthorName,
        copy.commitAuthorEmail,
        copy.commitMessage,
        copy.fromPatchMetadata,
        copy.commitInputRequired,
        copy.preview,
        copy.previewUnified,
        copy.previewSplit,
        copy.previewBinary,
        copy.previewTooLarge,
        copy.previewNoChange,
        copy.previewStillApplicable,
        copy.exitBlocked,
    )

    test("두 로케일 모두에서 인자 없는 접근자가 키 이름으로 폴백하지 않는다") {
        listOf(korean, english).forEach { copy ->
            (createAccessorsOf(copy) + applyAccessorsOf(copy)).forEach { text ->
                text.isBlank() shouldBe false
                // 폴백은 키 id 자체를 돌려준다 — 문구 안에 "a patch." 가 들어갈 수 있어 접두만 본다.
                text shouldNotStartWith "$PATCH_NAMESPACE."
            }
        }
    }

    test("한국어 문구는 실제 번역을 돌려준다") {
        korean.title shouldBe "패치"
        korean.modeWorkingTree shouldBe "워킹트리만"
        korean.formatPerCommit shouldBe "커밋당 파일"
    }

    test("영어 문구는 실제 번역을 돌려준다") {
        english.title shouldBe "Patch"
        english.modeWorkingTree shouldBe "Working tree only"
        english.formatPerCommit shouldBe "One file per commit"
    }

    test("파일 대화상자 제목도 로케일을 따른다 — 배선에 박힌 한국어가 영어 화면에 남지 않는다") {
        korean.openDialogTitle shouldBe "적용할 패치 파일 선택"
        korean.saveDialogTitle shouldBe "패치 저장"
        korean.directoryDialogTitle shouldBe "패치를 저장할 폴더 선택"

        english.openDialogTitle shouldBe "Choose the patch file to apply"
        english.saveDialogTitle shouldBe "Save the patch"
        english.directoryDialogTitle shouldBe "Choose a folder for the patch files"
    }

    test("크기·경로·사유가 들어가는 문구는 인자를 치환한다") {
        listOf(korean, english).forEach { copy ->
            copy.totalSize(4096) shouldContain "4"
            copy.saved("/tmp/out.patch") shouldContain "/tmp/out.patch"
            copy.selectedPatch("fix.patch") shouldContain "fix.patch"
            copy.overwriteWarning("0001.patch") shouldContain "0001.patch"
            copy.saveTemporaryLeft("/tmp/undine-patch1.tmp") shouldContain "/tmp/undine-patch1.tmp"
            copy.blockedStateViolation("경로 이탈") shouldContain "경로 이탈"
        }
    }

    test("치환 자리 표식이 화면 문구에 그대로 남지 않는다") {
        listOf(korean, english).forEach { copy ->
            copy.totalSize(4096) shouldNotContain "{0}"
            copy.saved("/tmp/out.patch") shouldNotContain "{0}"
            copy.blockedStateViolation("경로 이탈") shouldNotContain "{0}"
            copy.saveTemporaryLeft("/tmp/undine-patch1.tmp") shouldNotContain "{0}"
        }
    }
})
