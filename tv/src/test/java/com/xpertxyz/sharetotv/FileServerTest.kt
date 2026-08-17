package com.xpertxyz.sharetotv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class FileServerTest {

    @Test
    fun safeResolveJailsToBase() {
        val base = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertEquals(base.canonicalFile, FileServer.safeResolve(base, "").canonicalFile)
            assertEquals(File(base, "a/b").canonicalFile, FileServer.safeResolve(base, "a/b").canonicalFile)
            assertThrows(SecurityException::class.java) { FileServer.safeResolve(base, "..") }
            assertThrows(SecurityException::class.java) { FileServer.safeResolve(base, "a/../../etc") }
            // absolute child paths re-root under base (java.io.File semantics) — still jailed
            assertEquals(true, FileServer.safeResolve(base, "/etc/passwd").canonicalPath.startsWith(base.canonicalPath))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun resolveMapsRootsByLabel() {
        val a = kotlin.io.path.createTempDirectory().toFile()
        val b = kotlin.io.path.createTempDirectory().toFile()
        try {
            val srv = FileServer(0, listOf(Root("Couch Files", a), Root("Device storage", b)), "tv")
            assertEquals(a.canonicalFile, srv.resolve("").canonicalFile)
            assertEquals(a.canonicalFile, srv.resolve("Couch Files").canonicalFile)
            assertEquals(File(b, "DCIM").canonicalFile, srv.resolve("Device storage/DCIM").canonicalFile)
            // legacy phone paths (no root prefix) stay inside the first root
            assertEquals(File(a, "sub/x").canonicalFile, srv.resolve("sub/x").canonicalFile)
            assertThrows(SecurityException::class.java) { srv.resolve("Device storage/../etc") }
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }

    @Test
    fun sanitizeStripsPathTraversal() {
        assertEquals("etc", FileServer.sanitize("../../etc"))
        assertEquals("passwd", FileServer.sanitize("/etc/passwd"))
        assertEquals("x.txt", FileServer.sanitize("..\\..\\x.txt"))
        assertEquals("file", FileServer.sanitize(".."))
        assertEquals("file", FileServer.sanitize(""))
        assertEquals("movie.mp4", FileServer.sanitize("movie.mp4"))
    }

    @Test
    fun dedupeNeverOverwrites() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertEquals("a.txt", FileServer.dedupe(dir, "a.txt").name)
            File(dir, "a.txt").writeText("x")
            assertEquals("a (1).txt", FileServer.dedupe(dir, "a.txt").name)
            File(dir, "a (1).txt").writeText("x")
            assertEquals("a (2).txt", FileServer.dedupe(dir, "a.txt").name)
            File(dir, "noext").writeText("x")
            assertEquals("noext (1)", FileServer.dedupe(dir, "noext").name)
            File(dir, ".hidden").writeText("x")
            assertEquals(".hidden (1)", FileServer.dedupe(dir, ".hidden").name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
