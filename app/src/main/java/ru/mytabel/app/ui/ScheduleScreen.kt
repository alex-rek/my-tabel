@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import ru.mytabel.app.domain.Schedules
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun ScheduleDialog(employer:Employer,data:Snapshot,vm:TabelViewModel,dismiss:()->Unit){
    var month by remember{mutableStateOf(YearMonth.now())};var editing by remember{mutableStateOf(false)};var dayEdit by remember{mutableStateOf<ScheduleDay?>(null)}
    val language=LocalAppSettings.current.language
    AlertDialog(onDismissRequest=dismiss,title={AppText("${employer.name} · ${if(language=="en")"Schedule" else "График"}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton({month=month.minusMonths(1)}){AppText("‹")};AppText(month.toString(),Modifier.padding(top=12.dp));TextButton({month=month.plusMonths(1)}){AppText("›")}}
        Button({editing=true}){AppText("Изменить график")}
        AppText("Дни до текущей даты сохраняются. Новый график действует с выбранной даты.",style=MaterialTheme.typography.bodySmall)
        (1..month.lengthOfMonth()).forEach{n->val date=month.atDay(n);val day=Schedules.day(employer.id,date,data.rules,data.scheduleDays)
            OutlinedCard(onClick={dayEdit=day},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=if(day.working)androidx.compose.ui.graphics.Color(employer.color).copy(alpha=.15f)else MaterialTheme.colorScheme.surface)){
                Row(Modifier.padding(10.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){AppText(date.format(DateTimeFormatter.ofPattern("dd EEE",Locale.forLanguageTag(language))));AppText(if(day.working)"${clock(day.startMinutes)} – ${clock(day.endMinutes)}" else "Выходной")}
            }
        }
    }},confirmButton={TextButton(dismiss){AppText(if(language=="en")"Close" else "Закрыть")}})
    if(editing)RuleForm(employer,{editing=false}){rule->vm.perform{dao.saveRule(rule)};editing=false}
    dayEdit?.let{day->
        var working by remember(day){mutableStateOf(day.working)};var start by remember(day){mutableStateOf(clock(day.startMinutes))};var end by remember(day){mutableStateOf(clock(day.endMinutes))};var rest by remember(day){mutableStateOf(day.breakMinutes.toString())};var error by remember(day){mutableStateOf<String?>(null)}
        val past=day.day<LocalDate.now().toEpochDay()
        FormDialog(LocalDate.ofEpochDay(day.day).toString(),{dayEdit=null},{if(past)dayEdit=null else try{Schedules.validate("1",minute(start),minute(end),rest.toInt());vm.perform{dao.saveScheduleDay(day.copy(working=working,startMinutes=minute(start),endMinutes=minute(end),breakMinutes=rest.toInt()))};dayEdit=null}catch(e:Exception){error=e.message}},error){
            if(past)AppText("Прошедший день — только просмотр")else Toggle("Рабочий день",working){working=it}
            if(working){TimeField("Начало",start){if(!past)start=it};TimeField("Окончание",end){if(!past)end=it};Field("Перерыв, минут",rest){if(!past)rest=it}}
        }
    }
}
fun clock(minutes:Int)="%02d:%02d".format(minutes/60,minutes%60)
fun minute(value:String)=parseClock(value).let{it.hour*60+it.minute}
@Composable private fun RuleForm(employer:Employer,dismiss:()->Unit,save:(ScheduleRule)->Unit){
    var from by remember{mutableStateOf(LocalDate.now().toString())};var pattern by remember{mutableStateOf("1111100")};var mode by remember{mutableStateOf("WEEK")}
    var start by remember{mutableStateOf("09:00")};var end by remember{mutableStateOf("18:00")};var rest by remember{mutableStateOf(employer.breakMinutes.toString())};var error by remember{mutableStateOf<String?>(null)}
    FormDialog("Изменить график",dismiss,{
        try{val date=LocalDate.parse(from);require(date>=LocalDate.now()){"Нельзя изменять уже прошедшие дни"};Schedules.validate(pattern,minute(start),minute(end),rest.toInt())
            val anchor=if(mode=="WEEK")date.minusDays(date.dayOfWeek.value-1L)else date
            save(ScheduleRule(employerId=employer.id,effectiveFrom=date.toEpochDay(),anchor=anchor.toEpochDay(),pattern=pattern,startMinutes=minute(start),endMinutes=minute(end),breakMinutes=rest.toInt()))
        }catch(e:Exception){error=e.message}
    },error){
        DateField("Изменение с даты",from){from=it}
        Choices("Цикл",listOf("WEEK" to "5/2","2" to "2/2","3" to "3/3","CUSTOM" to "Свой цикл"),mode){mode=it;pattern=when(it){"WEEK"->"1111100";"2"->"1100";"3"->"111000";else->pattern}}
        if(mode=="WEEK")FlowRow{listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed{index,label->FilterChip(pattern[index]=='1',{pattern=pattern.toCharArray().also{it[index]=if(it[index]=='1')'0'else'1'}.concatToString()},{AppText(label)})}}
        if(mode=="CUSTOM"){Field("Работа / отдых",pattern){pattern=it.filter{c->c=='0'||c=='1'}.take(31)};AppText("1 = работа, 0 = отдых; цикл начинается с выбранной даты.")}
        TimeField("Начало",start){start=it};TimeField("Окончание",end){end=it};Field("Перерыв, минут",rest){rest=it}
        AppText("Дни до текущей даты сохраняются. Новый график действует с выбранной даты.")
    }
}
