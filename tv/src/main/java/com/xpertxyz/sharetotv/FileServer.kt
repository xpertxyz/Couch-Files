package com.xpertxyz.sharetotv

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLConnection

data class Transfer(
    val name: String,
    val bytes: Long,
    val total: Long,
    val done: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Folder-aware file server over [base].
 *   GET  /ping                    -> {"name": deviceName}
 *   GET  /list?path=rel           -> {"dirs":[...], "files":[{"name","size","mtime"}]}
 *   GET  /download?path=rel/f.txt -> file stream
 *   PUT  /upload?path=rel&name=f  -> raw body saved into base/rel
 *   POST /mkdir?path=rel/new
 * Every path is canonicalized and must stay inside [base].
 */
// ponytail: no auth — anyone on the same Wi-Fi can push/pull files. Add a pairing PIN if that matters.
class FileServer(
    port: Int,
    private val base: File,
    private val deviceName: String,
    private val onSaved: (File) -> Unit = {},
) : NanoHTTPD(port) {

    /** Latest incoming transfer state, for the UI. */
    val incoming = MutableStateFlow<Transfer?>(null)

    /** Bumped whenever the tree changes. */
    val filesChanged = MutableStateFlow(0)

    /** Wall-clock millis of the last request from any client — the phone's heartbeat. */
    val lastSeen = MutableStateFlow(0L)

    override fun serve(session: IHTTPSession): Response {
        lastSeen.value = System.currentTimeMillis()
        val path = session.parms["path"] ?: ""
        return try {
            when {
                session.method == Method.GET && session.uri == "/ping" ->
                    json(JSONObject().put("name", deviceName))
                session.method == Method.GET && session.uri == "/list" -> list(path)
                session.method == Method.GET && session.uri == "/download" -> download(path)
                session.method == Method.PUT && session.uri == "/upload" ->
                    receive(session, path, session.parms["name"] ?: "file")
                session.method == Method.POST && session.uri == "/mkdir" -> mkdir(path)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: SecurityException) {
            newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun json(o: Any): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private fun list(path: String): Response {
        val dir = safeResolve(base, path)
        val dirs = JSONArray()
        val files = JSONArray()
        dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { f ->
            when {
                f.isDirectory -> dirs.put(f.name)
                f.name.endsWith(".part") -> {}
                else -> files.put(
                    JSONObject().put("name", f.name).put("size", f.length()).put("mtime", f.lastModified()),
                )
            }
        }
        return json(JSONObject().put("dirs", dirs).put("files", files))
    }

    private fun download(path: String): Response {
        val f = safeResolve(base, path)
        if (!f.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no such file")
        val mime = URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(f), f.length())
    }

    private fun mkdir(path: String): Response {
        val dir = safeResolve(base, path)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create folder")
        filesChanged.value++
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
    }

    private fun receive(session: IHTTPSession, path: String, rawName: String): Response {
        val total = session.headers["content-length"]?.toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, MIME_PLAINTEXT, "Content-Length required")
        val dir = safeResolve(base, path)
        dir.mkdirs()
        val target = dedupe(dir, sanitize(rawName))
        val tmp = File(dir, target.name + ".part")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var copied = 0L
                incoming.value = Transfer(target.name, 0, total)
                while (copied < total) {
                    val n = session.inputStream.read(buf, 0, minOf(buf.size.toLong(), total - copied).toInt())
                    if (n < 0) throw EOFException("sender disconnected")
                    out.write(buf, 0, n)
                    copied += n
                    incoming.value = Transfer(target.name, copied, total)
                }
            }
            if (!tmp.renameTo(target)) throw IOException("could not save ${target.name}")
            onSaved(target)
            incoming.value = Transfer(target.name, total, total, done = true)
            filesChanged.value++
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
        } catch (e: Exception) {
            tmp.delete()
            incoming.value = Transfer(target.name, 0, total, failed = true)
            throw e
        }
    }

    companion object {
        /** Resolves [rel] under [base]; throws if the canonical result escapes it. */
        fun safeResolve(base: File, rel: String): File {
            val f = File(base, rel)
            val canon = f.canonicalPath
            val baseCanon = base.canonicalPath
            if (canon != baseCanon && !canon.startsWith(baseCanon + File.separator)) {
                throw SecurityException("path escapes shared folder")
            }
            return f
        }

        /** Uploaded names must never escape their folder. */
        fun sanitize(name: String): String {
            val clean = name.substringAfterLast('/').substringAfterLast('\\').trim()
            return if (clean.isEmpty() || clean == "." || clean == "..") "file" else clean
        }

        /** Never overwrite: "a.txt" -> "a (1).txt" while taken. */
        fun dedupe(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (f.exists()) {
                f = File(dir, "$base (${i++})$ext")
            }
            return f
        }
    }
}
