package com.regaim.app

import org.w3c.dom.Element
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the cells of an Excel file (.xlsx) without a library: every sheet's name and its rows, each cell a String,
 * a Double, a LocalDate (numbers formatted as dates) or null. Enough for importing a list of coupons.
 * No Android here (unit-tested).
 */
object XlsxReader {
    data class Sheet(val name: String, val rows: List<List<Any?>>)

    private const val MAX_ENTRY = 20L * 1024 * 1024

    fun read(input: InputStream): List<Sheet> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.trimStart('/')
                if (!e.isDirectory && (n.startsWith("xl/") && (n.endsWith(".xml") || n.endsWith(".rels")))) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val k = z.read(buf); if (k < 0) break
                        total += k; if (total > MAX_ENTRY) throw IllegalArgumentException("too big")
                        out.write(buf, 0, k)
                    }
                    files[n] = out.toByteArray()
                }
            }
        }
        val workbook = files["xl/workbook.xml"] ?: throw IllegalArgumentException("not an Excel file")
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val dateStyles = files["xl/styles.xml"]?.let { dateStyles(it) } ?: emptySet()
        val rels = files["xl/_rels/workbook.xml.rels"]?.let { relationships(it) } ?: emptyMap()
        val sheets = mutableListOf<Sheet>()
        for (s in elements(doc(workbook), "sheet")) {
            val rid = s.getAttribute("r:id").ifEmpty { s.attributes.let { a -> (0 until a.length).map { a.item(it) }.firstOrNull { it.nodeName.endsWith(":id") }?.nodeValue.orEmpty() } }
            val target = rels[rid] ?: continue
            val path = if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")
            val data = files[path] ?: continue
            sheets += Sheet(s.getAttribute("name").trim(), rows(data, shared, dateStyles))
        }
        return sheets
    }

    private fun doc(b: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(b.inputStream())

    private fun elements(d: org.w3c.dom.Node, tag: String): List<Element> {
        val nl = (d as? org.w3c.dom.Document)?.getElementsByTagName(tag) ?: (d as Element).getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun sharedStrings(b: ByteArray): List<String> =
        elements(doc(b), "si").map { si -> elements(si, "t").joinToString("") { it.textContent } }

    private fun relationships(b: ByteArray): Map<String, String> =
        elements(doc(b), "Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }

    /** The style numbers whose number format is a date (built-in 14–22, 45–47, or a custom one with d/m/y and no "0"). */
    private fun dateStyles(b: ByteArray): Set<Int> {
        val d = doc(b)
        val custom = elements(d, "numFmt").associate { it.getAttribute("numFmtId").toIntOrNull() to it.getAttribute("formatCode") }
        fun isDate(id: Int): Boolean = id in 14..22 || id in 45..47 ||
            custom[id]?.lowercase()?.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "")?.let { f -> (f.contains('d') || f.contains('y')) && f.contains('m') } == true
        val xfs = elements(d, "cellXfs").firstOrNull()?.let { elements(it, "xf") } ?: emptyList()
        return xfs.mapIndexedNotNull { i, x -> i.takeIf { isDate(x.getAttribute("numFmtId").toIntOrNull() ?: 0) } }.toSet()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    /** An Excel day number (1900 system) as a date. */
    fun excelDate(serial: Double): LocalDate = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())

    private fun rows(b: ByteArray, shared: List<String>, dateStyles: Set<Int>): List<List<Any?>> {
        val out = mutableListOf<List<Any?>>()
        for (r in elements(doc(b), "row")) {
            val cells = sortedMapOf<Int, Any?>()
            for (c in elements(r, "c")) {
                val col = colIndex(c.getAttribute("r")).takeIf { it >= 0 } ?: cells.size
                val type = c.getAttribute("t")
                val v = elements(c, "v").firstOrNull()?.textContent
                val value: Any? = when (type) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> elements(c, "t").joinToString("") { it.textContent }
                    "str" -> v
                    "b" -> v
                    else -> v?.toDoubleOrNull()?.let { num ->
                        if ((c.getAttribute("s").toIntOrNull() ?: -1) in dateStyles && num in 1.0..2958465.0) excelDate(num) else num
                    }
                }
                cells[col] = (value as? String)?.takeIf { it.isNotBlank() } ?: value.takeIf { it !is String }
            }
            if (cells.isEmpty()) continue
            val width = (cells.lastKey() ?: 0) + 1
            out += (0 until width).map { cells[it] }
        }
        return out
    }
}
