package ru.mytabel.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import ru.mytabel.app.settings.AppSettings
import java.time.*

@RunWith(AndroidJUnit4::class)
class MaintenanceTest {
    private lateinit var db:TabelDatabase
    private lateinit var repo:Repository
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(context,TabelDatabase::class.java).build();repo=Repository(db)}
    @After fun close(){db.close()}
    private fun plan():LegacyImport.Plan {
        val c=MutableList(27){""};c[0]="1";c[1]="4";c[2]="2026-10-23";c[5]="17.35";c[18]="100";c[23]="10";c[24]="14";c[25]="0"
        return LegacyImport.plan(c.joinToString(","))
    }
    @Test fun undoOnlyRemovesRowsCreatedByTheSelectedImport()=runBlocking<Unit>{
        LegacyImport.apply(repo,plan(),null,"Imported")
        val original=repo.snapshotNow();val e=original.employers.single();val imported=original.imports.single()
        assertNotNull(imported.shiftId);assertNotNull(imported.paymentId)
        val manualShift=repo.dao.saveShift(original.shifts.single().copy(id=0,comment="manual",paidSecondsOverride=null))
        val manualPayment=repo.dao.savePayment(original.payments.single().copy(id=0,comment="manual"))
        val preview=Maintenance.previewUndo(repo,Maintenance.batchKey(imported));assertEquals(1,preview.shifts);assertEquals(1,preview.payments);assertEquals(0,preview.unresolved)
        Maintenance.undoImport(repo,Maintenance.batchKey(imported))
        assertEquals(manualShift,repo.dao.allShifts().single().id);assertEquals(manualPayment,repo.dao.allPayments().single().id)
        assertTrue(repo.dao.allImports().isEmpty());assertEquals(e,repo.dao.allEmployers().single())
        assertEquals(1,LegacyImport.apply(repo,plan(),e.id,"unused").first)
    }
    @Test fun legacyVersionTwoImportIsLinkedBeforeRollback()=runBlocking<Unit>{
        LegacyImport.apply(repo,plan(),null,"Imported")
        val record=repo.dao.allImports().single();repo.dao.updateImport(record.copy(batchId=null,importedAt=0,shiftId=null,paymentId=null))
        val key=Maintenance.batchKey(repo.dao.allImports().single())
        val preview=Maintenance.previewUndo(repo,key);assertEquals(0,preview.unresolved);assertEquals(1,preview.shifts);assertEquals(1,preview.payments)
        Maintenance.undoImport(repo,key);assertTrue(repo.dao.allShifts().isEmpty());assertTrue(repo.dao.allPayments().isEmpty());assertTrue(repo.dao.allImports().isEmpty())
    }
    @Test fun ambiguousLegacyRollbackKeepsAllFinancialRows()=runBlocking<Unit>{
        LegacyImport.apply(repo,plan(),null,"Imported")
        val record=repo.dao.allImports().single();repo.dao.updateImport(record.copy(batchId=null,shiftId=null,paymentId=null))
        repo.dao.saveShift(repo.dao.allShifts().single().copy(id=0))
        val key=Maintenance.batchKey(repo.dao.allImports().single());assertEquals(1,Maintenance.previewUndo(repo,key).unresolved)
        try{Maintenance.undoImport(repo,key);fail("Ambiguous rollback accepted")}catch(_:IllegalArgumentException){}
        assertEquals(2,repo.dao.allShifts().size);assertEquals(1,repo.dao.allPayments().size)
    }
    @Test fun backupRemapsImportLinksWhenRestoredBesideOtherEmployers()=runBlocking<Unit>{
        LegacyImport.apply(repo,plan(),null,"Imported")
        val backup=Backup.decode(Backup.encode(AppSettings(weekStart=7,hour24=false),repo.snapshotNow()))
        val target=Room.inMemoryDatabaseBuilder(context,TabelDatabase::class.java).build();val r=Repository(target)
        try{
            val id=r.dao.saveEmployer(Employer(name="Manual",zone="UTC"));val manual=r.dao.saveShift(Shift(employerId=id,start=0,end=3600000,rateCents=1000))
            Backup.restore(r,backup)
            val imported=r.dao.allImports().single();assertNotEquals(id,imported.employerId);assertNotEquals(manual,imported.shiftId)
            Maintenance.undoImport(r,Maintenance.batchKey(imported))
            assertEquals(manual,r.dao.allShifts().single().id);assertTrue(r.dao.allPayments().isEmpty())
        }finally{target.close()}
    }
    @Test fun periodCleanupKeepsActiveShiftsOtherEmployersAndOutsideDates()=runBlocking<Unit>{
        val first=repo.dao.saveEmployer(Employer(name="First",zone="UTC"));val other=repo.dao.saveEmployer(Employer(name="Other",zone="UTC"));val date=LocalDate.of(2026,10,23)
        fun shift(id:Long,day:LocalDate,end:Boolean=true)=day.atTime(9,0).toInstant(ZoneOffset.UTC).toEpochMilli().let{Shift(employerId=id,start=it,end=if(end)it+3600000 else null,rateCents=1700)}
        repo.dao.saveShift(shift(first,date));val outside=repo.dao.saveShift(shift(first,date.minusDays(1)));val unrelated=repo.dao.saveShift(shift(other,date));val active=repo.dao.saveShift(shift(first,date,false))
        repo.dao.savePayment(Payment(employerId=first,day=date.toEpochDay(),cents=1000));val keepPayment=repo.dao.savePayment(Payment(employerId=other,day=date.toEpochDay(),cents=1000))
        repo.dao.saveRule(ScheduleRule(employerId=first,effectiveFrom=date.minusDays(20).toEpochDay(),anchor=date.toEpochDay(),pattern="1111111"))
        val result=Maintenance.clearPeriod(repo,setOf(first),date,date,true)
        assertEquals(1,result.shifts);assertEquals(1,result.payments)
        assertEquals(setOf(outside,unrelated,active),repo.dao.allShifts().map{it.id}.toSet());assertEquals(keepPayment,repo.dao.allPayments().single().id)
        val snapshot=repo.snapshotNow();assertFalse(Schedules.day(first,date,snapshot.rules,snapshot.scheduleDays).working);assertTrue(Schedules.day(first,date.plusDays(1),snapshot.rules,snapshot.scheduleDays).working)
    }
    @Test fun weekEditsAllowSevenDaysBackAndRejectOlderChangesAtomically()=runBlocking<Unit>{
        val e=repo.dao.saveEmployer(Employer(name="Week"));val today=LocalDate.of(2026,10,24)
        val allowed=ScheduleDay(e,today.minusDays(7).toEpochDay(),true)
        repo.saveWeek(listOf(allowed),today);assertEquals(allowed,repo.dao.allScheduleDays().single())
        try{repo.saveWeek(listOf(allowed.copy(working=false),allowed.copy(day=today.minusDays(8).toEpochDay())),today);fail("Old day changed")}catch(_:IllegalArgumentException){}
        assertEquals(allowed,repo.dao.allScheduleDays().single())
    }
}
