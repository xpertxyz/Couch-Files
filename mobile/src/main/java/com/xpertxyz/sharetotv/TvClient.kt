package com.xpertxyz.sharetotv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HttpURLConnection client for the TV's file server. All methods block — call on Dispatchers.IO. */
class TvClient(val host: String, val port: Int) {

    data class RemoteFile(val name: String, val size: Long)
    data class Listing(val dirs: List<String>, val files: List<RemoteFile>)

    /** nav/seq/tick mirror the TV's browsing location and file-change counter (0/"" on old TVs). */
    data class Ping(val name: String, val nav: String, val navSeq: Long, val tick: Long)

    private fun open(path: String, query: Map<String, String> = emptyMap()): HttpURLConnection {
        val q = query.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val url = URL("http://$host:$port$path" + if (q.isEmpty()) "" else "?$q")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 15000
            // No socket reuse: the TV closes idle sockets after 30s and a streamed PUT on a
            // stale pooled socket fails without retry — that made the first send always fail.
            setRequestProperty("Connection", "close")
        }
    }

    /** Returns the TV's name and sync state; also serves as the connection check. */
    fun ping(): Ping {
        val conn = open("/ping")
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            return Ping(
                name = o.getString("name"),
                nav = o.optString("nav", ""),
                navSeq = o.optLong("seq", 0),
                tick = o.optLong("tick", 0),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun list(path: String): Listing {
        val conn = open("/list", mapOf("path" to path))
        try {
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val dirs = o.getJSONArray("dirs")
            val files = o.getJSONArray("files")
            return Listing(
                dirs = (0 until dirs.length()).map { dirs.getString(it) },
                files = (0 until files.length()).map {
                    val f = files.getJSONObject(it)
                    RemoteFile(f.getString("name"), f.getLong("size"))
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    fun mkdir(path: String) {
        val conn = open("/mkdir", mapOf("path" to path))
        try {
            conn.requestMethod = "POST"
            if (conn.responseCode != 200) throw IOException("TV refused (HTTP ${conn.responseCode})")
        } finally {
            conn.disconnect()
        }
    }

    fun upload(
        input: InputStream,
        name: String,
        size: Long,
        path: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit,
    ) {
        val conn = open("/upload", mapOf("path" to path, "name" to name))
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(size)
            input.use { inp -> conn.outputStream.use { out -> copy(inp, out, isCancelled, onProgress) } }
            if (conn.responseCode != 200) throw IOException("TV refused upload (HTTP ${conn.responseCode})")
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads straight into the phone's Downloads via MediaStore. */
    fun downloadToDownloads(
        context: Context,
        path: String,
        file: RemoteFile,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit,
    ) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create download entry")
        try {
            val rel = if (path.isEmpty()) file.name else "$path/${file.name}"
            val conn = open("/download", mapOf("path" to rel))
            try {
                if (conn.responseCode != 200) throw IOException("TV refused download (HTTP ${conn.responseCode})")
                conn.inputStream.use { inp ->
                    resolver.openOutputStream(uri)!!.use { out -> copy(inp, out, isCancelled, onProgress) }
                }
            } finally {
                conn.disconnect()
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun copy(
        input: InputStream,
        out: OutputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (isCancelled()) throw IOException("Cancelled")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            copied += n
            onProgress(copied)
        }
    }
}
