package com.example.minimalapk.pose

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubMirrorTest {
    private val raw = "https://raw.githubusercontent.com/Kreedb/test-ai-apk/" +
        "e9f51fe286a7ea8863d40113e51dc5a4b9d49e88/models/yolo11n_pose.tflite"
    private val githubRaw = "https://github.com/Kreedb/test-ai-apk/raw/main/models/yolo11n_pose.tflite"
    private val release = "https://github.com/Kreedb/test-ai-apk/releases/download/v1/yolo11n_pose.tflite"
    private val preset = GitHubMirrorPreference.PRESET_PREFIX

    @Test
    fun directModeKeepsEveryUrl() {
        listOf(raw, githubRaw, release, "https://example.com/model.tflite").forEach { url ->
            assertEquals(url, rewriteGitHubUrl(url, rawPrefix = null))
        }
    }

    @Test
    fun presetPrefixRewritesSupportedGitHubUrls() {
        assertEquals(preset + raw, rewriteGitHubUrl(raw, preset))
        assertEquals(preset + githubRaw, rewriteGitHubUrl(githubRaw, preset))
        assertEquals(preset + release, rewriteGitHubUrl(release, preset))
    }

    @Test
    fun customPrefixGetsExactlyOneTrailingSlash() {
        val custom = "https://mirror.example/gh"
        assertEquals("$custom/$raw", rewriteGitHubUrl(raw, custom))

        val customWithSlash = "https://mirror.example/gh/"
        assertEquals(customWithSlash + raw, rewriteGitHubUrl(raw, customWithSlash))
    }

    @Test
    fun leavesNonGitHubAndUnsupportedGitHubPathsAlone() {
        listOf(
            "https://storage.googleapis.com/download.tensorflow.org/models/x.tflite",
            "https://github.com/Kreedb/test-ai-apk",
            "https://github.com/Kreedb/test-ai-apk/blob/main/models/x.tflite",
            "https://github.com/Kreedb/test-ai-apk/archive/refs/heads/main.zip",
        ).forEach { url ->
            assertEquals(url, rewriteGitHubUrl(url, preset))
        }
    }

    @Test
    fun rejectsSpoofedHostsAndEmbeddedGithubUrls() {
        listOf(
            "https://raw.githubusercontent.com.evil.example/Kreedb/test-ai-apk/main/x.tflite",
            "https://evil.example/raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite",
            "https://raw.githubusercontent.com@evil.example/Kreedb/test-ai-apk/main/x.tflite",
            "https://evil.example/?next=https://raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite",
        ).forEach { url ->
            assertEquals(url, rewriteGitHubUrl(url, preset))
        }
    }

    @Test
    fun rejectsCredentialsWhitespaceAndNonHttpsSources() {
        listOf(
            "http://raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite",
            "https://user:pass@raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite",
            "https://raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite extra",
            "https://raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite?token=1",
            "https://raw.githubusercontent.com/Kreedb/test-ai-apk/main/x.tflite#fragment",
            "https://raw.githubusercontent.com:8443/Kreedb/test-ai-apk/main/x.tflite",
        ).forEach { url ->
            assertEquals(url, rewriteGitHubUrl(url, preset))
        }
    }

    @Test
    fun doesNotPrefixAnAlreadyMirroredUrlTwice() {
        val mirrored = preset + raw
        assertEquals(mirrored, rewriteGitHubUrl(mirrored, preset))

        val custom = "https://mirror.example/gh/"
        val customMirrored = custom + raw
        assertEquals(customMirrored, rewriteGitHubUrl(customMirrored, "https://mirror.example/gh"))
    }

    @Test
    fun rejectsInvalidPrefixes() {
        listOf(
            "http://gh-proxy.org/",
            "gh-proxy.org/",
            "https://user:pass@gh-proxy.org/",
            "https://gh-proxy.org/path with space/",
            "https://gh-proxy.org/?token=1",
            "https://gh-proxy.org/#fragment",
        ).forEach { prefix ->
            assertEquals(raw, rewriteGitHubUrl(raw, prefix))
        }
    }

    @Test
    fun customPreferenceFallsBackToDirectWhenThePrefixIsInvalid() {
        val preference = GitHubMirrorPreference(
            mode = GitHubMirrorMode.CUSTOM,
            customPrefix = "http://insecure.example/",
        )

        assertEquals(null, preference.prefix)
        assertEquals(true, preference.isDirect)
    }
}
