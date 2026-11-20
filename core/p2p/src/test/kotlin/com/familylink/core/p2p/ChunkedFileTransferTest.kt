package com.familylink.core.p2p

import com.familylink.core.crypto.CryptoVerificationException
import com.familylink.core.p2p.transfer.ChunkedFileTransferManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

class ChunkedFileTransferTest {

    @Test
    fun `chunked transfer supports out-of-order delivery resume and rejects tampered chunks`() {
        val tempDir = Files.createTempDirectory("famlink-transfer-test").toFile()
        try {
            val senderMgr = ChunkedFileTransferManager(File(tempDir, "sender"), chunkSize = 1024)
            val receiverMgr = ChunkedFileTransferManager(File(tempDir, "receiver"), chunkSize = 1024)

            val originalBytes = ByteArray(4500).also { Random(42).nextBytes(it) }
            val prepared = senderMgr.prepareOutgoingBytes(
                rawBytes = originalBytes,
                conversationId = "grp:family",
                fileName = "family photo.jpg",
                mimeType = "image/jpeg"
            )
            assertEquals(5, prepared.meta.totalChunks)

            receiverMgr.registerIncomingMeta(prepared.meta)

            // Deliver chunks 0, 2, 4 out of order (simulating interrupted transfer)
            assertNull(receiverMgr.ingestChunk(prepared.chunks[4]))
            assertNull(receiverMgr.ingestChunk(prepared.chunks[0]))
            assertNull(receiverMgr.ingestChunk(prepared.chunks[2]))

            // Receiver computes exact missing indices for resume request
            val missing = receiverMgr.getMissingChunkIndices(prepared.meta.fileId)
            assertEquals(listOf(1, 3), missing)

            // Tampered chunk must fail authentication
            val tampered = prepared.chunks[1].copy(chunkSha256Hex = "0".repeat(64))
            try {
                receiverMgr.ingestChunk(tampered)
                fail("Tampered chunk must throw CryptoVerificationException")
            } catch (_: CryptoVerificationException) {
                // expected
            }

            // Sender serves only the requested missing chunks -> transfer completes cleanly
            val resumeChunks = senderMgr.getOutgoingChunks(prepared.meta.fileId, missing)
            assertNull(receiverMgr.ingestChunk(resumeChunks[0]))
            val assembledFile = receiverMgr.ingestChunk(resumeChunks[1])
            assertNotNull(assembledFile)
            assertArrayEquals(originalBytes, assembledFile!!.readBytes())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
