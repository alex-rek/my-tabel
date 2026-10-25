package ru.mytabel.app.widgets
import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.work.*
import java.util.concurrent.TimeUnit

class WidgetProgressWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=try{refreshWidgets(applicationContext);Result.success()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){Result.retry()}
    companion object {
        fun schedule(context:Context){
            val manager=AppWidgetManager.getInstance(context)
            val present=listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).any{manager.getAppWidgetIds(ComponentName(context,it)).isNotEmpty()}
            val work=WorkManager.getInstance(context)
            if(present)work.enqueueUniquePeriodicWork("widget-plan-progress",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<WidgetProgressWorker>(15,TimeUnit.MINUTES).build())
            else work.cancelUniqueWork("widget-plan-progress")
        }
    }
}
