package ru.mytabel.app

import android.appwidget.*
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mytabel.app.data.*
import ru.mytabel.app.widgets.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun widgetActionsRequireFreshConfirmationAndCanBeCancelled()=runBlocking {
        val repository=Repository(TabelDatabase.get(context))
        val employer=repository.dao.saveEmployer(Employer(name="Widget confirmation regression"))
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,731033)
        val preferences=context.getSharedPreferences("widgets",0);val ids=mutableListOf<Int>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try{
            instrumentation.runOnMainSync{host.startListening()}
            for(receiver in listOf(CompactReceiver::class.java,ExpandedReceiver::class.java)){
                val id=host.allocateAppWidgetId();ids.add(id)
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,receiver)))
                preferences.edit().putLong("employer_$id",employer).commit();refreshWidget(context,id)
                lateinit var view:AppWidgetHostView
                instrumentation.runOnMainSync{view=host.createView(context,id,manager.getAppWidgetInfo(id))}
                suspend fun awaitLabel(label:String){
                    val deadline=System.currentTimeMillis()+15000
                    while(System.currentTimeMillis()<deadline){var found=false;instrumentation.runOnMainSync{found=text(view).contains(label)};if(found)return;kotlinx.coroutines.delay(100)}
                    fail("Missing widget label: $label")
                }
                val glanceId=androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIdBy(id)
                suspend fun action(kind:String,shift:Long=-1L,confirmed:Boolean=false){
                    WidgetShiftAction().onAction(context,glanceId,androidx.glance.action.actionParametersOf(
                        androidx.glance.action.ActionParameters.Key<Long>("employer") to employer,
                        androidx.glance.action.ActionParameters.Key<String>("kind") to kind,
                        androidx.glance.action.ActionParameters.Key<Long>("shift") to shift,
                        androidx.glance.action.ActionParameters.Key<Boolean>("confirmed") to confirmed))
                }
                suspend fun active()=repository.dao.allShifts().singleOrNull{it.employerId==employer&&it.end==null}
                action("start",confirmed=true);assertNull(active())
                action("start");assertNull(active())
                action("cancel");action("start",confirmed=true);assertNull(active())
                action("start")
                androidx.glance.appwidget.state.updateAppWidgetState(context,glanceId){it[androidx.datastore.preferences.core.longPreferencesKey("pending_until")]=0L}
                action("start",confirmed=true);assertNull(active())
                action("start");awaitLabel("Начать смену?");awaitLabel("Подтвердить")
                instrumentation.runOnMainSync{
                    fun find(node:View):View? {
                        if(node is TextView&&node.text.toString()=="Подтвердить")return node
                        if(node is ViewGroup)for(i in 0 until node.childCount)find(node.getChildAt(i))?.let{return it}
                        return null
                    }
                    var button=find(view) ?: error("Confirmation button missing")
                    while(!button.isClickable&&button.parent is View)button=button.parent as View
                    assertTrue(button.performClick())
                }
                awaitLabel("Завершить")
                val shift=active()!!
                action("stop",shift.id);assertNotNull(active())
                action("cancel");action("stop",shift.id,true);assertNotNull(active())
                action("pause",shift.id);assertNull(active()!!.pauseStartedAt)
                action("pause",shift.id,true);assertNotNull(active()!!.pauseStartedAt)
                action("resume",shift.id);assertNotNull(active()!!.pauseStartedAt)
                action("resume",shift.id,true);assertNull(active()!!.pauseStartedAt)
                action("stop",shift.id);action("stop",shift.id,true);assertNull(active())
                action("start",confirmed=true);assertNull(active())
            }
        }finally{
            instrumentation.runOnMainSync{host.stopListening()}
            ids.forEach{preferences.edit().remove("employer_$it").commit();host.deleteAppWidgetId(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            repository.dao.allShifts().filter{it.employerId==employer}.forEach{repository.dao.deleteShift(it)}
            repository.dao.deleteEmptyEmployer(employer)
        }
    }
    private fun text(view:View):String=(view.contentDescription?.toString() ?: "")+when(view){is TextView->view.text.toString();is ViewGroup->(0 until view.childCount).joinToString(" "){text(view.getChildAt(it))};else->""}
    @Test fun bothPickerPreviewsHaveImagesAndInflatableLayouts(){
        val manager=AppWidgetManager.getInstance(context)
        listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).forEachIndexed{index,receiver->
            val info=manager.installedProviders.single{it.provider==ComponentName(context,receiver)}
            assertTrue(info.previewImage!=0);assertNotNull(info.loadPreviewImage(context,0));assertTrue(info.previewLayout!=0)
            instrumentation.runOnMainSync{
                val view=RemoteViews(context.packageName,info.previewLayout).apply(context,null)
                assertTrue(text(view).contains("02:35"));assertFalse(text(view).contains("Error"))
                val width=if(index==0)180 else 260;val height=if(index==0)140 else 320
                val density=context.resources.displayMetrics.density
                val w=(width*density).toInt();val h=(height*density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
                val group=view as ViewGroup
                for(child in 0 until group.childCount)assertTrue("Preview content clipped",group.getChildAt(child).bottom<=h-group.paddingBottom)
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null),"widget-preview-${if(index==0)"compact"else "expanded"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
        }
    }
    @Test fun installedWidgetsShowEmployerAndUpdateWhenShiftStarts()=runBlocking {
        val repository=Repository(TabelDatabase.get(context));val employer=repository.dao.saveEmployer(Employer(name="Widget regression"))
        val manager=AppWidgetManager.getInstance(context);val host=AppWidgetHost(context,731031);val ids=mutableListOf<Int>()
        val preferences=context.getSharedPreferences("widgets",0)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try{
            listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).forEach{receiver->
                val id=host.allocateAppWidgetId();ids.add(id)
                assertTrue("Cannot bind test widget",manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,receiver),Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,240);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,260)}))
                preferences.edit().putLong("employer_$id",employer).commit()
            }
            val views=mutableListOf<AppWidgetHostView>()
            instrumentation.runOnMainSync{host.startListening();ids.forEach{views.add(host.createView(context,it,manager.getAppWidgetInfo(it)))}}
            suspend fun awaitText(expected:String){
                val deadline=System.currentTimeMillis()+30000
                while(System.currentTimeMillis()<deadline){var ready=false;instrumentation.runOnMainSync{ready=views.all{text(it).contains(expected)}};if(ready)return;kotlinx.coroutines.delay(200)}
                var actual="";instrumentation.runOnMainSync{actual=views.joinToString(" | "){text(it)}};fail("Missing $expected: $actual")
            }
            refreshWidgets(context);awaitText("Widget regression")
            repository.start(employer);refreshWidgets(context);awaitText("Завершить")
            repository.stop(employer);refreshWidgets(context);awaitText("Начать")
            instrumentation.runOnMainSync{host.stopListening()}
        }finally{
            ids.forEach{preferences.edit().remove("employer_$it").commit();host.deleteAppWidgetId(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            repository.dao.allShifts().filter{it.employerId==employer}.forEach{repository.dao.deleteShift(it)};repository.dao.deleteEmptyEmployer(employer)
        }
    }
}
