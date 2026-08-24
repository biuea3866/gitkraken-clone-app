package dev.undine.presentation.palette

/** 화면 테스트가 팔레트의 각 부분을 집는 태그. 행 태그는 안정적인 명령 id 로 만든다. */
object PaletteTags {
    const val ROOT = "palette.root"
    const val QUERY = "palette.query"
    const val LIST = "palette.list"
    const val EMPTY = "palette.empty"

    fun row(id: CommandId): String = "palette.row.$id"

    fun reason(id: CommandId): String = "palette.reason.$id"
}
