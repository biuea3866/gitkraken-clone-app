package dev.undine.infrastructure.settings

import dev.undine.domain.AuthenticationMethod
import dev.undine.domain.ExternalTool
import dev.undine.domain.ExternalToolSettings
import dev.undine.domain.IdentityProfile

private const val KEY_PROFILE_NAME = "name"
private const val KEY_PROFILE_EMAIL = "email"
private const val KEY_SIGNING_KEY_ID = "signingKeyId"
private const val KEY_DEFAULT_AUTHENTICATION = "defaultAuthentication"
private const val KEY_EXPECTED_HOST = "expectedHost"
/** 두 키는 상위 객체를 적는 [encodeSettings] 도 쓴다 — 값을 두 곳에 적지 않는다. */
internal const val KEY_DIFF_TOOL = "diffTool"
internal const val KEY_MERGE_TOOL = "mergeTool"
private const val KEY_EXECUTABLE = "executable"
private const val KEY_ARGUMENTS = "arguments"

private const val NULL_JSON = "null"

/**
 * 인증 방식을 읽지 못했을 때의 값.
 *
 * HTTPS 로 두는 이유: SSH 로 잘못 읽으면 키를 등록하지 않은 사용자가 인증 단계에서 막힌다.
 * 방식을 저장만 하는 지금은 어느 쪽도 동작에 영향이 없지만, 소비 티켓이 붙었을 때 덜 해로운 쪽이다.
 */
private val DEFAULT_AUTHENTICATION = AuthenticationMethod.HTTPS

/**
 * UND-59 가 넓힌 두 필드(identity 프로필·외부 도구)의 JSON 표현.
 *
 * [SettingsCodec] 에서 분리한 이유는 파일당 함수 상한(detekt `TooManyFunctions`) 하나뿐이며,
 * 스키마 규칙은 그쪽과 같다 — 알 수 없는 키는 무시하고, 읽을 수 없는 값은 그 필드만 기본값이 된다.
 *
 * **자격증명은 쓰지 않는다** — 서명 키는 ID 만 담고 키 본문·패스프레이즈 키는 스키마에 없다.
 */
internal fun encodeIdentityProfile(profile: IdentityProfile): String =
    "{ \"$KEY_PROFILE_NAME\": ${jsonString(profile.name)}, " +
        "\"$KEY_PROFILE_EMAIL\": ${jsonString(profile.email)}, " +
        "\"$KEY_SIGNING_KEY_ID\": ${profile.signingKeyId?.let(::jsonString) ?: NULL_JSON}, " +
        "\"$KEY_DEFAULT_AUTHENTICATION\": \"${profile.defaultAuthentication.name}\", " +
        "\"$KEY_EXPECTED_HOST\": ${profile.expectedHost?.let(::jsonString) ?: NULL_JSON} }"

internal fun encodeExternalTool(tool: ExternalTool?): String {
    if (tool == null) return NULL_JSON
    val arguments = tool.arguments.joinToString(", ", transform = ::jsonString)
    return "{ \"$KEY_EXECUTABLE\": ${jsonString(tool.executable)}, \"$KEY_ARGUMENTS\": [$arguments] }"
}

/**
 * 이름·이메일이 없는 항목은 신원으로 쓸 수 없어 **그 항목만** 버린다 —
 * 목록 전체를 비우면 읽을 수 있었던 프로필까지 사라진다.
 */
internal fun readIdentityProfiles(value: Any?): List<IdentityProfile> =
    (value as? List<*>)?.mapNotNull(::readIdentityProfile) ?: DEFAULT_SETTINGS.identityProfiles

private fun readIdentityProfile(value: Any?): IdentityProfile? {
    val fields = value as? Map<*, *> ?: return null
    val name = fields[KEY_PROFILE_NAME] as? String
    val email = fields[KEY_PROFILE_EMAIL] as? String
    return if (name == null || email == null) {
        null
    } else {
        IdentityProfile(
            name = name,
            email = email,
            signingKeyId = fields[KEY_SIGNING_KEY_ID] as? String,
            defaultAuthentication = readAuthenticationMethod(fields[KEY_DEFAULT_AUTHENTICATION]),
            // 이 키가 없는 기존 프로필은 호스트 경고 대상이 아니다 — 없음과 빈 문자열을 뭉개지 않는다.
            expectedHost = fields[KEY_EXPECTED_HOST] as? String,
        )
    }
}

private fun readAuthenticationMethod(value: Any?): AuthenticationMethod =
    AuthenticationMethod.entries.firstOrNull { it.name == value } ?: DEFAULT_AUTHENTICATION

internal fun readExternalTools(value: Any?): ExternalToolSettings {
    val fields = value as? Map<*, *> ?: return DEFAULT_SETTINGS.externalTools
    return ExternalToolSettings(
        diffTool = readExternalTool(fields[KEY_DIFF_TOOL]),
        mergeTool = readExternalTool(fields[KEY_MERGE_TOOL]),
    )
}

/** 실행 파일이 없는 도구 설정은 실행할 수 없으므로 설정되지 않은 것으로 읽는다. */
private fun readExternalTool(value: Any?): ExternalTool? {
    val fields = value as? Map<*, *> ?: return null
    return (fields[KEY_EXECUTABLE] as? String)?.let { executable ->
        ExternalTool(
            executable = executable,
            arguments = (fields[KEY_ARGUMENTS] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        )
    }
}
