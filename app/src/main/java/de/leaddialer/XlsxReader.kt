package de.leaddialer

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Minimal .xlsx reader: an .xlsx file is a zip of XML parts. Reads cell text only (no
 * formatting, no formulas), which is all a lead import needs, without a library.
 * Uses SAX from javax, which exists on Android and on the JVM, so it is unit-testable.
 */
object XlsxReader {

    /** Zip files start with "PK"; CSV text never does in practice. */
    fun isXlsx(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** All sheets in workbook order, each as rows of cell strings ("" for empty cells). */
    fun sheets(bytes: ByteArray): List<List<List<String>>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && (e.name.endsWith(".xml") || e.name.endsWith(".rels"))) parts[e.name] = zip.readBytes()
            }
        }
        val strings = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        return sheetPaths(parts).mapNotNull { parts[it] }.map { rows(it, strings) }
    }

    /** Sheet part names in the order the workbook lists them. */
    private fun sheetPaths(parts: Map<String, ByteArray>): List<String> {
        val rels = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { xml ->
            parse(xml, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                    if (name(qName) != "Relationship") return
                    val target = a.getValue("Target") ?: return
                    rels[a.getValue("Id") ?: ""] = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
                }
            })
        }
        val ordered = ArrayList<String>()
        parts["xl/workbook.xml"]?.let { xml ->
            parse(xml, object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                    if (name(qName) != "sheet") return
                    // The relationship id is namespaced (r:id); match it by its local name.
                    val id = (0 until a.length).firstOrNull { name(a.getQName(it)) == "id" }?.let { a.getValue(it) }
                    rels[id]?.let { ordered.add(it) }
                }
            })
        }
        if (ordered.isEmpty()) ordered.addAll(parts.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted())
        return ordered
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = ArrayList<String>()
        parse(xml, object : DefaultHandler() {
            val sb = StringBuilder()
            var inText = false
            var phonetic = 0

            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (name(qName)) {
                    "si" -> sb.setLength(0)
                    "t" -> inText = phonetic == 0
                    // Phonetic hints would otherwise be glued into the text.
                    "rPh" -> phonetic++
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) sb.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (name(qName)) {
                    "t" -> inText = false
                    "rPh" -> phonetic--
                    "si" -> out.add(sb.toString())
                }
            }
        })
        return out
    }

    private fun rows(xml: ByteArray, strings: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parse(xml, object : DefaultHandler() {
            var row = ArrayList<String>()
            var col = 0
            var type: String? = null
            val value = StringBuilder()
            var inValue = false

            override fun startElement(uri: String?, local: String?, qName: String, a: Attributes) {
                when (name(qName)) {
                    "row" -> {
                        row = ArrayList()
                        col = 0
                    }
                    "c" -> {
                        type = a.getValue("t")
                        col = a.getValue("r")?.let { columnIndex(it) } ?: col
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, qName: String) {
                when (name(qName)) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        while (row.size < col) row.add("")
                        row.add(cellText(value.toString(), type, strings))
                        col = row.size
                    }
                    "row" -> rows.add(row)
                }
            }
        })
        return rows
    }

    private fun cellText(raw: String, type: String?, strings: List<String>): String = when (type) {
        "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) } ?: ""
        "str", "inlineStr", "e" -> raw
        "b" -> if (raw == "1") "WAHR" else "FALSCH"
        // Plain numbers: Excel may store a phone number as 4.36705560222E11.
        else -> runCatching { BigDecimal(raw.trim()).stripTrailingZeros().toPlainString() }.getOrDefault(raw)
    }

    /** "C12" -> 2 */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    /** Element name without a namespace prefix ("x:row" -> "row"). */
    private fun name(qName: String) = qName.substringAfter(':')

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }
}
