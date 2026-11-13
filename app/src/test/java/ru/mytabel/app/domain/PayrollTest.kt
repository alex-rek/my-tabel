package ru.mytabel.app.domain

import org.junit.Assert.*
import org.junit.Test
import ru.mytabel.app.data.*
import ru.mytabel.app.reports.*
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.time.*
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class PayrollTest {
    private val employer=Employer(id=1,name="Работа",rateCents=2000,zone="UTC")
    private fun shift(id:Long,day:Int,hours:Int,startHour:Int=0,rate:Long=2000)=Shift(id,1,LocalDate.of(2026,10,19).plusDays(day.toLong()).atTime(startHour,0).toInstant(ZoneOffset.UTC).toEpochMilli(),LocalDate.of(2026,10,19).plusDays(day.toLong()).atTime(startHour,0).plusHours(hours.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli(),rate)
    @Test fun dailyOvertimeAndDoubleTime() {
        val p=Payroll.calculate(employer,listOf(shift(1,0,14))).single()
        assertEquals(8*3600L,p.regularSeconds);assertEquals(4*3600L,p.overtimeSeconds);assertEquals(2*3600L,p.doubleSeconds);assertEquals(36000,p.grossCents)
    }
    @Test fun multipleShiftsShareDailyThreshold() {
        val p=Payroll.calculate(employer,listOf(shift(1,0,6),shift(2,0,5,8)))
        assertEquals(2*3600L,p[1].regularSeconds);assertEquals(3*3600L,p[1].overtimeSeconds)
    }
    @Test fun dailyOvertimeIsNotCountedAgainForWeeklyThreshold() {
        val shifts=(0..4).map{shift(it+1L,it,10)}
        val p=Payroll.calculate(employer,shifts)
        assertEquals(40*3600L,p.sumOf{it.regularSeconds});assertEquals(10*3600L,p.sumOf{it.overtimeSeconds})
        val withSaturday=Payroll.calculate(employer,shifts+shift(6,5,8))
        assertEquals(18*3600L,withSaturday.sumOf{it.overtimeSeconds})
    }
    @Test fun seventhDayOnlyWithinConfiguredWorkweek() {
        val p=Payroll.calculate(employer,(0..6).map{shift(it+1L,it,10)})
        assertEquals(0,p.last().regularSeconds);assertEquals(8*3600L,p.last().overtimeSeconds);assertEquals(2*3600L,p.last().doubleSeconds)
        val missing=Payroll.calculate(employer,listOf(shift(1,0,2),shift(2,6,2)))
        assertEquals(2*3600L,missing.last().regularSeconds)
    }
    @Test fun nightShiftSplitsAtWorkdayBoundary() {
        val p=Payroll.calculate(employer,listOf(shift(1,0,10,20))).single()
        assertEquals(10*3600L,p.regularSeconds);assertEquals(0,p.overtimeSeconds)
        val shifted=Payroll.calculate(employer.copy(workdayStartMinutes=12*60),listOf(shift(1,0,10,20))).single()
        assertEquals(8*3600L,shifted.regularSeconds);assertEquals(2*3600L,shifted.overtimeSeconds)
    }
    @Test fun unpaidBreakAndRateSnapshot() {
        val s=shift(1,0,10,rate=1500).copy(breakMinutes=60)
        val p=Payroll.calculate(employer.copy(rateCents=9999),listOf(s)).single()
        assertEquals(9*3600L,p.paidSeconds);assertEquals(14250,p.grossCents)
    }
    @Test fun overtimeOffAndOtherEmployerIgnored() {
        val p=Payroll.calculate(employer.copy(overtime=false),listOf(shift(1,0,14),shift(2,0,10).copy(employerId=2))).single()
        assertEquals(14*3600L,p.regularSeconds);assertEquals(28000,p.grossCents)
    }
    @Test fun activeShiftExcluded() { assertTrue(Payroll.calculate(employer,listOf(shift(1,0,10).copy(end=null))).isEmpty()) }
    @Test fun daylightSavingUsesElapsedTime() {
        val zone=ZoneId.of("America/Los_Angeles")
        val start=LocalDateTime.of(2026,11,1,0,0).atZone(zone).toInstant().toEpochMilli()
        val end=LocalDateTime.of(2026,11,1,4,0).atZone(zone).toInstant().toEpochMilli()
        val p=Payroll.calculate(employer.copy(zone=zone.id),listOf(Shift(1,1,start,end,2000))).single()
        assertEquals(5*3600L,p.paidSeconds)
    }
    @Test fun balanceCarriesPriorPeriodAndSeparatesTaxModes() {
        val s=shift(1,0,8);val pay=Payroll.calculate(employer,listOf(s))
        val payments=listOf(Payment(1,1,LocalDate.of(2026,10,20).toEpochDay(),5000),Payment(2,1,LocalDate.of(2026,10,20).toEpochDay(),1000,"WITHHOLDING"))
        assertEquals(11000,Payroll.balance(listOf(employer),pay,payments,LocalDate.of(2026,11,30)).due)
        assertEquals(10000,Payroll.balance(listOf(employer.copy(taxMode="MANUAL")),pay,payments,LocalDate.of(2026,11,30)).due)
        val estimate=Payroll.balance(listOf(employer.copy(taxMode="ESTIMATE")),pay,payments,LocalDate.of(2026,11,30))
        assertEquals(3200,estimate.estimate);assertEquals(0,estimate.actual);assertEquals(11000,estimate.due)
    }
    @Test fun biweeklyPeriodWorksBeforeAnchor() {
        val e=employer.copy(period="BIWEEK",periodAnchor=LocalDate.of(2026,10,19).toEpochDay())
        val p=Payroll.period(e,LocalDate.of(2026,10,18))
        assertEquals(LocalDate.of(2026,10,5),p.from);assertEquals(LocalDate.of(2026,10,18),p.through)
    }
    @Test fun advanceCarriesAcrossEmptyMonthsAndReportsWithoutBeingCountedTwice() {
        val e=employer.copy(overtime=false)
        fun datedShift(id:Long,date:String,hours:Long):Shift {
            val start=LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            return Shift(id,e.id,start,start+hours*3600000,e.rateCents)
        }
        val shifts=listOf(datedShift(1,"2026-10-19",8),datedShift(2,"2026-12-10",8),datedShift(3,"2027-01-10",8))
        val payments=listOf(Payment(1,1,LocalDate.of(2026,10,20).toEpochDay(),40000,"ADVANCE"),
            Payment(2,2,LocalDate.of(2026,10,20).toEpochDay(),99999,"ADVANCE"),
            Payment(3,1,LocalDate.of(2027,2,1).toEpochDay(),99999,"ADVANCE"))
        val pay=Payroll.calculate(e,shifts)
        val snapshot=Snapshot(listOf(e),shifts,payments)
        for((month,expected) in listOf("2026-11" to -24000L,"2026-12" to -8000L,"2027-01" to 8000L)){
            val range=YearMonth.parse(month)
            val opening=Payroll.balance(listOf(e),pay,payments,range.atDay(1).minusDays(1))
            val period=Payroll.balance(listOf(e),pay,payments,range.atEndOfMonth(),range.atDay(1))
            val closing=Payroll.balance(listOf(e),pay,payments,range.atEndOfMonth())
            assertEquals(expected,closing.due)
            assertEquals(opening.due+period.due,closing.due)
            assertEquals(40000L,closing.received)
            assertEquals(0L,period.received)
            val report=Reports.create(snapshot,setOf(1),range.atDay(1),range.atEndOfMonth(),listOf(ReportColumn.KIND,ReportColumn.DUE,ReportColumn.ADVANCE))
            assertEquals("Входящий остаток",report.rows.first()[0])
            assertEquals(java.math.BigDecimal(expected).movePointLeft(2).toPlainString(),report.rows.last()[1])
            assertTrue(report.rows.all{it[2].isEmpty()})
        }
    }
    @Test fun estimateTotalsMatchRoundedShiftRows() {
        val e=employer.copy(taxMode="ESTIMATE",estimatePercent=20)
        val pay=Payroll.calculate(e,listOf(shift(1,0,1,rate=3),shift(2,1,1,rate=3)))
        val balance=Payroll.balance(listOf(e),pay,emptyList(),LocalDate.of(2026,10,31))
        assertEquals(2,balance.estimate)
    }
    @Test fun reportPaymentsAppearOnceAndNarrowDateRangeStillUsesWeeklyContext() {
        val shifts=(0..5).map{shift(it+1L,it,8)}
        val data=Snapshot(listOf(employer),shifts,listOf(Payment(1,1,LocalDate.of(2026,10,24).toEpochDay(),1000)))
        val report=Reports.create(data,setOf(1),LocalDate.of(2026,10,24),LocalDate.of(2026,10,24),ReportColumn.entries.toList())
        assertEquals(3,report.rows.size)
        val ot=report.columns.indexOf(ReportColumn.OVERTIME);val salary=report.columns.indexOf(ReportColumn.SALARY)
        assertEquals("8",report.rows[1][ot]);assertEquals(1,report.rows.count{it[salary]=="10.00"})
        assertFalse(report.columns.contains(ReportColumn.ACTUAL));assertFalse(report.columns.contains(ReportColumn.ESTIMATE))
    }
    @Test fun exportsAreWellFormedOfficePackagesWithEscapedTextAndNumericCells() {
        val report=ReportData(listOf(ReportColumn.EMPLOYER,ReportColumn.GROSS),listOf(listOf("A&B <work>","125.50"),listOf("=HYPERLINK(1)","0.00")),listOf("Итог: 125.50"))
        for(word in listOf(false,true)) {
            val out=ByteArrayOutputStream();if(word)Reports.docx(report,out)else Reports.xlsx(report,out)
            val parts=mutableMapOf<String,String>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip -> var entry=zip.nextEntry;while(entry!=null){parts[entry.name]=zip.readBytes().toString(Charsets.UTF_8);entry=zip.nextEntry} }
            assertTrue(parts.containsKey("[Content_Types].xml"));assertTrue(parts.containsKey("_rels/.rels"))
            parts.values.forEach { DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}.newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray(Charsets.UTF_8))) }
            val content=parts[if(word)"word/document.xml" else "xl/worksheets/sheet1.xml"]!!
            assertTrue(content.contains("A&amp;B &lt;work&gt;"))
            if(!word){assertTrue(content.contains("<v>125.50</v>"));assertFalse(content.contains("<f>"))}
        }
    }
}
