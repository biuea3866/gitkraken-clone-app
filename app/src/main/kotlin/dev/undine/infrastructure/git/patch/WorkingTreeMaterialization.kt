package dev.undine.infrastructure.git.patch

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * [treeId] 안에서 [paths] 아래에 있는 blob 을 전부 읽는다.
 *
 * 정확한 경로 일치가 아니라 **접두 일치**로 모으는 것이 요점이다. 대상 경로가 디렉터리일 수 있고
 * (`a` 삭제 + `a/b` 추가처럼) 적용 전후로 파일↔디렉터리가 뒤바뀔 수 있어서, 경로 하나를 항목
 * 하나로 보면 복원이 어긋난다.
 */
internal fun Repository.readTreeBlobs(treeId: ObjectId, paths: List<String>): Map<String, TreeBlob> {
    if (paths.isEmpty()) return emptyMap()
    val blobs = mutableMapOf<String, TreeBlob>()
    RevWalk(this).use { walk ->
        TreeWalk(this).use { treeWalk ->
            treeWalk.addTree(walk.parseTree(treeId))
            treeWalk.isRecursive = true
            treeWalk.filter = PathFilterGroup.createFromStrings(paths)
            while (treeWalk.next()) {
                blobs[treeWalk.pathString] = TreeBlob(treeWalk.getFileMode(0), treeWalk.getObjectId(0))
            }
        }
    }
    return blobs
}

/**
 * [paths] 아래의 워킹트리를 [treeId] 와 **같게** 만든다. 승격과 복원이 같은 함수를 쓴다 —
 * 복원이 승격과 다른 코드였다면 "복원 경로에만 있는 버그" 가 생긴다.
 *
 * 대상에 없는데 워킹트리에 있는 항목을 먼저 지우고, 그다음 트리 항목을 쓴다.
 */
internal fun Repository.materialize(treeId: ObjectId, paths: List<String>) {
    val target = readTreeBlobs(treeId, paths)
    val present = workingTreeFilesUnder(paths)
    (present - target.keys).sortedByDescending(String::length).forEach { path -> deleteEntry(path) }
    target.forEach { (path, blob) -> writeEntry(path, blob) }
}

/**
 * [paths] 아래에 **실제로 존재하는** 워킹트리 항목의 경로.
 *
 * 링크를 따라가지 않는다(`NOFOLLOW`). 따라가면 `a` 가 심볼릭 링크일 때 링크 **바깥** 파일이
 * 목록에 들어오고, 정리 단계가 저장소 밖을 지운다.
 *
 * `NOFOLLOW` 는 경로의 **마지막 요소**에만 적용되므로 조상은 그것만으로 막히지 않는다 —
 * `a` 가 링크면 `root.resolve("a/b")` 는 이미 바깥을 가리킨다. 그래서 조상까지 함께 본다
 * ([hasSymbolicLinkAncestor]).
 */
internal fun Repository.workingTreeFilesUnder(paths: List<String>): Set<String> {
    val root = workTreeRoot()
    val found = sortedSetOf<String>()
    paths.forEach { path ->
        if (root.hasSymbolicLinkAncestor(path)) return@forEach
        val start = root.resolve(path)
        when {
            !Files.exists(start, LinkOption.NOFOLLOW_LINKS) -> Unit
            Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS) -> found += root.filesUnder(start)
            else -> found += path
        }
    }
    return found
}

internal fun Path.filesUnder(start: Path): List<String> {
    val found = mutableListOf<String>()
    Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                found += relativize(file).joinToString(SEPARATOR)
                return FileVisitResult.CONTINUE
            }
        },
    )
    return found
}

/**
 * 워킹트리의 그 자리가 트리 항목과 **내용·mode 까지** 같은가.
 *
 * 경로 존재만 보면 절반만 쓰인 파일이나 실행 비트가 안 붙은 파일을 성공으로 넘긴다.
 * 링크는 따라가지 않고 링크 자신의 대상 문자열을 비교한다.
 */
internal fun Repository.workingTreeMatches(path: String, blob: TreeBlob): Boolean {
    val target = workTreeRoot().resolve(path)
    val expected = newObjectReader().use { reader -> reader.open(blob.blobId, Constants.OBJ_BLOB).bytes }
    if (blob.fileMode == FileMode.SYMLINK) {
        return Files.isSymbolicLink(target) &&
            Files.readSymbolicLink(target).toString() == expected.toString(Charsets.UTF_8)
    }
    val isPlainFile = !Files.isSymbolicLink(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
    // 실행 비트를 표현하지 못하는 파일시스템에서는 mode 를 비교하지 않는다 — 비교하면 정상 승격이
    // 매번 검증에 걸려 되돌려지고, 그 환경에서는 패치 적용 자체가 불가능해진다.
    val modeMatches = !fs.supportsExecute() ||
        (isPlainFile && target.toFile().canExecute() == (blob.fileMode == FileMode.EXECUTABLE_FILE))
    return isPlainFile && modeMatches && Files.readAllBytes(target).contentEquals(expected)
}

private fun Repository.deleteEntry(path: String) {
    val root = workTreeRoot()
    // 조상이 링크면 그 아래는 저장소 밖이다 — 지울 것이 없다. 링크 자신은 자기 경로로 지워진다.
    if (root.hasSymbolicLinkAncestor(path)) return
    val target = root.resolve(path)
    target.deleteWhateverIsThere()
    target.parent?.pruneEmptyDirectoriesUpTo(root)
}

private fun Repository.writeEntry(path: String, blob: TreeBlob) {
    val root = workTreeRoot()
    val target = root.resolve(path)
    target.parent?.ensureRealDirectoriesUnder(root)
    target.deleteWhateverIsThere()
    val content = newObjectReader().use { reader -> reader.open(blob.blobId, Constants.OBJ_BLOB).bytes }
    if (blob.fileMode == FileMode.SYMLINK) {
        Files.createSymbolicLink(target, Path.of(content.toString(Charsets.UTF_8)))
        return
    }
    Files.write(target, content)
    fs.setExecute(target.toFile(), blob.fileMode == FileMode.EXECUTABLE_FILE)
}

/**
 * 그 자리에 있는 것이 파일이든 디렉터리든 심볼릭 링크든 치운다.
 *
 * 심볼릭 링크는 **링크 자신**을 지운다(`Files.delete` 는 링크를 따라가지 않는다). 디렉터리는
 * 링크를 따라가지 않고 재귀 삭제한다 — 따라가면 `a` 가 링크로 바뀐 뒤의 정리가 바깥 대상을 지운다.
 */
private fun Path.deleteWhateverIsThere() {
    if (!Files.exists(this, LinkOption.NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(this)
        return
    }
    Files.walkFileTree(
        this,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

/**
 * 조상을 **진짜 디렉터리**로 만든다. 심볼릭 링크나 파일이 조상 자리에 있으면 치우고 디렉터리를 만든다 —
 * `createDirectories` 만 부르면 링크를 따라가 저장소 밖에 파일을 쓴다.
 */
private fun Path.ensureRealDirectoriesUnder(root: Path) {
    var ancestor = root
    root.relativize(this).forEach { segment ->
        ancestor = ancestor.resolve(segment)
        if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor.deleteWhateverIsThere()
            Files.createDirectory(ancestor)
        }
    }
}

/** git 은 디렉터리를 추적하지 않는다 — 항목을 지운 뒤 남은 빈 디렉터리는 잔재이므로 함께 걷는다. */
private fun Path.pruneEmptyDirectoriesUpTo(root: Path) {
    var current = this
    while (current != root && current.startsWith(root) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        val removed = runCatching { Files.delete(current) }.isSuccess
        if (!removed) return
        current = current.parent ?: return
    }
}

internal const val SEPARATOR = "/"
