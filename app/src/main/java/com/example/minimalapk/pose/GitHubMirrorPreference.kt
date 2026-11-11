package com.example.minimalapk.pose

import android.content.Context
import androidx.core.content.edit
import java.net.URI

/** How GitHub-hosted model files are fetched. The default keeps the previous direct behaviour. */
enum class GitHubMirrorMode(val label: String, val description: String) {
    DIRECT("官方直连", "直接访问 GitHub，默认"),
    GH_PROXY("gh-proxy.org 镜像", "预置前缀 https://gh-proxy.org/"),
    CUSTOM("自定义 HTTPS 前缀", "例如自建反代或公司镜像"),
}

/**
 * The persisted mirror choice. [prefix] is null for a direct connection and also when a custom
 * prefix is invalid, so downloads always fall back to the original URL instead of failing.
 */
data class GitHubMirrorPreference(
    val mode: GitHubMirrorMode = GitHubMirrorMode.DIRECT,
    val customPrefix: String = "",
) {
    val prefix: String?
        get() = when (mode) {
            GitHubMirrorMode.DIRECT -> null
            GitHubMirrorMode.GH_PROXY -> PRESET_PREFIX
            GitHubMirrorMode.CUSTOM -> normalizeMirrorPrefix(customPrefix)
        }

    val isDirect: Boolean get() = prefix == null

    val summary: String
        get() = when (mode) {
            GitHubMirrorMode.DIRECT -> mode.label
            GitHubMirrorMode.GH_PROXY -> GitHubMirrorMode.GH_PROXY.label
            GitHubMirrorMode.CUSTOM -> prefix?.removeSuffix("/") ?: "官方直连（自定义前缀无效）"
        }

    companion object {
        const val PRESET_PREFIX = "https://gh-proxy.org/"

        private const val PREFS = "app_settings"
        private const val KEY_MODE = "github_mirror_mode"
        private const val KEY_CUSTOM_PREFIX = "github_mirror_custom_prefix"

        fun load(context: Context): GitHubMirrorPreference {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, null)
                ?.let { name -> GitHubMirrorMode.entries.firstOrNull { it.name == name } }
                ?: GitHubMirrorMode.DIRECT
            return GitHubMirrorPreference(
                mode = mode,
                customPrefix = prefs.getString(KEY_CUSTOM_PREFIX, "").orEmpty(),
            )
        }

        fun save(context: Context, preference: GitHubMirrorPreference) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_MODE, preference.mode.name)
                putString(KEY_CUSTOM_PREFIX, preference.customPrefix)
            }
        }
    }
}

/**
 * Returns [sourceUrl] unchanged unless it is a supported GitHub raw/release URL and [rawPrefix] is
 * a valid HTTPS prefix. The check compares the whole host, not substrings, so look-alike domains
 * and embedded URLs cannot be redirected.
 */
internal fun rewriteGitHubUrl(sourceUrl: String, rawPrefix: String?): String {
    val prefix = rawPrefix?.let(::normalizeMirrorPrefix) ?: return sourceUrl
    if (sourceUrl.any(Char::isWhitespace)) return sourceUrl
    // A URL that already points through this prefix would otherwise be wrapped a second time.
    if (sourceUrl.startsWith(prefix, ignoreCase = true)) return sourceUrl
    val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return sourceUrl
    if (!uri.scheme.equals("https", ignoreCase = true)) return sourceUrl
    if (uri.rawUserInfo != null) return sourceUrl
    if (uri.rawQuery != null || uri.rawFragment != null) return sourceUrl
    if (uri.port != -1 && uri.port != 443) return sourceUrl
    if (!isSupportedGitHubUri(uri)) return sourceUrl
    return prefix + sourceUrl
}

/**
 * Normalizes a mirror prefix to exactly one trailing slash. Only an absolute HTTPS prefix without
 * credentials, whitespace, query or fragment is accepted; everything else yields null.
 */
internal fun normalizeMirrorPrefix(rawPrefix: String): String? {
    val prefix = rawPrefix.trim()
    if (prefix.isEmpty() || prefix.any(Char::isWhitespace)) return null
    val uri = runCatching { URI(prefix) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    if (uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.host.isNullOrEmpty()) return null
    if (uri.port != -1 && uri.port != 443) return null
    return if (prefix.endsWith("/")) prefix else "$prefix/"
}

private fun isSupportedGitHubUri(uri: URI): Boolean {
    val host = uri.host ?: return false
    val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when {
        host.equals("raw.githubusercontent.com", ignoreCase = true) -> segments.size >= 4
        host.equals("github.com", ignoreCase = true) -> when {
            segments.size >= 5 && segments[2] == "raw" -> true
            segments.size >= 6 && segments[2] == "releases" && segments[3] == "download" -> true
            else -> false
        }
        else -> false
    }
}
