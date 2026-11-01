package io.github.vprodc83.gamecatalog.data

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Leitura e gravação simples de planilhas .xlsx (formato do Excel e do Google Sheets), sem bibliotecas. */
object Xlsx {
    data class Sheet(val name: String, val rows: List<List<String>>)

    fun read(input: InputStream): List<Sheet> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        fun doc(name: String) = files[name]?.let { dbf.newDocumentBuilder().parse(ByteArrayInputStream(it)).documentElement }
        fun Element.children(tag: String): List<Element> { val nl = getElementsByTagName(tag); return (0 until nl.length).map { nl.item(it) as Element } }

        val shared = doc("xl/sharedStrings.xml")?.children("si")?.map { si -> si.children("t").joinToString("") { it.textContent } } ?: emptyList()
        val rels = doc("xl/_rels/workbook.xml.rels")?.children("Relationship")?.associate { it.getAttribute("Id") to it.getAttribute("Target") } ?: emptyMap()
        val wb = doc("xl/workbook.xml") ?: return emptyList()
        return wb.children("sheet").mapNotNull { s ->
            val target = rels[s.getAttribute("r:id")] ?: return@mapNotNull null
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")
            val sheet = doc(path) ?: return@mapNotNull null
            val rows = sheet.children("row").map { row ->
                val cells = sortedMapOf<Int, String>()
                row.children("c").forEach { c ->
                    val col = colIndex(c.getAttribute("r"))
                    val t = c.getAttribute("t")
                    val v = c.children("v").firstOrNull()?.textContent ?: ""
                    cells[col] = when (t) {
                        "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                        "inlineStr" -> c.children("t").joinToString("") { it.textContent }
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        else -> v.removeSuffix(".0")
                    }
                }
                if (cells.isEmpty()) emptyList() else (0..cells.lastKey()).map { cells[it]?.trim() ?: "" }
            }
            Sheet(s.getAttribute("name"), rows)
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }; return sb.toString() }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    fun write(sheets: List<Sheet>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, s -> """<sheet name="${esc(s.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, s ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                s.rows.forEachIndexed { r, row ->
                    sb.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, v -> if (v.isNotEmpty()) sb.append("""<c r="${colName(c)}${r + 1}" t="inlineStr"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space="preserve">${esc(v)}</t></is></c>""") }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
    }
}
