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
    private fun texts(view:View):String=(view.contentDescription?.toString() ?: "")+when(view){is TextView->view.text.toString();is ViewGroup->(0 until view.childCount).joinToString(" "){texts(view.getChildAt(it))};else->""}
    private fun matchingText(view:View,label:String):View? {
        if(view.contentDescription?.toString()==label)return view
        if(view is TextView&&(view.text.toString()==label||view.text.toString().endsWith(" $label")))return view
        if(view is ViewGroup)for(i in 0 until view.childCount){matchingText(view.getChildAt(i),label)?.let{return it}}
        return null
    }
    @Test fun placeBothWidgetsThenChooseIndependentEmployersFromSetupButton()=runBlocking {
        val dao=TabelDatabase.get(context).dao()
        val names=listOf("Кафе ${System.nanoTime()%1000}","Офис ${System.nanoTime()%1000}")
        val employers=names.mapIndexed{index,name->dao.saveEmployer(Employer(name=name,color=if(index==0)0xFF1464F4 else 0xFFD44A15))}
        val app=context.applicationContext as TabelApplication;val original=app.settings.state.value;app.settings.update(original.copy(theme="LIGHT"))
        employers.forEach{dao.saveScheduleDay(ScheduleDay(it,java.time.LocalDate.now().toEpochDay(),true,8*60,18*60,30))}
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
            fun awaitText(index:Int,label:String){compose.waitUntil(30000){var found=false;instrumentation.runOnMainSync{found=matchingText(views[index],label)!=null};found}}
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
            // Attach the host views so Android's Chronometer actually runs its own ticks.
            instrumentation.runOnMainSync{
                val container=android.widget.LinearLayout(compose.activity).apply{orientation=android.widget.LinearLayout.VERTICAL}
                val density=context.resources.displayMetrics.density
                views.forEachIndexed{index,view->container.addView(view,android.widget.LinearLayout.LayoutParams((if(index==0)220*density else 340*density).toInt(),(if(index==0)160*density else 340*density).toInt()))}
                compose.activity.addContentView(container,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            fun clickControl(index:Int,label:String):View{
                var clicked:View?=null
                instrumentation.runOnMainSync{
                    var button=matchingText(views[index],label) ?: error("Control button missing: $label")
                    while(!button.isClickable && button.parent is View)button=button.parent as View
                    clicked=button;assertTrue(button.performClick())
                }
                return clicked!!
            }
            clickControl(0,"Начать");awaitText(0,"Подтвердить")
            assertTrue(dao.allShifts().none{it.employerId==employers[0]&&it.end==null})
            clickControl(0,"Подтвердить");awaitText(0,"Завершить")
            compose.onNodeWithText("Отмена").assertDoesNotExist()
            compose.waitUntil(10000){runBlocking{dao.allShifts().count{it.employerId==employers[0]&&it.end==null}==1}}
            WidgetShiftAction().onAction(context,androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIdBy(ids[0]),androidx.glance.action.actionParametersOf(androidx.glance.action.ActionParameters.Key<Long>("employer") to employers[0],androidx.glance.action.ActionParameters.Key<String>("kind") to "start",androidx.glance.action.ActionParameters.Key<Long>("shift") to -1L))
            kotlinx.coroutines.delay(300)
            assertEquals(1,dao.allShifts().count{it.employerId==employers[0]&&it.end==null})
            var initial=""
            instrumentation.runOnMainSync{initial=views[0].findViewById<android.widget.Chronometer>(R.id.widget_clock).text.toString()}
            compose.waitUntil(5000){var changed=false;instrumentation.runOnMainSync{changed=views[0].findViewById<android.widget.Chronometer>(R.id.widget_clock).text.toString()!=initial};changed}
            clickControl(1,"Начать");awaitText(1,"Подтвердить")
            assertTrue(dao.allShifts().none{it.employerId==employers[1]&&it.end==null})
            clickControl(1,"Подтвердить");awaitText(1,"Завершить")
            clickControl(1,"Перерыв");awaitText(1,"Подтвердить");clickControl(1,"Подтвердить");awaitText(1,"Продолжить")
            compose.waitUntil(5000){runBlocking{dao.allShifts().any{it.employerId==employers[1]&&it.pauseStartedAt!=null}}}
            var net="";instrumentation.runOnMainSync{net=views[1].findViewById<android.widget.Chronometer>(R.id.net_clock).text.toString()}
            kotlinx.coroutines.delay(1300)
            instrumentation.runOnMainSync{assertEquals(net,views[1].findViewById<android.widget.Chronometer>(R.id.net_clock).text.toString())}
            clickControl(1,"Продолжить");awaitText(1,"Подтвердить");clickControl(1,"Подтвердить");awaitText(1,"Перерыв")
            assertTrue(dao.allShifts().any{it.employerId==employers[1]&&it.pauseStartedAt==null&&it.timedBreaks!=null})
            instrumentation.runOnMainSync{
                views.forEachIndexed{index,view->
                    val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(bitmap))
                    java.io.File(context.getExternalFilesDir(null),"widget-live-${if(index==0)"compact"else "expanded"}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
            }
            app.settings.update(original.copy(theme="DARK"));refreshWidgets(context)
            compose.waitUntil(10000){var dark=false;instrumentation.runOnMainSync{dark=views[1].findViewById<android.widget.Chronometer>(R.id.widget_clock).currentTextColor==android.graphics.Color.WHITE};dark}
            instrumentation.runOnMainSync{
                views.forEachIndexed{index,view->
                    val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap))
                    java.io.File(context.getExternalFilesDir(null),"widget-live-${if(index==0)"compact"else "expanded"}-dark.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                }
            }
            clickControl(0,"Завершить");awaitText(0,"Подтвердить");clickControl(0,"×");awaitText(0,"Завершить")
            assertTrue(dao.allShifts().any{it.employerId==employers[0]&&it.end==null})
            clickControl(0,"Завершить");awaitText(0,"Подтвердить");clickControl(0,"Подтвердить");awaitText(0,"Начать")
            assertTrue(dao.allShifts().any{it.employerId==employers[1]&&it.end==null})
            clickControl(1,"Завершить");awaitText(1,"Подтвердить");clickControl(1,"Подтвердить");awaitText(1,"Начать")
            assertEquals(employers[0],preferences.getLong("employer_${ids[0]}",-1));assertEquals(employers[1],preferences.getLong("employer_${ids[1]}",-1))
        }finally{
            app.settings.update(original)
            instrumentation.runOnMainSync{host.stopListening()}
            ids.forEach{preferences.edit().remove("employer_$it").commit();host.deleteAppWidgetId(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            employers.forEach{id->dao.allScheduleDays().filter{it.employerId==id}.forEach{dao.deleteScheduleDay(it)};dao.allShifts().filter{it.employerId==id}.forEach{dao.deleteShift(it)};dao.deleteEmptyEmployer(id)}
        }
    }
}
