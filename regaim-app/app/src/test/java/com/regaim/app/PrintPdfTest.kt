package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PrintPdfTest {
    @Test fun aShortBookIsFilledUpToLulusMinimum() {
        val plan = PrintSpec.plan(8, dedication = true)
        assertEquals(24, plan.size)
        assertEquals(PrintSpec.Kind.TITLE, plan.first().first)
        assertEquals(PrintSpec.Kind.END, plan.last().first)
        // Each story page: its words facing its picture, in order.
        val story = plan.filter { it.first == PrintSpec.Kind.TEXT || it.first == PrintSpec.Kind.PICTURE }
        assertEquals(16, story.size)
        assertEquals(listOf(0, 0, 1, 1), story.take(4).map { it.second })
    }

    @Test fun aLongBookHasAnEvenPageCount() {
        val plan = PrintSpec.plan(15, dedication = false)
        assertTrue(plan.size >= 24 && plan.size % 2 == 0)
        assertEquals(2 + 30 + 1, plan.count { it.first != PrintSpec.Kind.NOTES })
    }

    @Test fun pageSizesIncludeTheBleed() {
        assertEquals(630, PrintSpec.pageWpt); assertEquals(810, PrintSpec.pageHpt)
        assertEquals(2625, PrintSpec.pageWpx); assertEquals(3375, PrintSpec.pageHpx)
        assertEquals(5775 to 3825, PrintSpec.coverPx(1386.0, 918.0))
    }

    @Test fun thePdfPointsToEveryObject() {
        val jpg = File.createTempFile("page", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, 1, 2, 3, -1, -39)) }
        val pages = listOf(
            StreamPdf.Page(630.0, 810.0, listOf(StreamPdf.Img(jpg, 10, 10, 0.0, 0.0, 630.0, 810.0))),
            StreamPdf.Page(1386.5, 918.0, listOf(StreamPdf.Img(jpg, 5, 10, 0.0, 0.0, 693.25, 918.0), StreamPdf.Img(jpg, 5, 10, 693.25, 0.0, 693.25, 918.0))),
        )
        val out = ByteArrayOutputStream()
        StreamPdf.write(out, pages)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        val xref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", xref))
        val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(2 + 3 + 4, entries.size)
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
        assertTrue(text.contains("/MediaBox [0 0 1386.500 918]"))
        jpg.delete()
    }
}
