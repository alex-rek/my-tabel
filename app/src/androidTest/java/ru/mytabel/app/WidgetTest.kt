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
    private fun text(view:View):String=when(view){is TextView->view.text.toString();is ViewGroup->(0 until view.childCount).joinToString(" "){text(view.getChildAt(it))};else->""}
    @Test fun bothPickerPreviewsHaveImagesAndInflatableLayouts(){
        val manager=AppWidgetManager.getInstance(context)
        listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).forEachIndexed{index,receiver->
            val info=manager.installedProviders.single{it.provider==ComponentName(context,receiver)}
            assertTrue(info.previewImage!=0);assertNotNull(info.loadPreviewImage(context,0));assertTrue(info.previewLayout!=0)
            instrumentation.runOnMainSync{
                val view=RemoteViews(context.packageName,info.previewLayout).apply(context,null)
                assertTrue(text(view).contains("02:35"));assertFalse(text(view).contains("Error"))
                val width=if(index==0)180 else 240;val height=if(index==0)140 else 240
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
            repository.start(employer);refreshWidgets(context);awaitText("Закончить работу")
            repository.stop(employer);refreshWidgets(context);awaitText("Начать работу")
            instrumentation.runOnMainSync{host.stopListening()}
        }finally{
            ids.forEach{preferences.edit().remove("employer_$it").commit();host.deleteAppWidgetId(it)}
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            repository.dao.allShifts().filter{it.employerId==employer}.forEach{repository.dao.deleteShift(it)};repository.dao.deleteEmptyEmployer(employer)
        }
    }
}