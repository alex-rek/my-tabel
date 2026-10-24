package ru.mytabel.app.domain

import org.junit.Assert.*
import org.junit.Test
import ru.mytabel.app.data.*
import ru.mytabel.app.reports.*
import ru.mytabel.app.settings.*
import java.time.LocalDate

class UpdateTest {
    private val monday=LocalDate.of(2026,10,19)
    @Test fun scheduleChangesPreservePastAndSupportRotatingCycles(){
        val first=ScheduleRule(1,1,monday.toEpochDay(),monday.toEpochDay(),"1111100")
        val change=ScheduleRule(2,1,monday.plusDays(7).toEpochDay(),monday.plusDays(7).toEpochDay(),"1100")
        assertTrue(Schedules.day(1,monday,listOf(first,change),emptyList()).working)
        assertFalse(Schedules.day(1,monday.plusDays(5),listOf(first,change),emptyList()).working)
        assertTrue(Schedules.day(1,monday.plusDays(7),listOf(first,change),emptyList()).working)
        assertFalse(Schedules.day(1,monday.plusDays(9),listOf(first,change),emptyList()).working)
        val override=ScheduleDay(1,monday.toEpochDay(),false)
        assertFalse(Schedules.day(1,monday,listOf(first,change),listOf(override)).working)
    }
    @Test fun schedulePaidMinutesHandleOvernight(){assertEquals(450,Schedules.paidMinutes(ScheduleDay(1,0,true,22*60,6*60,30)))}
    private fun csvRow():String {
        val c=MutableList(27){""};c[0]="1";c[1]="3.8333";c[2]="2026-10-23";c[5]="17.0";c[8]="note, with \"quotes\"";c[12]="0";c[15]="0";c[18]="100.0";c[23]="10.6667";c[24]="14.5";c[25]="0"
        return c.joinToString(","){if(it.contains(',')||it.contains('"'))"\"${it.replace("\"","\"\"")}\""else it}
    }
    @Test fun legacyCsvUsesDecimalHoursAndPreservesPaymentsAndQuotedComments(){
        val row=LegacyImport.plan("\uFEFF"+csvRow()+"\r\n").rows.single()
        assertEquals(230,row.paidMinutes);assertEquals(640,row.startMinutes);assertEquals(870,row.endMinutes);assertEquals(10000,row.payment);assertEquals("note, with \"quotes\"",row.note)
        assertEquals(row.key,LegacyImport.plan(csvRow()).rows.single().key)
    }
    @Test fun importedPaidTimeCanDifferFromClockTime(){
        val e=Employer(id=1,name="Test",zone="UTC",overtime=false)
        val p=Payroll.calculate(e,listOf(Shift(1,1,0,5*3600000,1700,paidSecondsOverride=6*3600))).single()
        assertEquals(6*3600L,p.paidSeconds);assertEquals(10200,p.grossCents)
    }
    @Test fun backupRoundTripIncludesScheduleHistoryAndSettings(){
        val e=Employer(id=1,name="Test",zone="UTC")
        val snapshot=Snapshot(employers=listOf(e),rules=listOf(ScheduleRule(1,1,0,0)),scheduleDays=listOf(ScheduleDay(1,2,false)),imports=listOf(ImportRecord("1:key",1,1,"note","raw")))
        val settings=AppSettings("en","DARK","PURPLE","EUR")
        val decoded=Backup.decode(Backup.encode(settings,snapshot))
        assertEquals(settings,decoded.settings);assertEquals(snapshot,decoded.data)
    }
    @Test fun reportLanguageCurrencyAndHtmlEscapingAreIndependentOfUi(){
        val e=Employer(id=1,name="<work & job>",zone="UTC",overtime=false)
        val shift=Shift(1,1,0,3600000,1700)
        val report=Reports.create(Snapshot(listOf(e),listOf(shift)),setOf(1),LocalDate.of(1970,1,1),LocalDate.of(1970,1,1),listOf(ReportColumn.DATE,ReportColumn.EMPLOYER,ReportColumn.GROSS),"en","EUR")
        assertEquals("Earned, EUR",report.headers.last());assertTrue(report.summary.first().contains("My Timesheet"));assertTrue(report.summary.any{it.contains("€")})
        val html=Reports.html(report);assertTrue(html.contains("lang=\"en\""));assertTrue(html.contains("&lt;work &amp; job&gt;"));assertFalse(html.contains("<work & job>"));assertTrue(html.contains("<thead>"))
    }
    @Test fun scheduleExportUsesHistoricalRuleAtEachDate(){
        val e=Employer(id=1,name="Test")
        val data=Snapshot(employers=listOf(e),rules=listOf(ScheduleRule(1,1,monday.toEpochDay(),monday.toEpochDay()),ScheduleRule(2,1,monday.plusDays(1).toEpochDay(),monday.toEpochDay(),"0000000")))
        val r=Reports.schedule(data,setOf(1),monday,monday.plusDays(1),"en")
        assertEquals("Workday",r.rows.first()[2]);assertEquals("Day off",r.rows.last()[2]);assertTrue(r.summary[1].contains("1"))
    }
}
