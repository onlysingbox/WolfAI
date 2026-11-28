package com.onlysingbox.wolfai

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates real OOXML DOCX/XLSX containers without a paid SDK. */
object DocumentExport {
    fun createPdf(context: Context, title: String, text: String, destination: File): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 11f)
                stream.newLineAtOffset(48f, 750f)
                (listOf(title) + text.lines()).forEach { line ->
                    val safe = line.replace(Regex("[^\\x20-\\x7E]"), "?").take(105)
                    stream.showText(safe)
                    stream.newLineAtOffset(0f, -14f)
                }
                stream.endText()
            }
            document.save(destination)
        }
        return destination
    }

    fun createDocx(title: String, text: String, destination: File): File {
        val paragraphs = (listOf(title) + text.lines()).joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>"
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr/></w:body></w:document>"""
        zip(destination, mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to documentXml
        ))
        return destination
    }

    fun createXlsx(rows: List<List<String>>, destination: File): File {
        val cells = rows.mapIndexed { r, row ->
            "<row r=\"${r + 1}\">" + row.mapIndexed { c, value ->
                val ref = column(c + 1) + (r + 1)
                "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$cells</sheetData></worksheet>"""
        zip(destination, mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="WOLF Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet
        ))
        return destination
    }

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name))
                out.write(value.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun column(number: Int): String {
        var n = number
        val out = StringBuilder()
        while (n > 0) { n--; out.insert(0, ('A'.code + n % 26).toChar()); n /= 26 }
        return out.toString()
    }
}
