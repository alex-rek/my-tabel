package ru.mytabel.app

import android.appwidget.*
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.mytabel.app.data.*
import ru.mytabel.app.widgets.*

@RunWith(AndroidJUnit4::class)
class WidgetPlacementTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun texts(view:View):String=when(view){is TextView->view.text.toString();is ViewGroup->(0 until view.childCount).joinToString(" "){texts(view.getChildAt(it))};else->""}
    private fun matchingText(view:View,label:String):View? {
        if(view is TextView&&view.text.toString()==label)return view
        if(view is ViewGroup)for(i in 0 until view.childCount){matchingText(view.getChildAt(i),label)?.let{return it}}
        return null
    }
    @Test fun placeBothWidgetsThenChooseIndependentEmployersFromSetupButton()=runBlocking {
        val dao=TabelDatabase.get(context).dao()
        val names=listOf("Placement compact ${System.nanoTime()}","Placement expanded ${System.nanoTime()}")
        val employers=names.map{dao.saveEmployer(Employer(name=it))}
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,731032);val ids=mutableListOf<Int>();val views=mutableListOf<AppWidgetHostView>()
        val preferences=context.getSharedPreferences("widgets",0)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try{
            listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).forEach{receiver->
                val info=manager.installedProviders.single{it.provider==ComponentName(context,receiver)}
                assertTrue(info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0)
                assertNotNull(context.packageManager.resolveActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setPackage(context.packageName),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
                val id=host.allocateAppWidgetId();ids.add(id)
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,info.provider,Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,240);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,260)}))
                preferences.edit().remove("employer_$id").commit()
                instrumentation.runOnMainSync{views.add(host.createView(context,id,info))}
                context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,intArrayOf(id)))
            }
            instrumentation.runOnMainSync{host.startListening()}
            fun awaitText(index:Int,label:String){compose.waitUntil(30000){var found=false;instrumentation.runOnMainSync{found=texts(views[index]).contains(label)};found}}
            ids.indices.forEach{index->
                awaitText(index,"Настроить")
                instrumentation.runOnMainSync{
                    var button=matchingText(views[index],"Настроить") ?: error("Setup button missing")
                    while(!button.isClickable && button.parent is View)button=button.parent as View
                    assertTrue("Setup action not clickable",button.performClick())
                }
                compose.waitUntil(15000){compose.onAllNodesWithText("Работодатель для виджета").fetchSemanticsNodes().isNotEmpty()}
                val name=names[index]
                compose.onNodeWithText(name).performScrollTo().performClick()
                compose.waitUntil(15000){preferences.getLong("employer_${ids[index]}",-1)==employers[index]}
                awaitText(index,name)
                compose.waitUntil(15000){compose.onAllNodesWithText("Работодатель для виджета").fetchSemanticsNodes().isEmpty()}
            }
            fun clickControl(label:String){
                instrumentation.runOnMainSync{
                    var button=matchingText(views[0],label) ?: error("Control button missing")
                    while(!button.isClickable && button.parent is View)button=button.parent as View
                    assertTrue(button.performClick())
                }
                compose.waitUntil(15000){compose.onAllNodesWithText("Отмена").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText(label).performClick()
                compose.waitUntil(15000){compose.onAllNodesWithText("Отмена").fetchSemanticsNodes().isEmpty()}
            }
            clickControl("Начать работу");awaitText(0,"Закончить работу")
            clickControl("Закончить работу");awaitText(0,"Начать работу")
            assertEquals(employers[0],preferences.getLong("employer_${ids[0]}",-1));assertEquals(employers[1],preferences.getLong("employer_${ids[1]}",-1))
        }finally{
            instrumentation.runOnMainSync{host.stopListening()}
            ids.forEach{preferences.edit().remove("employer_$it").commit();host.deleteAppWidgetId(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            employers.forEach{id->dao.allShifts().filter{it.employerId==id}.forEach{dao.deleteShift(it)};dao.deleteEmptyEmployer(id)}
        }
    }
}