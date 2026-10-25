package ru.mytabel.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mytabel.app.TabelViewModel
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun ScheduleDialog(employer:Employer,data:Snapshot,vm:TabelViewModel,dismiss:()->Unit){
    val settings=LocalAppSettings.current
    val today=LocalDate.now(ZoneId.of(employer.zone))
    var week by remember{mutableStateOf(CalendarTools.weekStart(today,settings.weekStart))}
    var draft by remember(week){mutableStateOf((0L..6L).map{Schedules.day(employer.id,week.plusDays(it),data.rules,data.scheduleDays)})}
    var dayEdit by remember{mutableStateOf<ScheduleDay?>(null)}
    val editable=draft.filter{CalendarTools.editable(LocalDate.ofEpochDay(it.day),today)}
    AlertDialog(onDismissRequest=dismiss,title={Text(employer.name)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        AppText("График на неделю",style=MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton({week=week.minusWeeks(1)}){AppText("‹")};AppText("$week — ${week.plusDays(6)}",Modifier.padding(top=12.dp),style=MaterialTheme.typography.labelMedium);TextButton({week=week.plusWeeks(1)}){AppText("›")}}
        AppText("Время задаётся отдельно для каждого дня. Можно менять последние 7 дней и будущие дни.",style=MaterialTheme.typography.bodySmall)
        draft.forEach{day->
            val date=LocalDate.ofEpochDay(day.day);val allowed=CalendarTools.editable(date,today)
            OutlinedCard(onClick={dayEdit=day},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=if(day.working)androidx.compose.ui.graphics.Color(employer.color).copy(alpha=.15f)else MaterialTheme.colorScheme.surface)){
                Row(Modifier.padding(10.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Column(Modifier.weight(1f)){AppText(date.format(DateTimeFormatter.ofPattern("EEE, dd.MM",Locale.forLanguageTag(settings.language))));AppText(if(day.working)"${TimeFormats.time(day.startMinutes,settings.hour24)} – ${TimeFormats.time(day.endMinutes,settings.hour24)}"else "Выходной",style=MaterialTheme.typography.labelMedium);if(!allowed)AppText("Только просмотр",style=MaterialTheme.typography.labelSmall)}
                    Switch(day.working,{value->draft=draft.map{if(it.day==day.day)it.copy(working=value)else it}},enabled=allowed)
                }
            }
        }
        AppText("График сохраняется для выбранной недели. Другие недели не меняются.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton({vm.perform{saveWeek(editable,today)};dismiss()},enabled=editable.isNotEmpty()){AppText("Сохранить")}},dismissButton={TextButton(dismiss){AppText("Отмена")}})
    dayEdit?.let{day->
        val date=LocalDate.ofEpochDay(day.day);val allowed=CalendarTools.editable(date,today)
        var working by remember(day){mutableStateOf(day.working)};var start by remember(day){mutableStateOf(clock(day.startMinutes))};var end by remember(day){mutableStateOf(clock(day.endMinutes))};var rest by remember(day){mutableStateOf(day.breakMinutes.toString())};var error by remember(day){mutableStateOf<String?>(null)}
        FormDialog(date.toString(),{dayEdit=null},{if(!allowed)dayEdit=null else try{Schedules.validate("1",minute(start),minute(end),rest.toInt());val edited=day.copy(working=working,startMinutes=minute(start),endMinutes=minute(end),breakMinutes=rest.toInt());draft=draft.map{if(it.day==day.day)edited else it};dayEdit=null}catch(e:Exception){error=e.message}},error){
            if(!allowed){AppText("Только просмотр");AppText(if(day.working)"${TimeFormats.time(day.startMinutes,settings.hour24)} – ${TimeFormats.time(day.endMinutes,settings.hour24)}"else"Выходной")}
            else{Toggle("Рабочий день",working){working=it};if(working){TimeField("Начало",start){start=it};TimeField("Окончание",end){end=it};Field("Перерыв, минут",rest){rest=it}}}
        }
    }
}
fun clock(minutes:Int)="%02d:%02d".format(minutes/60,minutes%60)
fun minute(value:String)=parseClock(value).let{it.hour*60+it.minute}
