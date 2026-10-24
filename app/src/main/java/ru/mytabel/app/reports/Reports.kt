package ru.mytabel.app.reports

import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.mytabel.app.settings.translate

enum class ReportColumn(val title: String) {
    DATE("Дата"), EMPLOYER("Работодатель"), KIND("Операция"), START("Начало"), END("Окончание"),
    DURATION("Длительность, ч"), BREAK("Перерыв, мин"), PAID("Оплачено часов"), RATE("Ставка, $/ч"),
    REGULAR("Обычные часы"), OVERTIME("Сверхурочные 1,5×"), DOUBLE("Двойные часы"), GROSS("Начислено, $"),
    ADVANCE("Аванс, $"), SALARY("Зарплата, $"), ACTUAL("Факт. удержания, $"), ESTIMATE("Оценка удержаний, $"), DUE("Остаток, $")
}
val builtInTemplates = linkedMapOf(
    "Краткий" to listOf(ReportColumn.DATE, ReportColumn.EMPLOYER, ReportColumn.KIND, ReportColumn.PAID, ReportColumn.GROSS),
    "Полный" to ReportColumn.entries.toList(),
    "Финансовый" to listOf(ReportColumn.DATE, ReportColumn.EMPLOYER, ReportColumn.KIND, ReportColumn.GROSS, ReportColumn.ADVANCE, ReportColumn.SALARY, ReportColumn.ACTUAL, ReportColumn.ESTIMATE, ReportColumn.DUE)
)
data class ReportData(val columns: List<ReportColumn>, val rows: List<List<String>>, val summary: List<String>, val headers:List<String> = columns.map{it.title},val language:String="ru")
object Reports {
    fun create(snapshot: Snapshot, ids: Set<Long>, from: LocalDate, through: LocalDate, columns: List<ReportColumn>,language:String="ru",currency:String="USD"): ReportData {
        fun money(cents:Long)=ru.mytabel.app.domain.money(cents,currency)
        require(from <= through) { "Начальная дата позже конечной" }
        require(columns.isNotEmpty()) { "Выберите хотя бы один столбец" }
        val employers = snapshot.employers.filter { it.id in ids }
        val allPay = employers.flatMap { Payroll.calculate(it, snapshot.shifts) }
        val selectedPay = allPay.filter { p -> val e = employers.first { it.id == p.shift.employerId }; p.shift.date(e) in from..through }
        val rows = mutableListOf<Pair<Long, Map<ReportColumn, String>>>()
        fun decimal(cents: Long) = java.math.BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()
        fun decimalHours(seconds: Long) = java.math.BigDecimal(seconds).divide(java.math.BigDecimal(3600), 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val time = DateTimeFormatter.ofPattern("HH:mm")
        employers.forEach { e ->
            val opening = Payroll.balance(listOf(e), allPay, snapshot.payments, from.minusDays(1)).due
            var running = opening
            rows += (from.toEpochDay() * 86400000 - 1) to mapOf(ReportColumn.DATE to from.toString(), ReportColumn.EMPLOYER to e.name, ReportColumn.KIND to "Входящий остаток", ReportColumn.DUE to decimal(opening))
            data class Event(val day: Long, val order: Long, val pay: ShiftPay? = null, val payment: Payment? = null)
            val events = selectedPay.filter { it.shift.employerId == e.id }.map { Event(it.shift.date(e).toEpochDay(), it.shift.start, pay = it) } +
                snapshot.payments.filter { it.employerId == e.id && it.day in from.toEpochDay()..through.toEpochDay() && (it.kind != "WITHHOLDING" || e.taxMode == "MANUAL") }.map { Event(it.day, Long.MAX_VALUE / 2 + it.id, payment = it) }
            events.sortedWith(compareBy<Event> { it.day }.thenBy { it.order }).forEach { event ->
                val row = mutableMapOf(ReportColumn.DATE to LocalDate.ofEpochDay(event.day).toString(), ReportColumn.EMPLOYER to e.name)
                event.pay?.let { p ->
                    running += p.grossCents
                    row += mapOf(ReportColumn.KIND to "Смена", ReportColumn.START to Instant.ofEpochMilli(p.shift.start).atZone(ZoneId.of(e.zone)).format(time),
                        ReportColumn.END to Instant.ofEpochMilli(p.shift.end!!).atZone(ZoneId.of(e.zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        ReportColumn.DURATION to decimalHours(p.elapsedSeconds), ReportColumn.BREAK to p.shift.breakMinutes.toString(), ReportColumn.PAID to decimalHours(p.paidSeconds),
                        ReportColumn.RATE to decimal(p.shift.rateCents), ReportColumn.REGULAR to decimalHours(p.regularSeconds), ReportColumn.OVERTIME to decimalHours(p.overtimeSeconds),
                        ReportColumn.DOUBLE to decimalHours(p.doubleSeconds), ReportColumn.GROSS to decimal(p.grossCents))
                    if (e.taxMode == "ESTIMATE") row[ReportColumn.ESTIMATE] = decimal(java.math.BigDecimal(p.grossCents).multiply(java.math.BigDecimal(e.estimatePercent)).divide(java.math.BigDecimal(100), 0, java.math.RoundingMode.HALF_UP).toLong())
                }
                event.payment?.let { p ->
                    running -= p.cents
                    val column = when (p.kind) { "ADVANCE" -> ReportColumn.ADVANCE; "WITHHOLDING" -> ReportColumn.ACTUAL; else -> ReportColumn.SALARY }
                    row[column] = decimal(p.cents)
                    row[ReportColumn.KIND] = when (p.kind) { "ADVANCE" -> "Аванс"; "WITHHOLDING" -> "Фактическое удержание"; else -> "Зарплата" }
                }
                row[ReportColumn.DUE] = decimal(running)
                rows += event.day * 86400000 to row
            }
        }
        val period = Payroll.balance(employers, allPay, snapshot.payments, through, from)
        val closing = Payroll.balance(employers, allPay, snapshot.payments, through)
        val summary = mutableListOf("Мой табель · $from — $through · $currency", "Смен: ${selectedPay.size}; оплачено часов: ${decimalHours(selectedPay.sumOf { it.paidSeconds })}",
            "Начислено: ${money(period.gross)}; получено: ${money(period.received)}", "Входящий остаток: ${money(Payroll.balance(employers, allPay, snapshot.payments, from.minusDays(1)).due)}; осталось с переносом: ${money(closing.due)}")
        if (employers.any { it.taxMode == "MANUAL" }) summary += "Фактические удержания: ${money(period.actual)}"
        if (employers.any { it.taxMode == "ESTIMATE" }) summary += "Оценочные удержания: ${money(period.estimate)} (не фактические; не вычитаются из остатка)"
        summary += "Смены относятся к дате начала; расчёт overtime учитывает полные рабочие недели. Выплаты и удержания — отдельные строки."
        val visibleColumns = columns.filter { c -> (c != ReportColumn.ACTUAL || employers.any { it.taxMode == "MANUAL" }) && (c != ReportColumn.ESTIMATE || employers.any { it.taxMode == "ESTIMATE" }) }
        require(visibleColumns.isNotEmpty()) { "Выбранные налоговые поля отключены. Добавьте другие столбцы." }
        return localized(ReportData(visibleColumns, rows.sortedBy { it.first }.map { (_, row) -> visibleColumns.map { row[it].orEmpty() } }, summary),language,currency)
    }

    fun schedule(snapshot:Snapshot,ids:Set<Long>,from:LocalDate,through:LocalDate,language:String="ru"):ReportData {
        require(from<=through && java.time.temporal.ChronoUnit.DAYS.between(from,through)<=3660){"Проверьте диапазон дат (не более 10 лет)"}
        val employers=snapshot.employers.filter{it.id in ids};require(employers.isNotEmpty()){"Выберите работодателя"}
        val columns=listOf(ReportColumn.DATE,ReportColumn.EMPLOYER,ReportColumn.KIND,ReportColumn.START,ReportColumn.END,ReportColumn.BREAK,ReportColumn.PAID)
        var shifts=0;var minutes=0L
        val rows=buildList{var date=from;while(date<=through){employers.forEach{e->val d=Schedules.day(e.id,date,snapshot.rules,snapshot.scheduleDays);val paid=Schedules.paidMinutes(d);if(d.working){shifts++;minutes+=paid};fun clock(m:Int)="%02d:%02d".format(m/60,m%60)
            add(listOf(date.toString(),e.name,if(d.working)"Рабочий день" else "Выходной",if(d.working)clock(d.startMinutes)else"",if(d.working)clock(d.endMinutes)else"",if(d.working)d.breakMinutes.toString()else"",if(d.working)java.math.BigDecimal(paid).divide(java.math.BigDecimal(60),4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()else""))};date=date.plusDays(1)}}
        val result=localized(ReportData(columns,rows,listOf("График работы · $from — $through","Плановых смен: $shifts; Плановые часы: ${hours(minutes*60)}")),language,"USD")
        return result.copy(headers=result.headers.dropLast(1)+if(language=="en")"Planned hours" else "Плановые часы")
    }
    private fun localized(data:ReportData,language:String,currency:String):ReportData {
        val kind=data.columns.indexOf(ReportColumn.KIND)
        return data.copy(headers=data.columns.map{translate(it.title,language).replace("$",currency)},language=language,
            rows=data.rows.map{row->row.mapIndexed{i,value->if(i==kind)translate(value,language)else value}},summary=data.summary.map{translate(it,language)})
    }
    fun html(data:ReportData):String=buildString {
        append("<!doctype html><html lang=\"${xml(data.language)}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>${xml(data.summary.firstOrNull().orEmpty())}</title><style>body{font:14px Arial,sans-serif;color:#172341;margin:24px}h1{font-size:22px}table{border-collapse:collapse;width:100%;font-size:12px}th,td{padding:8px;border:1px solid #bbc8df;text-align:left;vertical-align:top}th{background:#e8efff}tr:nth-child(even){background:#f5f8ff}thead{display:table-header-group}tr{break-inside:avoid}@page{size:landscape;margin:12mm}@media print{body{margin:0}th{-webkit-print-color-adjust:exact}}</style></head><body>")
        append("<h1>${xml(data.summary.firstOrNull().orEmpty())}</h1><table><thead><tr>");data.headers.forEach{append("<th>${xml(it)}</th>")};append("</tr></thead><tbody>")
        data.rows.forEach{row->append("<tr>");row.forEach{append("<td>${xml(it)}</td>")};append("</tr>")};append("</tbody></table><footer>")
        data.summary.drop(1).forEach{append("<p>${xml(it)}</p>")};append("</footer></body></html>")
    }

    private fun xml(value: String): String = buildString {
        value.forEach { c -> when(c) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); else -> if (c >= ' ' || c == '\n' || c == '\t' || c == '\r') append(c) } }
    }
    private fun ZipOutputStream.part(path: String, content: String) { putNextEntry(ZipEntry(path)); write(content.toByteArray(Charsets.UTF_8)); closeEntry() }
    fun xlsx(data: ReportData, output: OutputStream) {
        ZipOutputStream(output).use { z ->
            z.part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            z.part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            z.part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(translate("Мой табель",data.language))}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            z.part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            fun letter(index: Int): String { var n = index + 1; var r = ""; while(n > 0) { n--; r = ('A' + n % 26) + r; n /= 26 }; return r }
            val rows = listOf(data.headers) + data.rows + listOf(emptyList()) + data.summary.map { listOf(it) }
            val sheet = buildString {
                append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews><cols>""")
                data.columns.indices.forEach { append("""<col min="${it+1}" max="${it+1}" width="24" customWidth="1"/>""") }
                append("</cols><sheetData>")
                rows.forEachIndexed { r, cells ->
                    append("<row r=\"${r+1}\">")
                    cells.forEachIndexed { c, value ->
                        val numeric = r in 1..data.rows.size && c < data.columns.size && data.columns[c] !in listOf(ReportColumn.DATE, ReportColumn.EMPLOYER, ReportColumn.KIND, ReportColumn.START, ReportColumn.END) && value.toBigDecimalOrNull() != null
                        val ref = "${letter(c)}${r+1}"
                        if (numeric) append("<c r=\"$ref\"><v>$value</v></c>") else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData><autoFilter ref=\"A1:${letter(data.columns.size-1)}${data.rows.size+1}\"/></worksheet>")
            }
            z.part("xl/worksheets/sheet1.xml", sheet)
        }
    }
    fun docx(data: ReportData, output: OutputStream) {
        ZipOutputStream(output).use { z ->
            z.part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            z.part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            fun p(text: String, bold: Boolean = false) = "<w:p><w:r><w:rPr><w:sz w:val=\"18\"/>${if(bold) "<w:b/>" else ""}</w:rPr><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
            // Split very wide selections into readable tables with identical row order.
            val document = buildString {
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                data.summary.forEach { append(p(it)) }
                data.columns.indices.chunked(6).forEachIndexed { part, indices ->
                    if(data.columns.size > 6) append(p("${translate("Столбцы: часть",data.language)} ${part+1}", true))
                    append("""<w:tbl><w:tblPr><w:tblW w:w="14800" w:type="dxa"/><w:tblBorders><w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr><w:tblGrid>""")
                    indices.forEach { append("<w:gridCol w:w=\"${14800/indices.size}\"/>") }; append("</w:tblGrid>")
                    (listOf(data.headers) + data.rows).forEachIndexed { row, cells ->
                        append("<w:tr>"); if(row==0) append("<w:trPr><w:tblHeader/></w:trPr>")
                        indices.forEach { append("<w:tc><w:tcPr><w:tcW w:w=\"${14800/indices.size}\" w:type=\"dxa\"/></w:tcPr>${p(cells[it],row==0)}</w:tc>") }; append("</w:tr>")
                    }
                    append("</w:tbl>"); append(p(""))
                }
                append(p(translate("Итого",data.language),true))
                data.summary.drop(1).forEach { append(p(it)) }
                append("""<w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720"/></w:sectPr></w:body></w:document>""")
            }
            z.part("word/document.xml", document)
        }
    }
}
