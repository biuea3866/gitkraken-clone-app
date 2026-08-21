package dev.undine.presentation.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.undine.domain.CommitId
import dev.undine.domain.RepositoryPath

/**
 * 셸이 하위 슬롯에 내려보내는 **읽기 전용 선택 스냅샷**.
 *
 * 슬롯은 이 값을 받을 뿐 직접 바꾸지 않는다 — 변경은 [AppShellState] 를 쥔 배선 코드(UND-26)가
 * 콜백으로 처리한다 (compose-ui 규칙 1, 상태 끌어올리기).
 */
@Immutable
data class AppShellSelection(
    val repository: RepositoryPath?,
    val commit: CommitId?,
    val filePath: String?,
)

/**
 * 앱 전역 선택 상태 홀더 — 열린 저장소, 고른 커밋, 고른 파일 경로 세 값을 보유한다
 * (wave 3 결정 문서 §UND-12).
 *
 * 화면 간 연결(사이드바에서 고른 저장소가 그래프를 갱신하는 등)은 **콜백 파라미터로 열어 두고**
 * 실제 배선은 UND-26 이 한다. 이 홀더는 Gateway·UseCase 를 알지 못한다.
 *
 * 선택은 상위가 바뀌면 하위가 무효가 된다 — 저장소를 바꾸면 이전 저장소의 커밋 해시와 파일 경로가
 * 가리키는 대상이 사라지므로 함께 비운다. 조용히 남겨 두면 하위 화면이 존재하지 않는 커밋을 조회한다.
 */
@Stable
class AppShellState(
    repository: RepositoryPath? = null,
    commit: CommitId? = null,
    filePath: String? = null,
) {
    private var repositoryState by mutableStateOf(repository)
    private var commitState by mutableStateOf(commit)
    private var filePathState by mutableStateOf(filePath)

    val selection: AppShellSelection
        get() = AppShellSelection(
            repository = repositoryState,
            commit = commitState,
            filePath = filePathState,
        )

    /** 저장소를 연다. 다른 저장소로 바뀌면 커밋·파일 선택을 비운다. */
    fun selectRepository(path: RepositoryPath?) {
        if (path == repositoryState) return
        repositoryState = path
        commitState = null
        filePathState = null
    }

    /** 커밋을 고른다. 다른 커밋으로 바뀌면 파일 선택을 비운다. */
    fun selectCommit(commitId: CommitId?) {
        if (commitId == commitState) return
        commitState = commitId
        filePathState = null
    }

    /** 커밋 안의 파일을 고른다. */
    fun selectFile(path: String?) {
        filePathState = path
    }
}

/** 컴포지션 수명 동안 유지되는 셸 상태. 영속화 대상은 `Settings` 소유 티켓이 정한다. */
@Composable
fun rememberAppShellState(): AppShellState = remember { AppShellState() }
