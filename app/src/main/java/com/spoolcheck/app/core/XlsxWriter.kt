package com.spoolcheck.app.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal .xlsx writer. Builds the bare-minimum OOXML files needed for
 * Excel/LibreOffice/Numbers to open the result. No styles, no formulas,
 * just text cells.
 *
 * Avoids the 15-MB Apache POI dependency and keeps the APK lean.
 */
class XlsxWriter {

    /** Each sheet is a name + a 2D array of cell strings (rows then columns). */
    data class Sheet(val name: String, val rows: List<List<String>>)

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { zip ->
            // Build shared strings index from all cell values.
            val sharedStrings = mutableListOf<String>()
            val ssIndex = HashMap<String, Int>()
            for (s in sheets) {
                for (row in s.rows) {
                    for (cell in row) {
                        if (cell.isEmpty()) continue
                        if (!ssIndex.containsKey(cell)) {
                            ssIndex[cell] = sharedStrings.size
                            sharedStrings.add(cell)
                        }
                    }
                }
            }

            // [Content_Types].xml
            val ct = StringBuilder()
            ct.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            ct.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            ct.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            ct.append("""<Default Extension="xml" ContentType="application/xml"/>""")
            ct.append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            for (i in sheets.indices) {
                ct.append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            ct.append("""<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>""")
            ct.append("""</Types>""")
            zip.writeEntry("[Content_Types].xml", ct.toString())

            // _rels/.rels
            val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                """</Relationships>"""
            zip.writeEntry("_rels/.rels", rels)

            // xl/workbook.xml
            val wb = StringBuilder()
            wb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            wb.append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            wb.append("""<sheets>""")
            for (i in sheets.indices) {
                wb.append("""<sheet name="${escapeXml(sheets[i].name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            wb.append("""</sheets></workbook>""")
            zip.writeEntry("xl/workbook.xml", wb.toString())

            // xl/_rels/workbook.xml.rels
            val wbRels = StringBuilder()
            wbRels.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            wbRels.append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in sheets.indices) {
                wbRels.append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
            }
            wbRels.append("""<Relationship Id="rIdSS" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>""")
            wbRels.append("""</Relationships>""")
            zip.writeEntry("xl/_rels/workbook.xml.rels", wbRels.toString())

            // xl/sharedStrings.xml
            val ss = StringBuilder()
            ss.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            ss.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">""")
            for (s in sharedStrings) {
                ss.append("""<si><t xml:space="preserve">${escapeXml(s)}</t></si>""")
            }
            ss.append("""</sst>""")
            zip.writeEntry("xl/sharedStrings.xml", ss.toString())

            // xl/worksheets/sheetN.xml
            for ((i, sheet) in sheets.withIndex()) {
                val sx = StringBuilder()
                sx.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                sx.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                for ((rIdx, row) in sheet.rows.withIndex()) {
                    sx.append("""<row r="${rIdx + 1}">""")
                    for ((cIdx, cell) in row.withIndex()) {
                        if (cell.isEmpty()) continue
                        val ref = "${colLetters(cIdx)}${rIdx + 1}"
                        val sIdx = ssIndex[cell] ?: continue
                        sx.append("""<c r="$ref" t="s"><v>$sIdx</v></c>""")
                    }
                    sx.append("""</row>""")
                }
                sx.append("""</sheetData></worksheet>""")
                zip.writeEntry("xl/worksheets/sheet${i + 1}.xml", sx.toString())
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun colLetters(idx: Int): String {
        var n = idx + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
