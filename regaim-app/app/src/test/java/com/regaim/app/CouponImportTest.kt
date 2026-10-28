package com.regaim.app

import com.regaim.app.XlsxReader.Sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CouponImportTest {
    private val today = LocalDate.of(2026, 10, 28)

    @Test fun headingsDatesAndValues() {
        val s = Sheet("מקדונלדס", listOf(
            listOf("קופון  למימוש", "תאריך תפוגה", "שויי קופון"),
            listOf(null, LocalDate.of(2024, 12, 30), 49.0),
            listOf(null, LocalDate.of(2028, 10, 30), 100.0),
            listOf("1234567890123456", "12/26", 848.0),
        ))
        val d = CouponImport.parse(listOf(s))
        // Rows without a code to redeem are left out.
        assertEquals(1, d.size)
        // A card with MM/YY and a 3-digit number: that number is the CVV, not an amount.
        assertEquals("2026-12-31", d[0].expires); assertEquals("848", d[0].cvv); assertEquals(null, d[0].value)
        assertEquals("1234567890123456", d[0].code)
    }

    @Test fun yearsTextDatesAndCvvText() {
        val s = Sheet("גוד פראם", listOf(
            listOf("קופון", "תאריך ", "שווי"),
            listOf(null, "סוף 2029", null),
            listOf("106720505-8999", 2029.0, null),
            listOf("9376-7605-4335-0199", "04/30", "Cvr-510"),
        ))
        val d = CouponImport.parse(listOf(s))
        assertEquals(2, d.size)
        assertEquals("106720505-8999", d[0].code); assertEquals("2029-12-31", d[0].expires)
        assertEquals("510", d[1].cvv); assertEquals("2030-04-30", d[1].expires)
    }

    @Test fun buyMeLinkThenAmount() {
        val s = Sheet("Buyme", listOf(
            listOf("https://buyme.co.il/giftcard/abc", null),
            listOf(null, "500 שקל"),
            listOf("https://buyme.co.il/x", null),
            listOf(null, "71 שקל לרשתות אופנה"),
        ))
        val d = CouponImport.parse(listOf(s))
        assertEquals(2, d.size)
        assertEquals(500.0, d[0].value!!, 0.0); assertEquals("https://buyme.co.il/giftcard/abc", d[0].link)
        assertEquals(71.0, d[1].value!!, 0.0)
    }

    @Test fun noHeadingsValueAndCvvColumns() {
        val s = Sheet("Dream card", listOf(
            listOf(null, 50.0, LocalDate.of(2029, 7, 25)),
            listOf("9376-7403-8696-1690", 50.0, 505.0, "03/30"),
            listOf("8765639174578983", 35.0, "עובדים בריא"),
        ))
        val d = CouponImport.parse(listOf(s))
        assertEquals(2, d.size)
        assertEquals(50.0, d[0].value!!, 0.0); assertEquals("505", d[0].cvv)
        assertEquals("עובדים בריא", d[1].note)
    }

    @Test fun itemsAndStableFingerprint() {
        val d = CouponImport.Draft("רמי לוי", code = "117672485-3666", value = 112.0, expires = "2028-12-31", unclear = true, row = 5)
        val i = CouponImport.toItem(d)
        assertEquals(FamilyItems.Kind.VOUCHER, i.kind)
        assertEquals("רמי לוי", i.vendor)
        assertTrue(i.note.contains("לבדוק"))
        assertEquals(d.hash, d.copy().hash)
    }

    @Test fun readsARealXlsxWithDatesAndSharedStrings() {
        val bytes = xlsx()
        val sheets = XlsxReader.read(bytes.inputStream())
        assertEquals(listOf("רשת אצה"), sheets.map { it.name })
        assertEquals(listOf("קופון", "תאריך", "שווי"), sheets[0].rows[0])
        assertEquals(listOf("82718228-7653", LocalDate.of(2028, 12, 31), 100.0), sheets[0].rows[1])
        val d = CouponImport.parse(sheets)
        assertEquals("82718228-7653", d[0].code); assertEquals("2028-12-31", d[0].expires)
    }

    @Test fun usedOnesWithoutACodeAreLeftOut() {
        val excel = FamilyItems.Item("1", FamilyItems.Kind.VOUCHER, vendor = "רמי לוי", amount = 200.0, source = "excel")
        assertTrue(CouponImport.noCode(excel))
        assertTrue(!CouponImport.noCode(excel.copy(code = "117672485-3666")))
        assertTrue(!CouponImport.noCode(excel.copy(note = "https://buyme.co.il/x")))
        assertTrue(!CouponImport.noCode(excel.copy(source = "pick")))
    }

    private fun xlsx(): ByteArray {
        val files = mapOf(
            "xl/workbook.xml" to """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="רשת אצה " sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>קופון</t></si><si><t>תאריך</t></si><si><t>שווי</t></si><si><t>82718228-7653</t></si></sst>""",
            "xl/styles.xml" to """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="d/m/yyyy"/></numFmts><cellXfs><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row><row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>47118</v></c><c r="C2"><v>100</v></c></row></sheetData></worksheet>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }
}
