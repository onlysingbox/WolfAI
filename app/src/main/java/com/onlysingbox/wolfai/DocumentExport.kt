package com.onlysingbox.wolfai
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Persian RTL PDF rendering via Android's text shaper; DOCX/XLSX are UTF-8 OOXML. */
object DocumentExport {
    fun createPdf(context: Context, title: String, text: String, destination: File): File {
        require(title.isNotBlank()) { "عنوان سند خالی است." }
        destination.parentFile?.mkdirs()
        val width = 595
        val height = 842
        val margin = 44
        val contentHeight = height - margin * 2
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val heading = title.trim()
        val content = android.text.SpannableStringBuilder().append(heading).append("\n\n").append(text)
        content.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, heading.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.setSpan(android.text.style.RelativeSizeSpan(1.25f), 0, heading.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.RTL)
            .setLineSpacing(4f, 1.12f).setIncludePad(true).build()
        val pageCount = maxOf(1, (layout.height + contentHeight - 1) / contentHeight)
        val pdf = PdfDocument()
        try {
            for (pageIndex in 0 until pageCount) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageIndex + 1).create())
                page.canvas.save()
                page.canvas.translate(margin.toFloat(), (margin - pageIndex * contentHeight).toFloat())
                layout.draw(page.canvas)
                page.canvas.restore()
                pdf.finishPage(page)
            }
            destination.outputStream().buffered().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return destination
    }

    fun createDocx(title: String, text: String, destination: File): File {
        require(title.isNotBlank()) { "عنوان سند خالی است." }
        val paragraphs = (listOf(title) + text.lines()).joinToString("") { line ->
            "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t xml:space=\"preserve\">${xml(line)}</w:t></w:r></w:p>"
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraphs}<w:sectPr/></w:body></w:document>"""
        zip(destination, mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to documentXml
        ))
        return destination
    }

    fun createXlsx(rows: List<List<String>>, destination: File): File {
        require(rows.isNotEmpty()) { "جدول خالی است." }
        val cells = rows.mapIndexed { r, row ->
            "<row r=\"${r + 1}\">" + row.mapIndexed { c, value ->
                val ref = column(c + 1) + (r + 1)
                "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
            }.joinToString("") + "</row>"
        }.joinToString("")
        val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${cells}</sheetData></worksheet>"""
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
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, value) ->
                out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray(Charsets.UTF_8)); out.closeEntry()
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