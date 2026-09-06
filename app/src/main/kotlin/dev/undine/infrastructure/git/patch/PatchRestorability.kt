package dev.undine.infrastructure.git.patch

import dev.undine.domain.UndineException
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Repository
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 트리로 되돌릴 수 없는 상태가 대상에 있으면 **적용 전에** 거부한다.
 *
 * 상태의 **종류를 세지 않는다.** 묻는 것은 하나다 — "이 자리의 지금 상태를 트리에 담았다가 그대로
 * 되돌릴 수 있는가". 종류를 열거하기 시작하면 한 라운드에 하나씩 빠뜨린 종류를 발견하게 된다.
 *
 * git 트리가 담는 것은 추적되는 **파일**뿐이므로 그 하나의 물음에 두 가지가 걸린다: 추적되지 않은
 * 파일과, 파일을 하나도 품지 않은 디렉터리다 ([unrestorableDirectoriesAround]). 둘 다 복원이
 * 되살릴 수 없다 — 담을 수 없는 것을 담은 척하지 않는다.
 */
internal fun Repository.requireRestorableTarget(paths: List<String>) {
    val tracked = indexEntriesUnder(paths).map(DirCacheEntry::getPathString).toSet()
    val unrestorable = (workingTreeFilesUnder(paths) - tracked) + unrestorableDirectoriesAround(paths)
    if (unrestorable.isNotEmpty()) {
        throw UndineException.StateViolation(
            "적용 대상에 트리로 되돌릴 수 없는 상태가 있어 복원을 보장할 수 없습니다: " +
                unrestorable.sorted().joinToString(),
        )
    }
}

/**
 * [paths] 자리에서 **트리가 되살릴 수 없는 디렉터리**.
 *
 * 파일을 하나도 품지 않은 디렉터리는 어떤 트리를 펼쳐도 다시 생기지 않는다. 그런 자리가 적용 대상에
 * 걸리면 두 경로로 사라진다: 승격이 대상 자리를 통째로 치우거나, 복원이 항목을 지운 뒤 빈 조상을
 * 루트까지 걷어낸다. 그래서 **대상 자리 아래**와 **조상**을 함께 본다.
 *
 * 조상은 자기 자신만 본다 — 조상 아래를 훑으면 대상과 무관한 형제 빈 디렉터리까지 걸린다.
 * 그것은 사라질 자리가 아니다(빈 조상 정리는 비어 있지 않은 자리에서 멈춘다).
 *
 * 링크 조상 아래는 저장소 항목이 아니므로 보지 않는다 — 그 경로는 애초에 거부된다
 * ([requireSafePatchPaths]).
 */
private fun Repository.unrestorableDirectoriesAround(paths: List<String>): Set<String> {
    val root = workTreeRoot()
    val found = sortedSetOf<String>()
    paths.forEach { path ->
        if (root.hasSymbolicLinkAncestor(path)) return@forEach
        found += root.fileLessDirectoriesUnder(root.resolve(path))
        found += path.ancestorPaths().filter { ancestor -> root.isFileLessDirectory(root.resolve(ancestor)) }
    }
    return found
}

/** [node] 아래(자기 자신 포함)에서 파일을 하나도 품지 않은 디렉터리. [node] 가 디렉터리가 아니면 없다. */
private fun Path.fileLessDirectoriesUnder(node: Path): List<String> {
    if (!Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val directories = mutableListOf<Path>()
    val files = mutableListOf<Path>()
    Files.walkFileTree(
        node,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                directories.add(directory)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                files.add(file)
                return FileVisitResult.CONTINUE
            }
        },
    )
    return directories.filter { directory -> files.none { file -> file.startsWith(directory) } }
        .map { directory -> relativize(directory).joinToString(SEPARATOR) }
}

private fun Path.isFileLessDirectory(node: Path): Boolean =
    Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS) && filesUnder(node).isEmpty()
