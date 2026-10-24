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

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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
