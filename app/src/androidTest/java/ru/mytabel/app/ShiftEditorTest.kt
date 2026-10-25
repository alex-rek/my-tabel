package ru.mytabel.app
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import ru.mytabel.app.ui.*
import ru.mytabel.app.data.*
import java.time.LocalDate
class ShiftEditorTest {
    @get:Rule val compose=createComposeRule()
    @Test fun editingRateKeepsMeasuredBreakIntervals(){
        val original=Shift(1,1,1250,7201250,2000,20,timedBreaks="[[1001250,2001250],[7001250,7201250]]")
        var saved:Shift?=null
        compose.setContent{MaterialTheme{ShiftForm(listOf(Employer(id=1,name="Editor",zone="UTC")),original,1,LocalDate.of(1970,1,1),{}, {saved=it},null)}}
        compose.onNode(hasSetTextAction() and hasText("20.00")).performScrollTo().performTextReplacement("21.35")
        compose.onNodeWithText("Сохранить").performClick()
        assertNotNull(saved);assertEquals(2135L,saved!!.rateCents);assertEquals(original.timedBreaks,saved!!.timedBreaks);assertEquals(original.start,saved!!.start);assertEquals(original.end,saved!!.end)
    }
    @Test fun manualEntryUsesSeparateNumericTimeFields(){
        var saved:Shift?=null
        compose.setContent{MaterialTheme{ShiftForm(listOf(Employer(id=1,name="Editor")),null,1,LocalDate.of(2026,10,25),{}, {saved=it},null)}}
        compose.onAllNodes(hasSetTextAction() and hasText("Часы"))[0].performScrollTo().performTextReplacement("10")
        compose.onAllNodes(hasSetTextAction() and hasText("Минуты"))[0].performScrollTo().performTextReplacement("40")
        compose.onAllNodes(hasSetTextAction() and hasText("Часы"))[1].performScrollTo().performTextReplacement("14")
        compose.onAllNodes(hasSetTextAction() and hasText("Минуты"))[1].performScrollTo().performTextReplacement("30")
        compose.onNodeWithText("Сохранить").performClick()
        assertNotNull(saved);assertEquals(230*60000L,saved!!.end!!-saved!!.start)
    }
}