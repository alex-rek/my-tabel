package ru.mytabel.app.domain
import org.junit.Test
import org.junit.Assert.*
import ru.mytabel.app.data.*
import ru.mytabel.app.settings.AppSettings
import java.time.*
class BreaksTest {
    @Test fun actualBreaksReplaceDefaultAndPreserveWorkdayAllocation(){
        val start=Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
        val s=Shift(1,1,start,start+20*3600000,2000,60,timedBreaks=Breaks.encode(listOf(BreakSpan(start+7*3600000,start+9*3600000))))
        val pay=Payroll.calculate(Employer(id=1,name="Test",zone="UTC"),listOf(s)).single()
        assertEquals(18*3600L,pay.paidSeconds);assertEquals(15*3600L,pay.regularSeconds);assertEquals(3*3600L,pay.overtimeSeconds)
    }
    @Test fun netTimeFreezesWhileBreakTimeGrows(){
        val s=Shift(1,1,0,rateCents=2000,timedBreaks="[]",pauseStartedAt=3600000)
        assertEquals(3600,Breaks.worked(s,4500000));assertEquals(3600,Breaks.worked(s,4800000));assertEquals(1200,Breaks.seconds(s,4800000))
    }
    @Test fun measuredSecondsSurviveBackupIncludingActivePause(){
        val s=Shift(1,1,0,rateCents=2000,timedBreaks="[[1000,4500]]",pauseStartedAt=7000)
        val data=Snapshot(employers=listOf(Employer(id=1,name="Test")),shifts=listOf(s))
        assertEquals(s,Backup.decode(Backup.encode(AppSettings(),data)).data.shifts.single())
    }
    @Test fun resumeAccumulatesMultipleBreaks(){
        val first=Breaks.resume(Shift(1,1,0,rateCents=100,timedBreaks="[]",pauseStartedAt=1000),6000)
        val second=Breaks.resume(first.copy(pauseStartedAt=10000),17000)
        assertEquals(12,Breaks.seconds(second,20000));assertEquals(8,Breaks.worked(second,20000));assertNull(second.pauseStartedAt)
    }
    @Test fun overlappingBreaksAreRejected(){
        assertThrows(IllegalArgumentException::class.java){Breaks.validate(Shift(1,1,0,20000,100,timedBreaks="[[1000,9000],[5000,10000]]"))}
    }
    @Test fun overnightPlanUsesShiftStartDateAndEmployerZone(){
        val e=Employer(id=1,name="Night",zone="America/Los_Angeles")
        val date=LocalDate.of(2026,10,31);val start=date.atTime(22,0).atZone(ZoneId.of(e.zone)).toInstant().toEpochMilli()
        val plan=ShiftPlans.find(e,Shift(1,1,start,rateCents=2000),start+8*3600000,emptyList(),listOf(ScheduleDay(1,date.toEpochDay(),true,22*60,6*60,30)))!!
        assertEquals(9*3600000L,plan.through-plan.from);assertEquals(30600L,plan.paidSeconds)
        assertEquals(0f,plan.progress(start-1000));assertEquals(1f,plan.progress(plan.through+1000))
    }
    @Test fun subsecondPauseBoundariesKeepPayrollAndLiveNetConsistent(){
        val s=Shift(1,1,250,10250,3600,timedBreaks="[[1100,2450],[3700,5800]]")
        val paid=Payroll.calculate(Employer(id=1,name="Test",zone="UTC",overtime=false),listOf(s)).single().paidSeconds
        assertEquals(Breaks.worked(s),paid);assertEquals(10L,paid+Breaks.seconds(s))
    }
    @Test fun noWorkingDayProducesNoPlan(){assertNull(ShiftPlans.find(Employer(id=1,name="Off"),null,0,emptyList(),emptyList()))}
}
