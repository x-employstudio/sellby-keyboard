// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.util

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-rolled minimal .xlsx writer - no Apache POI/fastexcel dependency (POI is desktop-JVM-oriented
 * and a poor fit for Android; a lightweight alternative's Android compatibility isn't confirmed).
 * An .xlsx is just a ZIP of a handful of XML parts; this writes exactly the 5 parts required for a
 * single-sheet workbook, plus 2 extra `<Default>` content-type entries real Excel-generated files
 * always include (for compatibility with stricter readers like Google Sheets/LibreOffice, not just
 * Excel's own lenient, self-repairing parser).
 *
 * Two correctness rules are centralized here so no caller can accidentally bypass them:
 * - numeric cells are ALWAYS formatted with Locale.US (Indonesian locale renders decimals with a
 *   comma, which corrupts an OOXML numeric `<v>` value that requires a period as the separator)
 * - text cells are ALWAYS XML-escaped and stripped of XML 1.0-illegal control characters
 *   (user-entered names/notes can contain `& < > " '` or stray control bytes from a clipboard paste)
 */
object XlsxWriter {

    /** Cell type is dispatched at runtime: a [Number] becomes a numeric cell, anything else is
     *  written as text via `toString()`. Streamed row-by-row (not built as one giant String first),
     *  so memory stays bounded regardless of row count. */
    fun write(outputStream: OutputStream, sheetName: String, headers: List<String>, rows: List<List<Any>>) {
        ZipOutputStream(outputStream).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zip, "_rels/.rels", ROOT_RELS)
            writeEntry(zip, "xl/workbook.xml", workbookXml(sheetName))
            writeEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            writeSheetEntry(zip, headers, rows)
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeSheetEntry(zip: ZipOutputStream, headers: List<String>, rows: List<List<Any>>) {
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        // flush() (not close()) before closeEntry() - closing the writer would close the
        // underlying ZipOutputStream before the remaining parts get written.
        val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        writer.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        writer.write("<sheetData>")
        writeRow(writer, 1, headers)
        rows.forEachIndexed { index, row -> writeRow(writer, index + 2, row) }
        writer.write("</sheetData></worksheet>")
        writer.flush()
        zip.closeEntry()
    }

    private fun writeRow(writer: BufferedWriter, rowNumber: Int, cells: List<Any>) {
        writer.write("<row r=\"$rowNumber\">")
        cells.forEachIndexed { colIndex, value ->
            val ref = columnLetters(colIndex) + rowNumber
            if (value is Number) {
                writer.write("<c r=\"$ref\" t=\"n\"><v>${formatNumericCell(value)}</v></c>")
            } else {
                writer.write("<c r=\"$ref\" t=\"inlineStr\"><is><t>${escapeXml(value.toString())}</t></is></c>")
            }
        }
        writer.write("</row>")
    }

    private fun formatNumericCell(value: Number): String {
        val d = value.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.US, "%.2f", d)
    }

    private fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.code in 0x00..0x08 || ch.code == 0x0B || ch.code == 0x0C || ch.code in 0x0E..0x1F -> {}
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 0-based column index -> spreadsheet column letters (0 -> "A", 25 -> "Z", 26 -> "AA", ...). */
    private fun columnLetters(index0: Int): String {
        var n = index0 + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"

    private fun workbookXml(sheetName: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"${escapeXml(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        "</workbook>"
}
