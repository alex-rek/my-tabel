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
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import ru.mytabel.app.domain.hours
import java.time.*
import java.time.format.DateTimeFormatter

private fun preferences(context: Context)=context.getSharedPreferences("widgets",Context.MODE_PRIVATE)
private fun selectedEmployer(context: Context,id: Int)=preferences(context).getLong("employer_$id",-1)

class TimeWidget(private val expanded: Boolean=false) : GlanceAppWidget() {
    override val stateDefinition=PreferencesGlanceStateDefinition
    override suspend fun provideGlance(context: Context,id: GlanceId) {
        val widgetId=GlanceAppWidgetManager(context).getAppWidgetId(id)
        val dao=TabelDatabase.get(context).dao()
        val employer=dao.employer(selectedEmployer(context,widgetId))
        val active=dao.allShifts().firstOrNull{it.employerId==employer?.id&&it.end==null}
        val now=System.currentTimeMillis()
        val settings=(context.applicationContext as TabelApplication).settings.state.value
        val night=settings.theme=="DARK"||(settings.theme=="SYSTEM"&&context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val accent=Color(employer?.color ?: ru.mytabel.app.ui.paletteColor(settings.palette))
        val background=accent.copy(alpha=if(night).28f else .14f).compositeOver(if(night)Color(0xFF10151E)else Color.White)
        val foreground=ColorProvider(if(night)Color(0xFFF1F4FF)else Color(0xFF172341))
        fun tr(s:String)=translate(s,settings.language)
        provideContent {
            androidx.glance.layout.Column(GlanceModifier.fillMaxSize().background(ColorProvider(background)).padding(14.dp),verticalAlignment=androidx.glance.layout.Alignment.Vertical.CenterVertically) {
                Text(employer?.name ?: tr("Выберите работодателя"),style=TextStyle(color=foreground,fontSize=18.sp))
                if(active!=null) {
                    Text(hours(((now-active.start)/1000).coerceAtLeast(0)),style=TextStyle(color=foreground,fontSize=26.sp))
                    if(expanded)Text("${tr("Начало:")} ${Instant.ofEpochMilli(active.start).atZone(ZoneId.of(employer!!.zone)).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}",style=TextStyle(color=foreground))
                    Text("${if(settings.language=="en")"Updated" else "Обновлено"} ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",style=TextStyle(color=foreground,fontSize=11.sp))
                } else Text(if(settings.language=="en")"Not working" else "Смена не начата",style=TextStyle(color=foreground))
                val intent=Intent(context,WidgetControlActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                androidx.glance.Button(tr(if(employer==null)"Настроить" else if(active==null)"Начать работу" else "Закончить работу"),actionStartActivity(intent),colors=androidx.glance.ButtonDefaults.buttonColors(backgroundColor=ColorProvider(accent),contentColor=ColorProvider(Color.White)))
                if(expanded) {
                    val config=Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widgetId)
                    androidx.glance.Button(if(settings.language=="en")"Choose employer" else "Сменить работодателя",actionStartActivity(config),colors=androidx.glance.ButtonDefaults.buttonColors(backgroundColor=ColorProvider(accent),contentColor=ColorProvider(Color.White)))
                    Text(if(settings.language=="en")"Time is saved. Android refreshes widgets periodically." else "Время сохранено. Android обновляет виджет периодически.",style=TextStyle(color=foreground,fontSize=11.sp))
                }
            }
        }
    }
}
class CompactReceiver: GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=TimeWidget() }
class ExpandedReceiver: GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget=TimeWidget(true) }
suspend fun refreshWidgets(context: Context) {
    val manager=GlanceAppWidgetManager(context)
    listOf(CompactReceiver::class.java to TimeWidget(),ExpandedReceiver::class.java to TimeWidget(true)).forEach{(receiver,widget)->
        val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,receiver))
        ids.forEach { widget.update(context,manager.getGlanceIdBy(it)) }
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
            val scope=rememberCoroutineScope()
            LaunchedEffect(Unit){employers=TabelDatabase.get(this@WidgetConfigurationActivity).dao().allEmployers();loading=false}
            MaterialTheme { Surface(Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Column(Modifier.windowInsetsPadding(WindowInsets.systemBars).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                androidx.compose.material3.Text("Работодатель для виджета",style=MaterialTheme.typography.headlineSmall)
                if(loading)CircularProgressIndicator() else if(employers.isEmpty()) {
                    androidx.compose.material3.Text("Сначала добавьте рабочее место в приложении.")
                    Button({startActivity(Intent(this@WidgetConfigurationActivity,ru.mytabel.app.MainActivity::class.java));finish()}){androidx.compose.material3.Text("Открыть Мой табель")}
                }
                employers.forEach{e->Button({
                    preferences(this@WidgetConfigurationActivity).edit().putLong("employer_$id",e.id).apply()
                    scope.launch{refreshWidgets(this@WidgetConfigurationActivity);setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()}
                },modifier=Modifier.fillMaxWidth()){androidx.compose.material3.Text(e.name)}}
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
                if(!ready)CircularProgressIndicator() else {
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
