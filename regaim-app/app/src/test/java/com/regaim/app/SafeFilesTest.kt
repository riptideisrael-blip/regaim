package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeFilesTest {
    private fun dir(): File = Files.createTempDirectory("safefiles").toFile()

    @Test fun writesAndReadsBack() {
        val f = File(dir(), "a.json")
        assertTrue(SafeFiles.write(f, "{\"x\":1}"))
        val r = SafeFiles.readObject(f)
        assertTrue(r is SafeFiles.Read.Ok)
        assertEquals(1, (r as SafeFiles.Read.Ok).value.optInt("x"))
        assertFalse("no temp file left behind", File(f.parentFile, "a.json.tmp").exists())
    }

    @Test fun missingIsNotBroken() {
        assertTrue(SafeFiles.readObject(File(dir(), "none.json")) is SafeFiles.Read.Missing)
    }

    @Test fun brokenFileIsMovedAsideNotLost() {
        val d = dir(); val f = File(d, "c.json")
        f.writeText("{\"half\": ")
        val r = SafeFiles.readObject(f)
        assertTrue(r is SafeFiles.Read.Broken)
        val saved = (r as SafeFiles.Read.Broken).savedAs
        assertTrue(saved != null && saved.exists())
        assertEquals("{\"half\": ", saved!!.readText())
        assertFalse(f.exists())
    }

    @Test fun failedWriteKeepsTheOldFile() {
        val d = dir(); val f = File(d, "keep.json")
        f.writeText("old")
        // The temporary file's place is taken by a folder, so the write can't happen.
        File(d, "keep.json.tmp").mkdirs(); File(d, "keep.json.tmp/x").writeText("x")
        assertFalse(SafeFiles.write(f, "new"))
        assertEquals("old", f.readText())
    }
}
