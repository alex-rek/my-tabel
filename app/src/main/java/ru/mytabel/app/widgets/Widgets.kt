package ru.mytabel.app.widgets

import android.appwidget.AppWidgetManager
import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.clickable
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import android.os.SystemClock
import android.widget.RemoteViews
import ru.mytabel.app.R
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.currentState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import ru.mytabel.app.TabelApplication
import ru.mytabel.app.settings.translate
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import ru.mytabel.app.data.*
import ru.mytabel.app.ui.Choices
import ru.mytabel.app.domain.*
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.ColorFilter
import androidx.glance.action.Action
import androidx.glance.semantics.semantics as widgetSemantics
import androidx.glance.semantics.contentDescription as widgetDescription
import ru.mytabel.app.domain.TimeFormats
import java.time.*
import java.time.format.DateTimeFormatter

private fun preferences(context: Context)=context.getSharedPreferences("widgets",Context.MODE_PRIVATE)
private fun selectedEmployer(context: Context,id: Int)=preferences(context).getLong("employer_$id",-1)

private val widgetEmployerKey=longPreferencesKey("selected_employer")
private val parallelWarningKey=booleanPreferencesKey("parallel_warning")
private val widgetErrorKey=stringPreferencesKey("action_error")
private val actionEmployerKey=ActionParameters.Key<Long>("employer")
private val actionKindKey=ActionParameters.Key<String>("kind")
private val actionShiftKey=ActionParameters.Key<Long>("shift")
private val actionConfirmedKey=ActionParameters.Key<Boolean>("confirmed")
private val widgetRefreshKey=longPreferencesKey("refresh_time")

class TimeWidget(private val expanded: Boolean=false) : GlanceAppWidget() {
    override val stateDefinition=PreferencesGlanceStateDefinition
    override suspend fun provideGlance(context: Context,id: GlanceId) {
        WidgetProgressWorker.schedule(context)
        val widgetId=GlanceAppWidgetManager(context).getAppWidgetId(id)
        val dao=TabelDatabase.get(context).dao()
        val employerFlow=dao.employers();val shiftFlow=dao.shifts()
        val initialEmployers=dao.allEmployers();val initialShifts=dao.allShifts()
        val rulesFlow=dao.rules();val daysFlow=dao.scheduleDays();val initialRules=dao.allRules();val initialDays=dao.allScheduleDays()
        val settingsStore=(context.applicationContext as TabelApplication).settings
        provideContent {
            val widgetState=currentState<Preferences>()
            val selected=widgetState[widgetEmployerKey] ?: selectedEmployer(context,widgetId)
            val employers by employerFlow.collectAsState(initialEmployers)
            val shifts by shiftFlow.collectAsState(initialShifts)
            val settings by settingsStore.state.collectAsState()
            val rules by rulesFlow.collectAsState(initialRules);val days by daysFlow.collectAsState(initialDays)
            val employer=employers.firstOrNull{it.id==selected}
            val active=shifts.firstOrNull{it.employerId==employer?.id&&it.end==null}
            val night=settings.theme=="DARK"||(settings.theme=="SYSTEM"&&context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
            val accent=Color(employer?.color ?: ru.mytabel.app.ui.paletteColor(settings.palette))
            val foreground=ColorProvider(if(night)Color(0xFFF5F7FC)else Color(0xFF101B29));val secondary=ColorProvider(if(night)Color(0xFFB7C6D4)else Color(0xFF47556A))
            val now=System.currentTimeMillis();val paused=active?.pauseStartedAt!=null
            val compactFont=((androidx.glance.LocalSize.current.width.value-80f)/4.5f).coerceIn(20f,32f)
            val plan=employer?.let{ShiftPlans.find(it,active,now,rules,days)}
            fun tr(s:String)=translate(s,settings.language)
            val config=Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
            fun action(kind:String,confirmed:Boolean=false)=actionRunCallback<WidgetShiftAction>(actionParametersOf(actionEmployerKey to (employer?.id ?: -1),actionKindKey to kind,actionShiftKey to (active?.id ?: -1),actionConfirmedKey to confirmed))
            val warning=widgetState[parallelWarningKey]==true&&active==null
            val error=widgetState[widgetErrorKey]?.takeIf{it.isNotBlank()}
            val status=tr(if(paused)"На перерыве"else if(active!=null)"Работаю"else "Готов к работе")
            val red=Color(0xFFFA3455)
            @Composable fun iconButton(label:String,icon:Int,callback:Action,modifier:GlanceModifier,color:Color){
                androidx.glance.layout.Box(modifier.background(ColorProvider(color)).cornerRadius(28.dp).clickable(callback),contentAlignment=androidx.glance.layout.Alignment.Center){Image(ImageProvider(icon),tr(label),GlanceModifier.size(22.dp))}
            }
            @Composable fun clock(){
                if(active!=null){
                    val view=RemoteViews(context.packageName,R.layout.widget_chronometer)
                    view.setChronometer(R.id.widget_clock,SystemClock.elapsedRealtime()-(now-active.start).coerceAtLeast(0),null,true)
                    view.setTextColor(R.id.widget_clock,if(night)android.graphics.Color.WHITE else android.graphics.Color.rgb(16,27,41))
                    view.setTextViewTextSize(R.id.widget_clock,android.util.TypedValue.COMPLEX_UNIT_SP,if(expanded)42f else compactFont)
                    AndroidRemoteViews(view,GlanceModifier.fillMaxWidth().height(if(expanded)58.dp else 40.dp))
                }else Text("00:00",style=TextStyle(color=foreground,fontSize=if(expanded)42.sp else 30.sp,fontWeight=androidx.glance.text.FontWeight.Bold))
            }
            @Composable fun header(){
                androidx.glance.layout.Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(config)),verticalAlignment=androidx.glance.layout.Alignment.Vertical.CenterVertically){
                    androidx.glance.layout.Box(GlanceModifier.size(if(expanded)40.dp else 30.dp).background(ColorProvider(accent)).cornerRadius(10.dp),contentAlignment=androidx.glance.layout.Alignment.Center){Image(ImageProvider(R.drawable.widget_work),null,GlanceModifier.size(if(expanded)24.dp else 19.dp))}
                    Spacer(GlanceModifier.width(8.dp))
                    androidx.glance.layout.Column(GlanceModifier.defaultWeight()){
                        Text(employer?.name ?: tr("Выберите работодателя"),maxLines=1,style=TextStyle(color=foreground,fontSize=if(expanded)18.sp else 14.sp,fontWeight=androidx.glance.text.FontWeight.Bold))
                        Text("● $status",maxLines=1,style=TextStyle(color=if(paused)ColorProvider(Color(0xFFFFB74D))else secondary,fontSize=if(expanded)12.sp else 10.sp))
                    }
                    if(expanded)Image(ImageProvider(R.drawable.widget_more),tr("Настроить"),GlanceModifier.size(22.dp),colorFilter=ColorFilter.tint(foreground))
                }
            }
            androidx.glance.layout.Column(GlanceModifier.fillMaxSize().background(ImageProvider(widgetGradient(accent,night))).cornerRadius(26.dp).padding(if(expanded)16.dp else 12.dp)){
                header()
                if(warning||error!=null){
                    Text(error ?: if(settings.language=="en")"Another job has an active shift. Start another?"else "У другой работы идёт смена. Начать ещё?",maxLines=3,style=TextStyle(color=foreground,fontSize=12.sp))
                    Spacer(GlanceModifier.defaultWeight())
                    androidx.glance.layout.Row(GlanceModifier.fillMaxWidth()){
                        androidx.glance.Button(tr(if(warning)"Начать ещё"else"Повторить"),action(if(active==null)"start"else"stop",warning),modifier=GlanceModifier.defaultWeight())
                        androidx.glance.Button("×",action("cancel"),modifier=GlanceModifier.width(44.dp))
                    }
                }else if(!expanded){
                    Spacer(GlanceModifier.defaultWeight())
                    androidx.glance.layout.Row(GlanceModifier.fillMaxWidth(),verticalAlignment=androidx.glance.layout.Alignment.Vertical.CenterVertically){
                        androidx.glance.layout.Column(GlanceModifier.defaultWeight()){
                            clock()
                            Text(if(active!=null)"${if(settings.language=="en")"from"else"с"} ${TimeFormats.time(Instant.ofEpochMilli(active.start).atZone(ZoneId.of(employer!!.zone)).toLocalTime().let{it.hour*60+it.minute},settings.hour24)}"else tr("Смена не начата"),maxLines=1,style=TextStyle(color=secondary,fontSize=11.sp))
                        }
                        Spacer(GlanceModifier.width(4.dp))
                        iconButton(if(employer==null)"Настроить"else if(paused)"Продолжить"else if(active!=null)"Завершить"else"Начать",if(active!=null&&!paused)R.drawable.widget_stop else R.drawable.widget_play,if(employer==null)actionStartActivity(config)else action(if(paused)"resume"else if(active==null)"start"else"stop"),GlanceModifier.size(48.dp),if(active!=null&&!paused)red else accent)
                    }
                }else{
                    androidx.glance.layout.Column(GlanceModifier.padding(top=8.dp)){clock();Text(tr("Продолжительность смены"),style=TextStyle(color=secondary,fontSize=11.sp))}
                    Spacer(GlanceModifier.defaultWeight())
                    androidx.glance.layout.Column {
                    androidx.glance.layout.Row(GlanceModifier.fillMaxWidth().background(ColorProvider(if(night)Color(0xFF253143)else Color(0xFFE6ECF1))).cornerRadius(16.dp).padding(10.dp)){
                        val started=active?.let{TimeFormats.time(Instant.ofEpochMilli(it.start).atZone(ZoneId.of(employer!!.zone)).hour*60+Instant.ofEpochMilli(it.start).atZone(ZoneId.of(employer.zone)).minute,settings.hour24)} ?: "—"
                        listOf(tr("Начало") to started,tr("План") to (plan?.let{hours(it.paidSeconds)} ?: "—"),tr("Ставка") to (money(active?.rateCents ?: employer?.rateCents ?: 0,settings.currency)+if(settings.language=="en")"/h"else"/ч")).forEach{(label,value)->
                            androidx.glance.layout.Column(GlanceModifier.defaultWeight()){
                                Text(label,style=TextStyle(color=secondary,fontSize=11.sp));Text(value,maxLines=1,style=TextStyle(color=foreground,fontSize=14.sp,fontWeight=androidx.glance.text.FontWeight.Bold))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    if(plan!=null){
                        LinearProgressIndicator(plan.progress(now),GlanceModifier.fillMaxWidth().height(5.dp),color=ColorProvider(accent),backgroundColor=secondary)
                        Text("${tr("План до")} ${TimeFormats.dateTime(plan.through,employer!!.zone,settings.hour24)}",maxLines=1,style=TextStyle(color=secondary,fontSize=11.sp))
                    }else Text(tr("График на день не задан"),style=TextStyle(color=secondary,fontSize=11.sp))
                    if(active!=null){
                        val view=RemoteViews(context.packageName,R.layout.widget_net_time)
                        val net=Breaks.worked(active,now);val rest=Breaks.seconds(active,now)
                        view.setTextViewText(R.id.net_label,tr("Чистое:"));view.setTextViewText(R.id.rest_label,tr("Перерывы:"))
                        view.setChronometer(R.id.net_clock,SystemClock.elapsedRealtime()-net*1000,null,!paused&&(active.timedBreaks!=null||(now-active.start)/1000>active.breakMinutes*60L))
                        view.setChronometer(R.id.rest_clock,SystemClock.elapsedRealtime()-rest*1000,null,paused)
                        listOf(R.id.net_label,R.id.rest_label,R.id.net_clock,R.id.rest_clock).forEach{view.setTextColor(it,if(night)android.graphics.Color.WHITE else android.graphics.Color.rgb(16,27,41))}
                        AndroidRemoteViews(view,GlanceModifier.fillMaxWidth().height(28.dp))
                    }else Spacer(GlanceModifier.height(8.dp))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    androidx.glance.layout.Row(GlanceModifier.fillMaxWidth(),verticalAlignment=androidx.glance.layout.Alignment.Vertical.CenterVertically){
                        val label=tr(if(employer==null)"Настроить"else if(active==null)"Начать"else"Завершить")
                        androidx.glance.layout.Box(GlanceModifier.defaultWeight().height(44.dp).background(ColorProvider(if(active==null)accent else red)).cornerRadius(18.dp).clickable(if(employer==null)actionStartActivity(config)else action(if(active==null)"start"else"stop")),contentAlignment=androidx.glance.layout.Alignment.Center){Text("${if(active==null)"▶"else"■"} $label",style=TextStyle(color=ColorProvider(Color.White),fontSize=14.sp))}
                        if(active!=null){
                            Spacer(GlanceModifier.width(8.dp))
                            androidx.glance.layout.Box(GlanceModifier.defaultWeight().height(44.dp).background(ColorProvider(if(night)Color(0xFF35405B)else Color(0xFFDFE5F4))).cornerRadius(18.dp).clickable(action(if(paused)"resume"else"pause")),contentAlignment=androidx.glance.layout.Alignment.Center){Text(tr(if(paused)"Продолжить"else"Перерыв"),style=TextStyle(color=foreground,fontSize=13.sp))}
                        }
                    }
                }
            }

        }
    }
}

// Explicit start/stop commands make repeated taps and stale widget buttons idempotent.
class WidgetShiftAction:ActionCallback {
    override suspend fun onAction(context:Context,glanceId:GlanceId,parameters:ActionParameters){
        val widgetId=GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val employerId=parameters[actionEmployerKey] ?: return
        val kind=parameters[actionKindKey] ?: return
        if(selectedEmployer(context,widgetId)!=employerId){refreshWidget(context,widgetId);return}
        var warning=false;var error=""
        try{
            withContext(Dispatchers.IO){
                val repository=Repository(TabelDatabase.get(context))
                repository.db.withTransaction{
                    val shifts=repository.dao.allShifts()
                    val active=shifts.firstOrNull{it.employerId==employerId&&it.end==null}
                    when(kind){
                        "start"->if(active==null){
                            if(shifts.any{it.employerId!=employerId&&it.end==null}&&parameters[actionConfirmedKey]!=true)warning=true
                            else repository.start(employerId)
                        }
                        "pause","resume"->if(active!=null&&active.id==parameters[actionShiftKey])repository.pause(employerId,active.id,kind=="pause")
                        "stop"->if(active!=null&&active.id==parameters[actionShiftKey])repository.stop(employerId)
                    }
                }
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Не удалось изменить смену"}
        updateAppWidgetState(context,glanceId){it[parallelWarningKey]=warning;it[widgetErrorKey]=error}
        refreshWidgets(context)
    }
}

class CompactReceiver: GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=TimeWidget() }
class ExpandedReceiver: GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=TimeWidget(true) }
suspend fun refreshWidget(context:Context,widgetId:Int) {
    val manager=AppWidgetManager.getInstance(context)
    val info=manager.getAppWidgetInfo(widgetId) ?: return
    val widget=when(info.provider){
        ComponentName(context,CompactReceiver::class.java)->TimeWidget()
        ComponentName(context,ExpandedReceiver::class.java)->TimeWidget(true)
        else->return
    }
    val glanceId=GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
    updateAppWidgetState(context,glanceId){state->
        if(state[widgetEmployerKey]!=selectedEmployer(context,widgetId)){state[parallelWarningKey]=false;state[widgetErrorKey]=""}
        state[widgetEmployerKey]=selectedEmployer(context,widgetId)
        state[widgetRefreshKey]=System.currentTimeMillis()
    }
    widget.update(context,glanceId)
}
suspend fun refreshWidgets(context: Context) {
    WidgetProgressWorker.schedule(context)
    val manager=AppWidgetManager.getInstance(context)
    listOf(CompactReceiver::class.java,ExpandedReceiver::class.java).forEach{receiver->
        manager.getAppWidgetIds(ComponentName(context,receiver)).forEach{refreshWidget(context,it)}
    }
}
class RestoreReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{try{refreshWidgets(context)}finally{pending.finish()}}
    }
}
class WidgetConfigurationActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return}
        setContent {
            var employers by remember{mutableStateOf<List<Employer>>(emptyList())}; var loading by remember{mutableStateOf(true)}
            var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
            val scope=rememberCoroutineScope()
            LaunchedEffect(Unit){try{employers=TabelDatabase.get(this@WidgetConfigurationActivity).dao().allEmployers()}catch(e:Exception){error=e.message}finally{loading=false}}
            MaterialTheme { Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column(Modifier.windowInsetsPadding(WindowInsets.systemBars).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.Text("Работодатель для виджета",style=MaterialTheme.typography.headlineSmall)
                if(loading)androidx.compose.material3.CircularProgressIndicator() else if(employers.isEmpty()) {
                    androidx.compose.material3.Text("Сначала добавьте рабочее место в приложении.")
                    Button({startActivity(Intent(this@WidgetConfigurationActivity,ru.mytabel.app.MainActivity::class.java));finish()}){androidx.compose.material3.Text("Открыть Мой табель")}
                }
                error?.let{androidx.compose.material3.Text(it,color=MaterialTheme.colorScheme.error)}
                if(busy)androidx.compose.material3.CircularProgressIndicator()
                employers.forEach{e->Button({
                    busy=true;error=null
                    scope.launch{
                        try{
                            val saved=withContext(Dispatchers.IO){preferences(this@WidgetConfigurationActivity).edit().putLong("employer_$id",e.id).commit()}
                            check(saved){"Не удалось сохранить настройку виджета"}
                            // Return success even if a launcher delays the Glance update.
                            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
                            try{withTimeout(5000){refreshWidget(this@WidgetConfigurationActivity,id)}}catch(e:Exception){android.util.Log.w("MyTabelWidget","Widget refresh deferred",e)}
                            finish()
                        }catch(e:Exception){error=e.message;busy=false}
                    }
                },enabled=!busy,modifier=Modifier.fillMaxWidth()){androidx.compose.material3.Text(e.name)}}
            } } }
        }
    }
}
class WidgetControlActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)
        val employerId=selectedEmployer(this,id)
        if(employerId<0){startActivity(Intent(this,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish();return}
        setContent {
            val repository=remember{Repository(TabelDatabase.get(this@WidgetControlActivity))}
            var ready by remember{mutableStateOf(false)};var another by remember{mutableStateOf(false)};var active by remember{mutableStateOf(false)}
            var employer by remember{mutableStateOf<Employer?>(null)};var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)}
            val scope=rememberCoroutineScope()
            LaunchedEffect(Unit){
                employer=repository.dao.employer(employerId)
                val shifts=repository.dao.allShifts();active=shifts.any{it.employerId==employerId&&it.end==null};another=shifts.any{it.employerId!=employerId&&it.end==null};ready=true
            }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column(Modifier.windowInsetsPadding(WindowInsets.systemBars).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(!ready)androidx.compose.material3.CircularProgressIndicator() else {
                    androidx.compose.material3.Text(employer?.name ?: "Работодатель не найден",style=MaterialTheme.typography.headlineSmall)
                    if(another&&!active)androidx.compose.material3.Text("У другого работодателя уже идёт смена. Можно начать ещё одну одновременно.")
                    error?.let{androidx.compose.material3.Text(it,color=MaterialTheme.colorScheme.error)}
                    Button({busy=true;scope.launch{try{if(active)repository.stop(employerId)else repository.start(employerId);refreshWidgets(this@WidgetControlActivity);finish()}catch(e:Exception){error=e.message;busy=false}}},enabled=!busy&&employer!=null){androidx.compose.material3.Text(if(active)"Закончить работу" else "Начать работу")}
                    TextButton({finish()}){androidx.compose.material3.Text("Отмена")}
                }
            } } }
        }
    }
}

private fun widgetGradient(accent:Color,night:Boolean):android.graphics.Bitmap {
    val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
    val a=accent.copy(alpha=if(night).42f else .20f).compositeOver(if(night)Color(0xFF07121C)else Color.White)
    val b=if(night)Color(0xFF080F1C)else Color(0xFFF4F7F7)
    val paint=android.graphics.Paint().apply{shader=android.graphics.LinearGradient(0f,0f,256f,256f,intArrayOf(android.graphics.Color.rgb((a.red*255).toInt(),(a.green*255).toInt(),(a.blue*255).toInt()),android.graphics.Color.rgb((b.red*255).toInt(),(b.green*255).toInt(),(b.blue*255).toInt())),null,android.graphics.Shader.TileMode.CLAMP)}
    android.graphics.Canvas(bitmap).drawRect(0f,0f,256f,256f,paint);return bitmap
}
