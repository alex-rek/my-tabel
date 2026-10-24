package ru.mytabel.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import java.io.File
import java.time.*
import com.google.gson.JsonParser

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsLanguageAndCurrencyAreSaved(){
        val app=context.applicationContext as TabelApplication;val original=app.settings.state.value
        try{
            compose.onNodeWithText("Настройки").performClick()
            compose.onNodeWithText("English").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("EUR").performScrollTo().performClick()
            assertEquals("en",app.settings.state.value.language);assertEquals("EUR",app.settings.state.value.currency)
            assertEquals(app.settings.state.value,ru.mytabel.app.settings.SettingsStore(context).state.value)
            capture("settings-phone.png")
        }finally{app.settings.update(original)}
    }

    @Test fun calendarDateOpensEditorWithSeparateNumericHoursAndMinutes(){
        val dao=TabelDatabase.get(context).dao();var employer=0L
        runBlocking{employer=dao.saveEmployer(Employer(name="Time entry regression",rateCents=1735))}
        try{
            compose.waitUntil(15000){compose.onAllNodesWithText("Time entry regression").fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodesWithText("Time entry regression")[0].performClick()
            compose.onNodeWithContentDescription("day-${LocalDate.now()}").performScrollTo().performClick()
            compose.onNodeWithText("Добавить время").performClick()
            compose.onAllNodes(hasSetTextAction() and hasText("Часы"))[0].performScrollTo().performTextReplacement("10")
            compose.onAllNodes(hasSetTextAction() and hasText("Минуты"))[0].performScrollTo().performTextReplacement("40")
            compose.onAllNodes(hasSetTextAction() and hasText("Часы"))[1].performScrollTo().performTextReplacement("14")
            compose.onAllNodes(hasSetTextAction() and hasText("Минуты"))[1].performScrollTo().performTextReplacement("30")
            capture("time-entry-phone.png")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10000){runBlocking{dao.allShifts().any{it.employerId==employer}}}
            val shift=runBlocking{dao.allShifts().single{it.employerId==employer}}
            assertEquals(230*60000L,shift.end!!-shift.start);assertEquals(1735,shift.rateCents)
        }finally{runBlocking{dao.allShifts().filter{it.employerId==employer}.forEach{dao.deleteShift(it)};dao.deleteEmptyEmployer(employer)}}
    }

    @Test fun importAndRestoreAreIdempotentAndPreserveSchedule()=runBlocking<Unit>{
        val db=Room.inMemoryDatabaseBuilder(context,TabelDatabase::class.java).build();val repo=Repository(db)
        try{
            val columns=MutableList(27){""};columns[0]="1";columns[1]="4";columns[2]="2026-10-23";columns[5]="17.35";columns[18]="100";columns[23]="10";columns[24]="14";columns[25]="0"
            val plan=LegacyImport.plan(columns.joinToString(","))
            assertEquals(1,LegacyImport.apply(repo,plan,null,"Imported").first)
            val id=repo.dao.allEmployers().single().id
            assertEquals(1,LegacyImport.apply(repo,plan,id,"Ignored").second)
            repo.dao.saveRule(ScheduleRule(employerId=id,effectiveFrom=0,anchor=0))
            val before=repo.snapshotNow();val backup=Backup.decode(Backup.encode(ru.mytabel.app.settings.AppSettings(),before))
            Backup.restore(repo,backup)
            assertEquals(before,repo.snapshotNow())
            assertEquals("ADVANCE",repo.dao.allPayments().single().kind)
            assertEquals(1735,repo.dao.allShifts().single().rateCents)
        }finally{db.close()}
    }

    @Test fun startButtonDoesNotRecurseAndFinishesExactlyOneShift(){
        val dao=TabelDatabase.get(context).dao();var employer=0L
        runBlocking{employer=dao.saveEmployer(Employer(name="Timer regression"))}
        try{
            compose.waitUntil(15000){compose.onAllNodesWithText("Timer regression").fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodesWithText("Timer regression")[0].performClick()
            compose.onNodeWithText("Начать работу").performScrollTo().performClick()
            compose.waitUntil(10000){runBlocking{dao.allShifts().count{it.employerId==employer&&it.end==null}==1}}
            compose.onNodeWithText("Закончить работу").performScrollTo().performClick()
            compose.waitUntil(10000){runBlocking{dao.allShifts().count{it.employerId==employer&&it.end!=null}==1}}
            compose.onNodeWithText("Работа").performClick();compose.onNodeWithText("Рабочие места").assertIsDisplayed()
        }finally{runBlocking{dao.allShifts().filter{it.employerId==employer}.forEach{dao.deleteShift(it)};dao.deleteEmptyEmployer(employer)}}
    }
    @Test fun upgradeKeepsVersionOneShiftsAndRates()=runBlocking<Unit>{
        val name="migration-fixture.db";context.deleteDatabase(name)
        val schema=InstrumentationRegistry.getInstrumentation().context.assets.open("ru.mytabel.app.data.TabelDatabase/1.json").bufferedReader().use{JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database")}
        context.openOrCreateDatabase(name,0,null).use{db->
            schema.getAsJsonArray("entities").forEach{entry->val e=entry.asJsonObject;val table=e.get("tableName").asString;db.execSQL(e.get("createSql").asString.replace("\${TABLE_NAME}",table));e.getAsJsonArray("indices")?.forEach{db.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}",table))}}
            db.execSQL("INSERT INTO employers VALUES(1,'Existing',0xFF55765B,2050,1,0,'MONTH',0,0,1,8,12,40,1,150,200,'NONE',20,'UTC')")
            db.execSQL("INSERT INTO shifts VALUES(1,1,1000,3601000,2050,0,'migration')")
            db.version=1
        }
        val db=Room.databaseBuilder(context,TabelDatabase::class.java,name).addMigrations(MIGRATION_1_2).build()
        try{assertEquals(2050,db.dao().allEmployers().single().rateCents);assertEquals(2050,db.dao().allShifts().single().rateCents);assertNull(db.dao().allShifts().single().paidSecondsOverride);assertTrue(db.dao().allRules().isEmpty())}finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun roomPersistsConcurrentEmployerTimersAndRejectsOverlap() = runBlocking<Unit> {
        val name="integration-test.db"
        context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,TabelDatabase::class.java,name).build()
        val repo=Repository(db)
        val first=repo.dao.saveEmployer(Employer(name="Первый"));val second=repo.dao.saveEmployer(Employer(name="Второй"))
        coroutineScope { listOf(first,second).map{ id->async {repo.start(id)} }.awaitAll() }
        assertEquals(2,repo.dao.allShifts().count{it.end==null})
        try {repo.start(first);fail("Duplicate active shift accepted")}catch(_:IllegalArgumentException){}
        db.close()
        db=Room.databaseBuilder(context,TabelDatabase::class.java,name).build()
        val restored=Repository(db)
        assertEquals(2,restored.dao.allShifts().count{it.end==null})
        restored.stop(first)
        assertEquals(1,restored.dao.allShifts().count{it.end==null})
        assertNotNull(restored.dao.allShifts().first{it.employerId==first}.end)
        db.close();context.deleteDatabase(name)
    }

    @Test fun calendarAndPaymentNavigationOnDevice() {
        val dao=TabelDatabase.get(context).dao()
        var employerId=0L
        runBlocking {
            employerId=dao.saveEmployer(Employer(name="Студия • тест",rateCents=2500,zone=ZoneId.systemDefault().id))
            val start=LocalDate.now().atTime(9,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dao.saveShift(Shift(employerId=employerId,start=start,end=start+9*3600000L,rateCents=2500,breakMinutes=30,comment="Тест календаря"))
        }
        try {
            compose.waitUntil(15000){compose.onAllNodesWithText("Студия • тест").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Мой табель").assertIsDisplayed()
            compose.onNodeWithContentDescription("Добавить смену").assertIsDisplayed()
            capture("calendar-phone.png")
            compose.onNodeWithText("Выплаты").performClick()
            compose.onNodeWithText("История операций").assertExists()
            compose.onNodeWithText("Отчёты").performClick()
            compose.onNodeWithText("Ваш отчёт").assertIsDisplayed()
            capture("reports-phone.png")
            compose.onNodeWithText("Работа").performClick()
            compose.onNodeWithText("Рабочие места").assertIsDisplayed()
        } finally {
            runBlocking {dao.allShifts().filter{it.employerId==employerId}.forEach{dao.deleteShift(it)};dao.deleteEmptyEmployer(employerId)}
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500) // Let the emulator present the frame before UiAutomation captures it.
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),name).outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
        screenshot.recycle()
    }
}
