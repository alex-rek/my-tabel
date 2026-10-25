package ru.mytabel.app.domain

import org.junit.Test
import org.junit.Assert.*
import ru.mytabel.app.data.*
import ru.mytabel.app.reports.*
import ru.mytabel.app.settings.*
import java.time.*

class WeekAndTimeTest {
    @Test fun customWeekStartReordersDaysAndHandlesMonthBoundary(){
        val date=LocalDate.of(2026,11,1)
        assertEquals(listOf(7,1,2,3,4,5,6),CalendarTools.weekdays(7))
        assertEquals(0,CalendarTools.offset(date,7));assertEquals(6,CalendarTools.offset(date,1))
        assertEquals(date,CalendarTools.weekStart(date,7));assertEquals(date.minusDays(6),CalendarTools.weekStart(date,1))
    }
    @Test fun swipeDirectionAndThresholdAreCorrect(){assertEquals(1,CalendarTools.monthStep(-100f,56f));assertEquals(-1,CalendarTools.monthStep(100f,56f));assertEquals(0,CalendarTools.monthStep(55f,56f))}
    @Test fun editableScheduleWindowIncludesExactlyOneWeekBack(){val today=LocalDate.of(2026,10,24);assertTrue(CalendarTools.editable(today.minusDays(7),today));assertFalse(CalendarTools.editable(today.minusDays(8),today));assertTrue(CalendarTools.editable(today.plusYears(1),today))}
    @Test fun twelveHourInputHandlesNoonMidnightAndRejectsInvalidFields(){
        assertEquals("00:30",TimeFormats.encodeInput("12","30",false,false));assertEquals("12:30",TimeFormats.encodeInput("12","30",true,false));assertEquals("14:30",TimeFormats.encodeInput("2","30",true,false))
        assertNull(TimeFormats.encodeInput("0","30",false,false));assertNull(TimeFormats.encodeInput("13","30",false,false));assertNull(TimeFormats.encodeInput("2","60",false,false))
        assertEquals("00:30",TimeFormats.encodeInput("0","30",false,true));assertEquals("12:00 AM",TimeFormats.time(0,false));assertEquals("12:00 PM",TimeFormats.time(720,false));assertEquals("23:59",TimeFormats.time(1439,true))
    }
    @Test fun versionTwoBackupGetsCompatibleNewPreferenceDefaults(){
        val old=Backup.encode(AppSettings(),Snapshot()).replace(Regex(",?\\s*\"weekStart\"\\s*:\\s*1"),"").replace(Regex(",?\\s*\"hour24\"\\s*:\\s*true"),"")
        val backup=Backup.decode(old);assertEquals(1,backup.settings.weekStart);assertTrue(backup.settings.hour24)
    }
    @Test fun twelveHourReportAndScheduleUseAmPm(){
        val e=Employer(1,"Test",zone="UTC");val shift=Shift(1,1,0,3600000,1700)
        val r=Reports.create(Snapshot(listOf(e),listOf(shift)),setOf(1),LocalDate.ofEpochDay(0),LocalDate.ofEpochDay(0),listOf(ReportColumn.START,ReportColumn.END),hour24=false)
        assertEquals("12:00 AM",r.rows.last()[0]);assertTrue(r.rows.last()[1].endsWith("1:00 AM"))
        val s=Snapshot(employers=listOf(e),scheduleDays=listOf(ScheduleDay(1,0,true,14*60,22*60)))
        val plan=Reports.schedule(s,setOf(1),LocalDate.ofEpochDay(0),LocalDate.ofEpochDay(0),hour24=false)
        assertEquals("2:00 PM",plan.rows.single()[3]);assertEquals("10:00 PM",plan.rows.single()[4])
    }
}
