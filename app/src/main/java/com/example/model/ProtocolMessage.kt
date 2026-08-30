package com.example.model

import org.json.JSONArray
import org.json.JSONObject

sealed class ProtocolMessage {

    data class GetCatalog(val offset: Int = 0, val limit: Int = 200) : ProtocolMessage()
    data class CatalogResponse(val items: List<MediaItem>, val deviceName: String = "") : ProtocolMessage()
    data class CatalogChunk(
        val items: List<MediaItem>,
        val chunkIndex: Int,
        val totalChunks: Int,
        val totalItems: Int,
        val deviceName: String = ""
    ) : ProtocolMessage()
    data class GetThumbnail(val id: String) : ProtocolMessage()
    data class ThumbnailResponse(val id: String, val base64Data: String) : ProtocolMessage()
    data class DownloadRequest(val id: String) : ProtocolMessage()
    data class DownloadHeader(
        val id: String,
        val fileName: String,
        val size: Long,
        val mimeType: String,
        val totalChunks: Int
    ) : ProtocolMessage()
    data class DownloadChunk(
        val id: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: String // Base64 chunk
    ) : ProtocolMessage()
    data class DownloadComplete(val id: String) : ProtocolMessage()
    data class DownloadCancel(val id: String) : ProtocolMessage()
    data object Ping : ProtocolMessage()
    data object Pong : ProtocolMessage()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is GetCatalog -> {
                json.put("type", "GET_CATALOG")
                json.put("offset", offset)
                json.put("limit", limit)
            }
            is CatalogResponse -> {
                json.put("type", "CATALOG_RESP")
                json.put("deviceName", deviceName)
                val array = JSONArray()
                items.forEach { item ->
                    val obj = JSONObject()
                    obj.put("id", item.id)
                    obj.put("name", item.displayName)
                    obj.put("mime", item.mimeType)
                    obj.put("size", item.size)
                    obj.put("date", item.dateModified)
                    obj.put("duration", item.durationMs)
                    obj.put("isVideo", item.isVideo)
                    obj.put("bucket", item.bucketName)
                    obj.put("path", item.relativePath)
                    array.put(obj)
                }
                json.put("items", array)
            }
            is CatalogChunk -> {
                json.put("type", "CATALOG_CHUNK")
                json.put("chunkIndex", chunkIndex)
                json.put("totalChunks", totalChunks)
                json.put("totalItems", totalItems)
                json.put("deviceName", deviceName)
                val array = JSONArray()
                items.forEach { item ->
                    val obj = JSONObject()
                    obj.put("id", item.id)
                    obj.put("name", item.displayName)
                    obj.put("mime", item.mimeType)
                    obj.put("size", item.size)
                    obj.put("date", item.dateModified)
                    obj.put("duration", item.durationMs)
                    obj.put("isVideo", item.isVideo)
                    obj.put("bucket", item.bucketName)
                    obj.put("path", item.relativePath)
                    array.put(obj)
                }
                json.put("items", array)
            }
            is GetThumbnail -> {
                json.put("type", "GET_THUMBNAIL")
                json.put("id", id)
            }
            is ThumbnailResponse -> {
                json.put("type", "THUMBNAIL_RESP")
                json.put("id", id)
                json.put("data", base64Data)
            }
            is DownloadRequest -> {
                json.put("type", "DOWNLOAD_REQ")
                json.put("id", id)
            }
            is DownloadHeader -> {
                json.put("type", "DOWNLOAD_HEADER")
                json.put("id", id)
                json.put("fileName", fileName)
                json.put("size", size)
                json.put("mime", mimeType)
                json.put("totalChunks", totalChunks)
            }
            is DownloadChunk -> {
                json.put("type", "DOWNLOAD_CHUNK")
                json.put("id", id)
                json.put("index", chunkIndex)
                json.put("total", totalChunks)
                json.put("data", data)
            }
            is DownloadComplete -> {
                json.put("type", "DOWNLOAD_COMPLETE")
                json.put("id", id)
            }
            is DownloadCancel -> {
                json.put("type", "DOWNLOAD_CANCEL")
                json.put("id", id)
            }
            is Ping -> json.put("type", "PING")
            is Pong -> json.put("type", "PONG")
        }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): ProtocolMessage? {
            return try {
                val json = JSONObject(jsonStr)
                when (json.optString("type")) {
                    "GET_CATALOG" -> GetCatalog(
                        offset = json.optInt("offset", 0),
                        limit = json.optInt("limit", 200)
                    )
                    "CATALOG_RESP" -> {
                        val array = json.optJSONArray("items") ?: JSONArray()
                        val list = mutableListOf<MediaItem>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(
                                MediaItem(
                                    id = obj.getString("id"),
                                    displayName = obj.getString("name"),
                                    mimeType = obj.optString("mime", "image/jpeg"),
                                    size = obj.optLong("size", 0L),
                                    dateModified = obj.optLong("date", 0L),
                                    durationMs = obj.optLong("duration", 0L),
                                    isVideo = obj.optBoolean("isVideo", false),
                                    bucketName = obj.optString("bucket", "Storage"),
                                    relativePath = obj.optString("path", "")
                                )
                            )
                        }
                        CatalogResponse(
                            items = list,
                            deviceName = json.optString("deviceName", "")
                        )
                    }
                    "CATALOG_CHUNK" -> {
                        val array = json.optJSONArray("items") ?: JSONArray()
                        val list = mutableListOf<MediaItem>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            list.add(
                                MediaItem(
                                    id = obj.getString("id"),
                                    displayName = obj.getString("name"),
                                    mimeType = obj.optString("mime", "image/jpeg"),
                                    size = obj.optLong("size", 0L),
                                    dateModified = obj.optLong("date", 0L),
                                    durationMs = obj.optLong("duration", 0L),
                                    isVideo = obj.optBoolean("isVideo", false),
                                    bucketName = obj.optString("bucket", "Storage"),
                                    relativePath = obj.optString("path", "")
                                )
                            )
                        }
                        CatalogChunk(
                            items = list,
                            chunkIndex = json.optInt("chunkIndex", 0),
                            totalChunks = json.optInt("totalChunks", 1),
                            totalItems = json.optInt("totalItems", list.size),
                            deviceName = json.optString("deviceName", "")
                        )
                    }
                    "GET_THUMBNAIL" -> GetThumbnail(json.getString("id"))
                    "THUMBNAIL_RESP" -> ThumbnailResponse(
                        id = json.getString("id"),
                        base64Data = json.getString("data")
                    )
                    "DOWNLOAD_REQ" -> DownloadRequest(json.getString("id"))
                    "DOWNLOAD_HEADER" -> DownloadHeader(
                        id = json.getString("id"),
                        fileName = json.getString("fileName"),
                        size = json.getLong("size"),
                        mimeType = json.optString("mime", "application/octet-stream"),
                        totalChunks = json.getInt("totalChunks")
                    )
                    "DOWNLOAD_CHUNK" -> DownloadChunk(
                        id = json.getString("id"),
                        chunkIndex = json.getInt("index"),
                        totalChunks = json.getInt("total"),
                        data = json.getString("data")
                    )
                    "DOWNLOAD_COMPLETE" -> DownloadComplete(json.getString("id"))
                    "DOWNLOAD_CANCEL" -> DownloadCancel(json.getString("id"))
                    "PING" -> Ping
                    "PONG" -> Pong
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
