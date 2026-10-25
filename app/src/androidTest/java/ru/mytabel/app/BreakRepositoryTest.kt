package ru.mytabel.app
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
@RunWith(AndroidJUnit4::class)
class BreakRepositoryTest {
    @Test fun repeatedPauseResumeAndStopDuringBreakKeepExactTotals()=runBlocking{
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,TabelDatabase::class.java).build()
        try{
            val r=Repository(db);val e=r.dao.saveEmployer(Employer(name="Test",zone="UTC",breakMinutes=60,overtime=false))
            val id=r.dao.saveShift(Shift(employerId=e,start=0,rateCents=3600,breakMinutes=60))
            r.pause(e,id,true,3600000);r.pause(e,id,true,3700000)
            assertEquals(3600000L,r.dao.allShifts().single().pauseStartedAt)
            r.pause(e,id,false,4500000);r.pause(e,id,false,4600000)
            r.pause(e,id,true,5000000);r.stop(e,6000000)
            val s=r.dao.allShifts().single();assertNull(s.pauseStartedAt);assertEquals(1900,Breaks.seconds(s));assertEquals(4100,Breaks.worked(s))
            assertEquals(4100L,Payroll.calculate(r.dao.allEmployers().single(),listOf(s)).single().grossCents)
        }finally{db.close()}
    }
}
