package com.example.minimalapk.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class AssetInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun downloadsWholeSetWithoutByteArrayAndCleansTemporaryDirectories() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val transport = FakeTransport { request ->
            assertEquals(0L, request.rangeStart)
            FakeResponse(ASSET_HTTP_OK, payload, etag = "\"v1\"")
        }
        val set = setOfFiles(asset("model.bin", payload))
        val installer = installer(root, transport)

        installer.install(set)

        assertTrue(installer.isReady(set))
        assertArrayEquals(payload, File(installer.installedDirectory(set), "model.bin").readBytes())
        assertFalse(File(root, ".test-set.partial").exists())
        assertFalse(File(root, ".test-set.staging").exists())
        assertFalse(File(root, ".test-set.backup").exists())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun resumesPartialWithRangeAndStoredEtag() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(40_000) { (it * 7 % 256).toByte() }
        val half = payload.size / 2
        seedPartial(root, payload.copyOf(half), "\"v1\"")
        val transport = FakeTransport { request ->
            assertEquals(half.toLong(), request.rangeStart)
            assertEquals("\"v1\"", request.ifRangeEtag)
            FakeResponse(
                ASSET_HTTP_PARTIAL,
                payload.copyOfRange(half, payload.size),
                etag = "\"v1\"",
                contentLength = (payload.size - half).toLong(),
                contentRange = "bytes $half-${payload.lastIndex}/${payload.size}",
            )
        }
        val set = setOfFiles(asset("model.bin", payload))
        File(root, ".test-set.partial/.fingerprint").writeText(set.fingerprint)
        val installer = installer(root, transport)

        installer.install(set)

        assertArrayEquals(payload, File(installer.installedDirectory(set), "model.bin").readBytes())
        assertEquals(1, transport.requests.size)
        assertFalse(File(root, ".test-set.partial").exists())
    }

    @Test
    fun fullResponseAfterRangeRestartsFromZero() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(30_000) { (it % 97).toByte() }
        val half = payload.size / 2
        seedPartial(root, payload.copyOf(half), "\"old\"")
        val transport = FakeTransport { request ->
            assertEquals(half.toLong(), request.rangeStart)
            FakeResponse(ASSET_HTTP_OK, payload, etag = "\"new\"")
        }
        val set = setOfFiles(asset("model.bin", payload))
        File(root, ".test-set.partial/.fingerprint").writeText(set.fingerprint)
        val installer = installer(root, transport)

        installer.install(set)

        assertArrayEquals(payload, File(installer.installedDirectory(set), "model.bin").readBytes())
        assertEquals(listOf(half.toLong()), transport.requests.map { it.rangeStart })
    }

    @Test
    fun handlesRangeNotSatisfiableByRestarting() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(20_000) { (it % 31).toByte() }
        val half = payload.size / 2
        seedPartial(root, payload.copyOf(half), "\"v1\"")
        val transport = FakeTransport { request ->
            if (request.rangeStart > 0L) {
                FakeResponse(ASSET_HTTP_RANGE_NOT_SATISFIABLE, ByteArray(0), contentLength = 0)
            } else {
                FakeResponse(ASSET_HTTP_OK, payload, etag = "\"v1\"")
            }
        }
        val set = setOfFiles(asset("model.bin", payload))
        File(root, ".test-set.partial/.fingerprint").writeText(set.fingerprint)
        val installer = installer(root, transport)

        installer.install(set)

        assertArrayEquals(payload, File(installer.installedDirectory(set), "model.bin").readBytes())
        assertEquals(listOf(half.toLong(), 0L), transport.requests.map { it.rangeStart })
    }

    @Test
    fun changedEtagOnPartialResponseRestartsFromZero() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(25_000) { (it % 113).toByte() }
        val half = payload.size / 2
        seedPartial(root, payload.copyOf(half), "\"old\"")
        val transport = FakeTransport { request ->
            if (request.rangeStart > 0L) {
                FakeResponse(
                    ASSET_HTTP_PARTIAL,
                    payload.copyOfRange(half, payload.size),
                    etag = "\"new\"",
                    contentLength = (payload.size - half).toLong(),
                    contentRange = "bytes $half-${payload.lastIndex}/${payload.size}",
                )
            } else {
                FakeResponse(ASSET_HTTP_OK, payload, etag = "\"new\"")
            }
        }
        val set = setOfFiles(asset("model.bin", payload))
        File(root, ".test-set.partial/.fingerprint").writeText(set.fingerprint)
        val installer = installer(root, transport)

        installer.install(set)

        assertArrayEquals(payload, File(installer.installedDirectory(set), "model.bin").readBytes())
        assertEquals(listOf(half.toLong(), 0L), transport.requests.map { it.rangeStart })
    }

    @Test
    fun truncatedBodyFailsAndPreservesPreviouslyInstalledSet() {
        val root = folder.newFolder("assets")
        val oldPayload = ByteArray(100) { 1 }
        val oldSet = setOfFiles(asset("model.bin", oldPayload))
        File(root, "test-set").mkdirs()
        File(root, "test-set/model.bin").writeBytes(oldPayload)
        File(root, "test-set/$ASSET_RECEIPT").writeText("1\n${oldSet.fingerprint}\n")
        val nextPayload = ByteArray(5_000) { 2 }
        val nextSet = setOfFiles(asset("model.bin", nextPayload))
        val transport = FakeTransport {
            FakeResponse(
                ASSET_HTTP_OK,
                nextPayload.copyOf(1_000),
                etag = "\"v1\"",
                contentLength = nextPayload.size.toLong(),
            )
        }
        val installer = installer(root, transport)

        assertThrows(AssetInstallException::class.java) { installer.install(nextSet) }

        assertArrayEquals(oldPayload, File(installer.installedDirectory(oldSet), "model.bin").readBytes())
        assertTrue(installer.isReady(oldSet))
        assertFalse(File(root, ".test-set.staging").exists())
        assertEquals(1_000L, File(root, ".test-set.partial/model.bin.part").length())
    }

    @Test
    fun htmlErrorPageIsRejectedWithoutTouchingInstalledSet() {
        val root = folder.newFolder("assets")
        val oldPayload = ByteArray(64) { 9 }
        val oldSet = setOfFiles(asset("model.bin", oldPayload))
        File(root, "test-set").mkdirs()
        File(root, "test-set/model.bin").writeBytes(oldPayload)
        File(root, "test-set/$ASSET_RECEIPT").writeText("1\n${oldSet.fingerprint}\n")
        val nextPayload = ByteArray(64) { 8 }
        val nextSet = setOfFiles(asset("model.bin", nextPayload))
        val html = "<html><body>rate limited</body></html>".toByteArray()
        val transport = FakeTransport {
            FakeResponse(
                ASSET_HTTP_OK,
                html,
                contentType = "text/html; charset=utf-8",
                contentLength = nextPayload.size.toLong(),
            )
        }
        val installer = installer(root, transport)

        assertThrows(AssetInstallException::class.java) { installer.install(nextSet) }

        assertArrayEquals(oldPayload, File(installer.installedDirectory(oldSet), "model.bin").readBytes())
        assertFalse(File(root, ".test-set.staging").exists())
    }

    @Test
    fun declaredContentLengthMismatchFailsBeforeWriting() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(100) { 4 }
        val set = setOfFiles(asset("model.bin", payload))
        val transport = FakeTransport {
            FakeResponse(ASSET_HTTP_OK, payload.copyOf(50), contentLength = 50)
        }
        val installer = installer(root, transport)

        assertThrows(AssetInstallException::class.java) { installer.install(set) }

        assertFalse(installer.installedDirectory(set).exists())
        assertFalse(File(root, ".test-set.staging").exists())
    }

    @Test
    fun hashMismatchDeletesPartialAndKeepsPreviousSet() {
        val root = folder.newFolder("assets")
        val oldPayload = ByteArray(40) { 5 }
        val oldSet = setOfFiles(asset("model.bin", oldPayload))
        File(root, "test-set").mkdirs()
        File(root, "test-set/model.bin").writeBytes(oldPayload)
        File(root, "test-set/$ASSET_RECEIPT").writeText("1\n${oldSet.fingerprint}\n")
        val payload = ByteArray(120) { 6 }
        val badSet = setOfFiles(
            asset("model.bin", payload).copy(sha256 = "0".repeat(64)),
        )
        val transport = FakeTransport { FakeResponse(ASSET_HTTP_OK, payload, etag = "\"v1\"") }
        val installer = installer(root, transport)

        assertThrows(AssetInstallException::class.java) { installer.install(badSet) }

        assertArrayEquals(oldPayload, File(installer.installedDirectory(oldSet), "model.bin").readBytes())
        assertTrue(installer.isReady(oldSet))
        assertFalse(File(root, ".test-set.partial/model.bin.part").exists())
        assertFalse(File(root, ".test-set.staging").exists())
    }

    @Test
    fun readinessIsFalseForMissingOrWrongSizedFile() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(32) { 7 }
        val set = setOfFiles(asset("model.bin", payload))
        val transport = FakeTransport { FakeResponse(ASSET_HTTP_OK, payload) }
        val installer = installer(root, transport)
        installer.install(set)
        assertTrue(installer.isReady(set))

        val installed = File(installer.installedDirectory(set), "model.bin")
        installed.writeBytes(payload.copyOf(payload.size - 1))
        assertFalse(installer.isReady(set))

        installed.delete()
        assertFalse(installer.isReady(set))
    }

    @Test
    fun deleteRemovesInstalledAndPrivateDirectories() {
        val root = folder.newFolder("assets")
        val payload = ByteArray(32) { 8 }
        val set = setOfFiles(asset("model.bin", payload))
        val installer = installer(root, FakeTransport { FakeResponse(ASSET_HTTP_OK, payload) })
        installer.install(set)
        File(root, ".test-set.partial").mkdirs()
        File(root, ".test-set.staging").mkdirs()

        installer.delete(set.id)

        assertFalse(installer.installedDirectory(set).exists())
        assertFalse(File(root, ".test-set.partial").exists())
        assertFalse(File(root, ".test-set.staging").exists())
    }

    private fun installer(root: File, transport: AssetTransport) =
        AssetInstaller(root, transport, freeSpace = { Long.MAX_VALUE }, reserveBytes = 0L)

    private fun setOfFiles(vararg files: AssetFile): AssetSet = AssetSet(
        id = "test-set",
        name = "Test",
        group = "Vision",
        backend = "cpu",
        licenseName = "Apache-2.0",
        licenseUrl = "https://example.com/LICENSE",
        upstreamUrl = "https://example.com/upstream",
        modelCardUrl = "https://example.com/card",
        files = files.toList(),
    )

    private fun asset(name: String, bytes: ByteArray): AssetFile = AssetFile(
        name = name,
        url = "https://example.com/$name",
        bytes = bytes.size.toLong(),
        sha256 = bytes.sha256(),
    )

    private fun seedPartial(root: File, bytes: ByteArray, etag: String) {
        val directory = File(root, ".test-set.partial").apply { mkdirs() }
        File(directory, "model.bin.part").writeBytes(bytes)
        File(directory, "model.bin.etag").writeText(etag)
    }

    private data class FakeResponse(
        val code: Int,
        val body: ByteArray,
        val etag: String? = null,
        val contentType: String? = "application/octet-stream",
        val contentLength: Long = body.size.toLong(),
        val contentRange: String? = null,
    )

    private class FakeTransport(
        private val handler: (AssetHttpRequest) -> FakeResponse,
    ) : AssetTransport {
        val requests = mutableListOf<AssetHttpRequest>()

        override fun open(request: AssetHttpRequest): AssetHttpResponse {
            requests += request
            val response = handler(request)
            return AssetHttpResponse(
                code = response.code,
                contentLength = response.contentLength,
                etag = response.etag,
                contentType = response.contentType,
                body = ByteArrayInputStream(response.body),
                contentRange = response.contentRange,
            )
        }
    }
}
