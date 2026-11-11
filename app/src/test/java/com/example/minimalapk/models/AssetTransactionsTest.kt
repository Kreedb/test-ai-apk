package com.example.minimalapk.models

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class AssetTransactionsTest {
    @get:Rule val folder = TemporaryFolder()
    private val first = ByteArray(17) { 1 }
    private val second = ByteArray(19) { 2 }

    @Test
    fun sameSizedRevisionRequiresItsOwnReceiptAndAllHashes() {
        val root = folder.newFolder()
        val old = set("a.bin" to first)
        install(root, old, mapOf("a.bin" to first))
        val changed = first.copyOf().apply { this[0] = 9 }
        val next = set("a.bin" to changed)
        assertTrue(old.isReadyIn(root))
        assertFalse(next.isReadyIn(root))
        assertNull(AssetResourceStore.at(root).installedDirectory(next))
        val wrongSourceRevision = old.copy(files = old.files.map { it.copy(url = "https://example.com/new/a.bin") })
        assertFalse(wrongSourceRevision.isReadyIn(root))
        install(root, next, mapOf("a.bin" to changed))
        assertTrue(next.isReadyIn(root))
        assertFalse(old.isReadyIn(root))
        assertArrayEquals(changed, File(root, "test-set/a.bin").readBytes())
    }

    @Test
    fun oldReceiptlessInstallationIsVerifiedWithoutDownloadingOrMovingWeights() {
        val root = folder.newFolder()
        val set = set("a.bin" to first, "b.bin" to second)
        val target = File(root, set.id).apply { mkdir() }
        File(target, "a.bin").writeBytes(first)
        File(target, "b.bin").writeBytes(second)
        assertFalse(set.isReadyIn(root))
        installer(root) { error("No download is needed for verified old bytes") }.install(set)
        assertTrue(set.isReadyIn(root))
        assertArrayEquals(first, File(target, "a.bin").readBytes())
        File(target, "b.bin").delete()
        assertFalse(set.isReadyIn(root))
    }

    @Test
    fun cancelledReadAndCancelledLastProgressNeverReplaceInstalledSet() {
        for (cancelAtEnd in listOf(false, true)) {
            val root = folder.newFolder()
            val old = set("a.bin" to first)
            install(root, old, mapOf("a.bin" to first))
            val next = set("a.bin" to second)
            val transfer = AssetTransfer()
            var closed = false
            val payload = object : ByteArrayInputStream(second) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val result = super.read(bytes, offset, length)
                    if (!cancelAtEnd) transfer.cancel()
                    return result
                }
                override fun close() { closed = true; super.close() }
            }
            val runner = installer(root) { response(second, body = payload) }
            assertThrows(CancellationException::class.java) {
                runner.install(next, transfer) { if (cancelAtEnd && it.stage == "完成安装") transfer.cancel() }
            }
            assertTrue(closed)
            assertTrue(old.isReadyIn(root))
            assertFalse(next.isReadyIn(root))
            assertArrayEquals(first, File(root, "test-set/a.bin").readBytes())
            assertFalse(File(root, ".test-set.staging").exists())
        }
    }

    @Test
    fun completedComponentsSurviveLaterFailureAndAreReusedOnRetry() {
        val root = folder.newFolder()
        val set = set("a.bin" to first, "b.bin" to second)
        var fail = true
        val requested = mutableListOf<String>()
        val runner = installer(root) { request ->
            val name = request.url.substringAfterLast('/')
            requested += name
            if (name == "b.bin" && fail) throw IOException("connection interrupted")
            response(if (name == "a.bin") first else second)
        }
        assertThrows(IOException::class.java) { runner.install(set) }
        assertArrayEquals(first, File(root, ".test-set.partial/a.bin.part").readBytes())
        assertFalse(set.isReadyIn(root))
        fail = false
        runner.install(set)
        assertEquals(listOf("a.bin", "b.bin", "b.bin"), requested)
        assertTrue(set.isReadyIn(root))
    }

    @Test
    fun insufficientSpaceFailsBeforeAnyNetworkOrSourceOpenAndKeepsOldSet() {
        val root = folder.newFolder()
        val old = set("a.bin" to first)
        install(root, old, mapOf("a.bin" to first))
        val next = set("a.bin" to second)
        val runner = AssetInstaller(root, transport { error("must not open") }, freeSpace = { 20L }, reserveBytes = 10L)
        assertThrows(IllegalArgumentException::class.java) { runner.install(next) }
        assertThrows(IllegalArgumentException::class.java) {
            runner.importFiles(next, listOf(AssetImportSource("a.bin", null) { error("must not open SAF") }))
        }
        assertTrue(old.isReadyIn(root))
    }

    @Test
    fun spaceLostDuringCopyStopsBeforeTheNextWriteAndPreservesOldSet() {
        val root = folder.newFolder()
        val old = set("a.bin" to first)
        install(root, old, mapOf("a.bin" to first))
        val next = set("a.bin" to second)
        var free = 100L
        val runner = AssetInstaller(root, transport { response(second) }, freeSpace = { free }, reserveBytes = 10L, bufferSize = 4)
        assertThrows(IllegalArgumentException::class.java) {
            runner.install(next) { if (it.fileBytes >= 4) free = 10L }
        }
        assertEquals(4L, File(root, ".test-set.partial/a.bin.part").length())
        assertTrue(old.isReadyIn(root))
    }

    @Test
    fun rangeValidationRestartsBeforeAppendingWrongOffsetTotalOrEnd() {
        for (range in listOf(null, "bytes 0-18/19", "bytes 4-17/19", "bytes 4-18/20", "bytes 4-18/*", "garbage")) {
            val root = folder.newFolder()
            val set = set("a.bin" to second)
            seedPartial(root, set, second.copyOf(4))
            val offsets = mutableListOf<Long>()
            val runner = installer(root) { request ->
                offsets += request.rangeStart
                if (request.rangeStart > 0) response(second.copyOfRange(4, second.size), 206, range = range)
                else response(second)
            }
            runner.install(set)
            assertEquals(listOf(4L, 0L), offsets)
            assertArrayEquals(second, File(root, "test-set/a.bin").readBytes())
        }
        assertTrue(validAssetContentRange("bytes 2147483650-4294967295/4294967296", 2147483650L, 4294967296L))
        assertFalse(validAssetContentRange("bytes 4-18/9999999999999999999999", 4L, 19L))
    }

    @Test
    fun weakEtagAndChangedCatalogRevisionRestartInsteadOfMixingPartialBytes() {
        for (weak in listOf(false, true)) {
            val root = folder.newFolder()
            val set = set("a.bin" to second)
            seedPartial(root, set, second.copyOf(4))
            if (weak) File(root, ".test-set.partial/a.bin.etag").writeText("W/\"old\"")
            else File(root, ".test-set.partial/.fingerprint").writeText("old catalog")
            installer(root) { request ->
                assertEquals(0L, request.rangeStart)
                assertNull(request.ifRangeEtag)
                response(second)
            }.install(set)
            assertTrue(set.isReadyIn(root))
        }
    }

    @Test
    fun cancelledPartialResumesWithTheExactContentRangeAndValidator() {
        val root = folder.newFolder()
        val set = set("a.bin" to second)
        val transfer = AssetTransfer()
        val firstInstaller = AssetInstaller(root, transport { response(second) }, reserveBytes = 0, bufferSize = 4)
        assertThrows(CancellationException::class.java) {
            firstInstaller.install(set, transfer) { if (it.fileBytes == 4L) transfer.cancel() }
        }
        installer(root) { request ->
            assertEquals(4L, request.rangeStart)
            assertEquals("\"v1\"", request.ifRangeEtag)
            response(second.copyOfRange(4, second.size), 206, range = "bytes 4-18/19")
        }.install(set)
        assertTrue(set.isReadyIn(root))
    }

    @Test
    fun malformedPartialCannotLoopOrInstallEvenIfBodyWouldHashCorrectly() {
        val root = folder.newFolder()
        val set = set("a.bin" to second)
        var opens = 0
        val runner = installer(root) { opens++; response(second, 206) }
        assertThrows(AssetInstallException::class.java) { runner.install(set) }
        assertEquals(2, opens)
        assertFalse(set.isReadyIn(root))
    }

    @Test
    fun overlongAndHashMismatchedStreamsAreNeverInstalled() {
        for (bytes in listOf(second + 3.toByte(), second.copyOf().apply { this[0] = 8 })) {
            val root = folder.newFolder()
            val set = set("a.bin" to second)
            val runner = installer(root) { response(bytes, length = -1L) }
            assertThrows(AssetInstallException::class.java) { runner.install(set) }
            assertFalse(set.isReadyIn(root))
            assertFalse(File(root, ".test-set.partial/a.bin.part").exists())
        }
    }

    @Test
    fun authenticatedResourceFailureHasAnImportActionReasonAndIsNotCorruption() {
        for (status in listOf(401, 403)) {
            val root = folder.newFolder()
            val old = set("a.bin" to first)
            install(root, old, mapOf("a.bin" to first))
            val error = assertThrows(AssetAccessRequiredException::class.java) {
                installer(root) { response(byteArrayOf(), status) }.install(set("a.bin" to second))
            }
            assertEquals(status, error.statusCode)
            assertTrue(error.message!!.contains("授权"))
            assertTrue(error.message!!.contains("文件选择器"))
            assertTrue(old.isReadyIn(root))
        }
    }

    @Test
    fun safSelectionRejectsMissingExtraDuplicateAndWrongSizedNamesBeforeOpening() {
        val set = set("a.bin" to first, "b.bin" to second)
        fun source(name: String, size: Long? = null) = AssetImportSource(name, size) { error("must not open") }
        val selections = listOf(
            listOf(source("a.bin")),
            listOf(source("a.bin"), source("a.bin")),
            listOf(source("a.bin"), source("renamed.bin")),
            listOf(source("a.bin", 1), source("b.bin")),
            listOf(source("../a.bin"), source("b.bin")),
        )
        selections.forEach { selected ->
            assertThrows(IllegalArgumentException::class.java) {
                installer(folder.newFolder()) { error("must not download") }.importFiles(set, selected)
            }
        }
    }

    @Test
    fun safImportsAllFilesByDisplayNameWithUnknownLengthAndClosesStreams() {
        val root = folder.newFolder()
        val set = set("a.bin" to first, "b.bin" to second)
        var closed = 0
        val sources = listOf("b.bin" to second, "a.bin" to first).map { (name, bytes) ->
            AssetImportSource(name, null) {
                object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
            }
        }
        installer(root) { error("SAF is offline") }.importFiles(set, sources)
        assertTrue(set.isReadyIn(root))
        assertEquals(2, closed)
        assertArrayEquals(first, File(root, "test-set/a.bin").readBytes())
    }

    @Test
    fun revokedSafAccessAndTruncatedOrOverlongFilesRetainPreviousSet() {
        val sources: List<() -> InputStream> = listOf(
            { throw SecurityException("provider revoked grant") },
            { ByteArrayInputStream(second.copyOf(2)) },
            { ByteArrayInputStream(second + 3.toByte()) },
        )
        sources.forEachIndexed { index, open ->
            val root = folder.newFolder()
            val old = set("a.bin" to first)
            install(root, old, mapOf("a.bin" to first))
            val runner = installer(root) { error("SAF must not use HTTP") }
            val error = assertThrows(AssetInstallException::class.java) {
                runner.importFiles(set("a.bin" to second), listOf(AssetImportSource("a.bin", null, open)))
            }
            if (index == 0) assertTrue(error is AssetImportAccessException)
            assertTrue(old.isReadyIn(root))
        }
    }

    @Test
    fun swapFailureRestoresOldSetAndKeepsCompleteComponentsForRetry() {
        val root = folder.newFolder()
        val old = set("a.bin" to first)
        install(root, old, mapOf("a.bin" to first))
        val next = set("a.bin" to second)
        val runner = AssetInstaller(root, transport { response(second) }, reserveBytes = 0,
            rename = { from, to -> if (from.name.endsWith(".staging")) false else from.renameTo(to) })
        assertThrows(AssetInstallException::class.java) { runner.install(next) }
        assertTrue(old.isReadyIn(root))
        assertArrayEquals(second, File(root, ".test-set.partial/a.bin.part").readBytes())
        installer(root) { error("verified component must be reused") }.install(next)
        assertTrue(next.isReadyIn(root))
    }

    @Test
    fun statusAndAcquireRecoverAnInterruptedRenameWithoutAnotherDownload() {
        val root = folder.newFolder()
        val set = set("a.bin" to first)
        install(root, set, mapOf("a.bin" to first))
        assertTrue(File(root, "test-set").renameTo(File(root, ".test-set.backup")))
        val store = AssetResourceStore.at(root)
        assertEquals(File(root, "test-set"), store.installedDirectory(set))
        store.acquire(set).use { assertArrayEquals(first, File(it.directory, "a.bin").readBytes()) }
        assertFalse(File(root, ".test-set.backup").exists())
        // A crash after commit leaves both directories: target wins, old backup is cleaned.
        File(root, ".test-set.backup").mkdir()
        File(root, ".test-set.backup/old.bin").writeBytes(second)
        assertNotNull(store.installedDirectory(set))
        assertFalse(File(root, ".test-set.backup").exists())
    }

    @Test
    fun rollbackFailureKeepsBackupAndNextStatusRestoresIt() {
        val root = folder.newFolder()
        val old = set("a.bin" to first)
        install(root, old, mapOf("a.bin" to first))
        val next = set("a.bin" to second)
        val runner = AssetInstaller(root, transport { response(second) }, reserveBytes = 0,
            rename = { from, to ->
                if (to.name == "test-set" && (from.name.endsWith(".staging") || from.name.endsWith(".backup"))) false
                else from.renameTo(to)
            })
        assertThrows(AssetInstallException::class.java) { runner.install(next) }
        assertArrayEquals(first, File(root, ".test-set.backup/a.bin").readBytes())
        assertNotNull(AssetResourceStore.at(root).installedDirectory(old))
        assertTrue(old.isReadyIn(root))
        assertFalse(next.isReadyIn(root))
    }

    @Test
    fun processDeathDuringStagingReusesEveryPreviouslyVerifiedComponent() {
        val root = folder.newFolder()
        val set = set("a.bin" to first, "b.bin" to second)
        val partial = File(root, ".test-set.partial").apply { mkdir() }
        File(partial, ".fingerprint").writeText(set.fingerprint)
        File(partial, "b.bin.part").writeBytes(second)
        val staging = File(root, ".test-set.staging").apply { mkdir() }
        File(staging, "a.bin").writeBytes(first)
        installer(root) { error("completed components are verified locally") }.install(set)
        assertTrue(set.isReadyIn(root))
        assertFalse(staging.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun cancellationDuringSafCopyCannotPromotePartialSet() {
        val root = folder.newFolder()
        val set = set("a.bin" to first, "b.bin" to second)
        val transfer = AssetTransfer()
        var closed = false
        val sources = listOf(
            AssetImportSource("a.bin", first.size.toLong()) { ByteArrayInputStream(first) },
            AssetImportSource("b.bin", null) {
                object : ByteArrayInputStream(second) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        transfer.cancel()
                        return super.read(bytes, offset, length)
                    }
                    override fun close() { closed = true; super.close() }
                }
            },
        )
        assertThrows(CancellationException::class.java) {
            installer(root) { error("offline only") }.importFiles(set, sources, transfer)
        }
        assertTrue(closed)
        assertFalse(set.isReadyIn(root))
        assertArrayEquals(first, File(root, ".test-set.partial/a.bin.part").readBytes())
    }

    @Test
    fun interruptedDeletionIsCompletedRatherThanRestoringDeletedModels() {
        val root = folder.newFolder()
        val set = set("a.bin" to first)
        install(root, set, mapOf("a.bin" to first))
        File(root, "test-set").renameTo(File(root, ".test-set.deleted"))
        File(root, ".test-set.partial").mkdir()
        assertNull(AssetResourceStore.at(root).installedDirectory(set))
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun longSetTotalOverflowIsRejectedBeforeTouchingStorage() {
        val root = File(folder.root, "never-created")
        val set = set("a.bin" to first, "b.bin" to second).let { original ->
            original.copy(files = original.files.map { it.copy(bytes = Long.MAX_VALUE) })
        }
        assertThrows(IllegalArgumentException::class.java) { installer(root) { error("no IO") }.install(set) }
        assertFalse(root.exists())
    }

    private fun seedPartial(root: File, set: AssetSet, bytes: ByteArray) {
        val partial = File(root, ".${set.id}.partial").apply { mkdirs() }
        File(partial, ".fingerprint").writeText(set.fingerprint)
        File(partial, "a.bin.part").writeBytes(bytes)
        File(partial, "a.bin.etag").writeText("\"v1\"")
    }

    private fun set(vararg files: Pair<String, ByteArray>) = assetTestSet(*files)
    private fun install(root: File, set: AssetSet, bytes: Map<String, ByteArray>) = installer(root) {
        response(bytes.getValue(it.url.substringAfterLast('/')))
    }.install(set)
    private fun installer(root: File, open: (AssetHttpRequest) -> AssetHttpResponse) =
        AssetInstaller(root, transport(open), reserveBytes = 0, bufferSize = 4)
    private fun transport(open: (AssetHttpRequest) -> AssetHttpResponse) = object : AssetTransport {
        override fun open(request: AssetHttpRequest) = open(request)
    }
    private fun response(
        bytes: ByteArray, code: Int = 200, range: String? = null,
        length: Long = bytes.size.toLong(), body: InputStream = ByteArrayInputStream(bytes),
    ) = AssetHttpResponse(code, length, "\"v1\"", "application/octet-stream", body, range)
}

internal fun assetTestSet(vararg files: Pair<String, ByteArray>) = AssetSet(
    "test-set", "Test", "Vision", "cpu", "MIT", "https://example.com/LICENSE",
    "https://example.com/upstream", "https://example.com/card",
    files.map { (name, bytes) -> AssetFile(name, "https://example.com/$name", bytes.size.toLong(), bytes.sha256()) },
)
