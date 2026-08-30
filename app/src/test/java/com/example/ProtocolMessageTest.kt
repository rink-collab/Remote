package com.example

import com.example.model.MediaItem
import com.example.model.ProtocolMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProtocolMessageTest {

    @Test
    fun testCatalogResponseSerialization() {
        val items = listOf(
            MediaItem(
                id = "img_1",
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                size = 2048576L,
                dateModified = 1720000000L,
                isVideo = false
            ),
            MediaItem(
                id = "vid_2",
                displayName = "video.mp4",
                mimeType = "video/mp4",
                size = 52428800L,
                dateModified = 1720001000L,
                durationMs = 125000L,
                isVideo = true
            )
        )

        val msg = ProtocolMessage.CatalogResponse(items)
        val json = msg.toJson()
        val parsed = ProtocolMessage.fromJson(json)

        assertNotNull(parsed)
        assertTrue(parsed is ProtocolMessage.CatalogResponse)
        val parsedCatalog = parsed as ProtocolMessage.CatalogResponse
        assertEquals(2, parsedCatalog.items.size)
        assertEquals("photo.jpg", parsedCatalog.items[0].displayName)
        assertEquals(52428800L, parsedCatalog.items[1].size)
        assertTrue(parsedCatalog.items[1].isVideo)
    }

    @Test
    fun testDownloadChunkSerialization() {
        val chunkMsg = ProtocolMessage.DownloadChunk(
            id = "img_123",
            chunkIndex = 3,
            totalChunks = 10,
            data = "SGVsbG8gV2ViUlRD"
        )
        val json = chunkMsg.toJson()
        val parsed = ProtocolMessage.fromJson(json)

        assertNotNull(parsed)
        assertTrue(parsed is ProtocolMessage.DownloadChunk)
        val chunk = parsed as ProtocolMessage.DownloadChunk
        assertEquals("img_123", chunk.id)
        assertEquals(3, chunk.chunkIndex)
        assertEquals(10, chunk.totalChunks)
        assertEquals("SGVsbG8gV2ViUlRD", chunk.data)
    }

    @Test
    fun testThumbnailResponseSerialization() {
        val thumbMsg = ProtocolMessage.ThumbnailResponse("thumb_1", "base64ThumbnailData")
        val json = thumbMsg.toJson()
        val parsed = ProtocolMessage.fromJson(json)

        assertNotNull(parsed)
        assertTrue(parsed is ProtocolMessage.ThumbnailResponse)
        val thumb = parsed as ProtocolMessage.ThumbnailResponse
        assertEquals("thumb_1", thumb.id)
        assertEquals("base64ThumbnailData", thumb.base64Data)
    }

    @Test
    fun testBinaryChunkProtocolSerialization() {
        val testPayload = "Raw WebRTC Binary Chunk Payload 1234567890".toByteArray(Charsets.UTF_8)
        val packet = com.example.model.BinaryChunkProtocol.createChunkPacket(
            fileId = "media_item_999",
            chunkIndex = 5,
            totalChunks = 50,
            payload = testPayload
        )

        val parsed = com.example.model.BinaryChunkProtocol.parseChunkPacket(packet)
        assertNotNull(parsed)
        assertEquals("media_item_999", parsed?.fileId)
        assertEquals(5, parsed?.chunkIndex)
        assertEquals(50, parsed?.totalChunks)
        assertEquals(testPayload.size, parsed?.payload?.size)
        assertEquals("Raw WebRTC Binary Chunk Payload 1234567890", String(parsed!!.payload, Charsets.UTF_8))
    }
}
