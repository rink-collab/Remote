package com.example.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object BinaryChunkProtocol {
    const val MAGIC_BYTE: Byte = 0x43 // 'C' for Chunk packet

    fun createChunkPacket(
        fileId: String,
        chunkIndex: Int,
        totalChunks: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size
    ): ByteArray {
        val idBytes = fileId.toByteArray(StandardCharsets.UTF_8)
        val headerSize = 1 + 2 + idBytes.size + 4 + 4 + 4
        val totalSize = headerSize + payloadLength
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(MAGIC_BYTE)
        buffer.putShort(idBytes.size.toShort())
        buffer.put(idBytes)
        buffer.putInt(chunkIndex)
        buffer.putInt(totalChunks)
        buffer.putInt(payloadLength)
        buffer.put(payload, payloadOffset, payloadLength)
        return buffer.array()
    }

    data class ParsedChunk(
        val fileId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val payload: ByteArray
    )

    fun parseChunkPacket(bytes: ByteArray): ParsedChunk? {
        if (bytes.size < 15) return null
        val buffer = ByteBuffer.wrap(bytes)
        val magic = buffer.get()
        if (magic != MAGIC_BYTE) return null

        val idLength = buffer.short.toInt()
        if (idLength <= 0 || buffer.remaining() < idLength + 12) return null

        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val fileId = String(idBytes, StandardCharsets.UTF_8)

        val chunkIndex = buffer.int
        val totalChunks = buffer.int
        val payloadLength = buffer.int

        if (payloadLength < 0 || buffer.remaining() < payloadLength) return null

        val payload = ByteArray(payloadLength)
        buffer.get(payload)

        return ParsedChunk(
            fileId = fileId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payload = payload
        )
    }
}
