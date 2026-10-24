@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ru.mytabel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.mytabel.app.TabelViewModel
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val light = lightColorScheme(
    primary=Color(0xFF3B6549),onPrimary=Color.White,primaryContainer=Color(0xFFD3EACF),onPrimaryContainer=Color(0xFF142B1A),
    secondary=Color(0xFF5A7160),secondaryContainer=Color(0xFFDCE6D8),onSecondaryContainer=Color(0xFF172C1C),tertiary=Color(0xFF80623B),
    background=Color(0xFFF7F9F2),surface=Color(0xFFF7F9F2),surfaceContainer=Color(0xFFEBEFE5),surfaceContainerLow=Color(0xFFF0F3EB),surfaceContainerHigh=Color(0xFFE4E9DF),
    surfaceVariant=Color(0xFFDFE6D9),onSurface=Color(0xFF1B211B),onSurfaceVariant=Color(0xFF424B41))
private val dark = darkColorScheme(
    primary=Color(0xFFA7D3AA),onPrimary=Color(0xFF12381C),primaryContainer=Color(0xFF2B4B32),onPrimaryContainer=Color(0xFFD3EACF),
    secondary=Color(0xFFB4CCB8),secondaryContainer=Color(0xFF374B3B),onSecondaryContainer=Color(0xFFD8E8D6),tertiary=Color(0xFFE4C28F),
    background=Color(0xFF111711),surface=Color(0xFF111711),surfaceContainer=Color(0xFF1E261E),surfaceContainerLow=Color(0xFF192119),surfaceContainerHigh=Color(0xFF293129),
    surfaceVariant=Color(0xFF414A40),onSurface=Color(0xFFE2E8DE),onSurfaceVariant=Color(0xFFC0C9BB))

@Composable fun TabelApp(vm: TabelViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance",0) }
    var theme by rememberSaveable { mutableStateOf(prefs.getString("theme","SYSTEM")!!) }
    MaterialTheme(colorScheme = if(theme == "DARK" || (theme == "SYSTEM" && isSystemInDarkTheme())) dark else light) {
        val data by vm.state.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableIntStateOf(0) }; var selected by rememberSaveable { mutableStateOf<Long?>(null) }
        var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }; val month = YearMonth.parse(monthText)
        var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; val day = LocalDate.parse(dayText)
        var employerForm by remember { mutableStateOf(false) }; var employerEdit by remember { mutableStateOf<Employer?>(null) }
        var shiftForm by remember { mutableStateOf(false) }; var shiftEdit by remember { mutableStateOf<Shift?>(null) }
        var paymentForm by remember { mutableStateOf(false) }; var paymentEdit by remember { mutableStateOf<Payment?>(null) }
        var pendingStart by remember { mutableStateOf<Long?>(null) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
        val chosen = data.employers.filter { selected == null || it.id == selected }
        val allPay = remember(data.employers, data.shifts) { data.employers.flatMap { Payroll.calculate(it,data.shifts) } }
        fun editShift(s: Shift?) { shiftEdit = s; shiftForm = true }
        fun start(id: Long) {
            if(data.shifts.any { it.end == null && it.employerId != id }) pendingStart = id else vm.perform { start(id) }
        }
        Scaffold(
            topBar = { TopAppBar(title = { Column { Text("Мой табель", fontWeight = FontWeight.SemiBold); Text("Время работает на вас", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) } }, actions = {
                IconButton({ theme = when(theme) { "SYSTEM" -> "DARK"; "DARK" -> "LIGHT"; else -> "SYSTEM" }; prefs.edit().putString("theme",theme).apply() }) { Icon(Icons.Default.Contrast, "Тема: $theme") }
            }) },
            bottomBar = { NavigationBar {
                listOf("Календарь" to Icons.Default.CalendarMonth, "Выплаты" to Icons.Default.AccountBalanceWallet, "Отчёты" to Icons.Default.Assessment, "Работа" to Icons.Default.Business).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(screen == index, { screen = index }, icon = { Icon(icon,label) }, label = { Text(label) })
                }
            } },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = { if(screen == 0 || screen == 1 || screen == 3) FloatingActionButton({
                when { screen == 3 || data.employers.isEmpty() -> { employerEdit = null; employerForm = true }; screen == 1 -> { paymentEdit = null; paymentForm = true }; else -> editShift(null) }
            }) { Icon(Icons.Default.Add, when(screen) { 1 -> "Добавить выплату"; 3 -> "Добавить работодателя"; else -> "Добавить смену" }) } }
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                if(data.employers.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max=480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Schedule,null,Modifier.size(64.dp),MaterialTheme.colorScheme.primary)
                        Text("Ваша работа. Ваше время.", style = MaterialTheme.typography.headlineSmall)
                        Text("Добавьте первое рабочее место, чтобы отмечать смены, видеть заработок и учитывать выплаты.")
                        Button({ employerEdit = null; employerForm = true }) { Text("Добавить работодателя") }
                    }
                } else when(screen) {
                    0 -> {
                        val calendar: @Composable () -> Unit = {
                            EmployerSelector(data.employers,selected) { selected = it }
                            Calendar(month,day,chosen,allPay, { monthText = it.toString() }, { dayText = it.toString() })
                            val selectedPeriods = chosen.map { Payroll.period(it, if(day in month.atDay(1)..month.atEndOfMonth()) day else month.atDay(1)) }
                            if(chosen.size == 1) {
                                val period = selectedPeriods.first()
                                Text("Расчётный период: ${period.from} — ${period.through}", style = MaterialTheme.typography.labelLarge)
                                Summary(chosen,allPay,data.payments,period.from,period.through)
                            } else Summary(chosen,allPay,data.payments,month.atDay(1),month.atEndOfMonth())
                        }
                        val details: @Composable () -> Unit = {
                            Text("Сейчас на работе",style = MaterialTheme.typography.titleMedium)
                            chosen.forEach { e -> ActiveCard(e,data.shifts.firstOrNull { it.employerId == e.id && it.end == null }, { start(e.id) }, { vm.perform { stop(e.id) } }, { editShift(it) }) }
                            DayDetails(day,chosen,data.shifts,allPay,{ editShift(it) },{ editShift(null) })
                        }
                        if(wide) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(bottom=96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { calendar() }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { details() }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=96.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) { calendar(); details() }
                    }
                    1 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom=96.dp)) {
                        item { EmployerSelector(data.employers,selected) { selected = it } }
                        item { Summary(chosen,allPay,data.payments,month.atDay(1),month.atEndOfMonth()); Text("История операций",style=MaterialTheme.typography.titleLarge) }
                        val operations = data.payments.filter { p -> chosen.any { it.id == p.employerId && (p.kind != "WITHHOLDING" || it.taxMode == "MANUAL") } }
                        if(operations.isEmpty()) item { Text("Выплат пока нет. Добавьте аванс, зарплату или фактическое удержание.") }
                        items(operations,key={it.id}) { p ->
                            val e = data.employers.first { it.id == p.employerId }
                            Card(onClick={paymentEdit=p;paymentForm=true},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text("${e.name} · ${LocalDate.ofEpochDay(p.day)}",style=MaterialTheme.typography.labelLarge)
                                    Text("${when(p.kind){"ADVANCE"->"Аванс";"WITHHOLDING"->"Фактическое удержание";else->"Зарплата"}} · ${money(p.cents)}",style=MaterialTheme.typography.titleMedium)
                                    if(p.comment.isNotBlank()) Text(p.comment)
                                }
                            }
                        }
                    }
                    2 -> ReportScreen(data,vm)
                    3 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)) {
                        item { Text("Рабочие места",style=MaterialTheme.typography.headlineMedium); Text("Независимые ставки, периоды и правила расчёта.") }
                        items(data.employers,key={it.id}) { e ->
                            Card(onClick={employerEdit=e;employerForm=true},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Box(Modifier.size(12.dp).background(Color(e.color),CircleShape)); Text(e.name,style=MaterialTheme.typography.titleLarge) }
                                    Text("${money(e.rateCents)}/ч · ${if(e.overtime) "Overtime включён" else "Без overtime"}")
                                    Text("Налоги: ${when(e.taxMode){"MANUAL"->"фактические удержания";"ESTIMATE"->"оценка ${e.estimatePercent}%";else->"не учитываются"}}")
                                    Text("Настроить",color=MaterialTheme.colorScheme.primary)
                                    if(data.shifts.none { it.employerId == e.id } && data.payments.none { it.employerId == e.id }) TextButton({ vm.perform { dao.deleteEmptyEmployer(e.id) }; if(selected==e.id) selected=null }) { Text("Удалить пустое рабочее место") }
                                }
                            }
                        }
                        item { Text("Виджеты: удерживайте свободное место на домашнем экране → Виджеты → Мой табель. Для каждого выберите работодателя.\n\nВсе данные хранятся на устройстве. Валюта первой версии — USD. Оценка налогов использует заданный вами процент.",style=MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        if(employerForm) EmployerForm(employerEdit,{employerForm=false}) { e -> vm.perform { dao.saveEmployer(e) }; employerForm=false }
        if(shiftForm && data.employers.isNotEmpty()) ShiftForm(data.employers,shiftEdit,selected,day,{shiftForm=false},{s->vm.perform { saveShift(s) };shiftForm=false},{s->vm.perform { dao.deleteShift(s) }})
        if(paymentForm && data.employers.isNotEmpty()) PaymentForm(data.employers,paymentEdit,selected,{paymentForm=false},{p->vm.perform { dao.savePayment(p) };paymentForm=false},{p->vm.perform { dao.deletePayment(p) }})
        pendingStart?.let { id -> AlertDialog(onDismissRequest={pendingStart=null}, title={Text("Ещё одна активная смена")},text={Text("У другого работодателя уже идёт смена. Можно учитывать работу одновременно.")},confirmButton={TextButton({vm.perform { start(id) };pendingStart=null}){Text("Начать ещё одну")}},dismissButton={TextButton({pendingStart=null}){Text("Отмена")}}) }
    }
}

@Composable private fun EmployerSelector(employers: List<Employer>, selected: Long?, change: (Long?) -> Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(selected==null,{change(null)},{Text("Все места")})
        employers.forEach { e -> FilterChip(selected==e.id,{change(e.id)},{Text(e.name)},leadingIcon={Box(Modifier.size(8.dp).background(Color(e.color),CircleShape))}) }
    }
}
@Composable private fun Calendar(month: YearMonth, selected: LocalDate, employers: List<Employer>, pay: List<ShiftPay>, changeMonth: (YearMonth)->Unit, select: (LocalDate)->Unit) {
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton({changeMonth(month.minusMonths(1))}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,"Предыдущий месяц")}
                Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru"))).replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.titleLarge); TextButton({changeMonth(YearMonth.now());select(LocalDate.now())}){Text("Сегодня")} }
                IconButton({changeMonth(month.plusMonths(1))}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"Следующий месяц")}
            }
            Row { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
            val offset=month.atDay(1).dayOfWeek.value-1
            val daily = pay.filter { p -> employers.any { it.id==p.shift.employerId } }.groupBy { p -> p.shift.date(employers.first { it.id==p.shift.employerId }) }
            (0 until (offset+month.lengthOfMonth()+6)/7).forEach { week -> Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { weekday ->
                    val n=week*7+weekday-offset+1
                    if(n !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(88.dp)) else {
                        val d=month.atDay(n); val entries=daily[d].orEmpty()
                        Column(Modifier.weight(1f).heightIn(min=88.dp).background(if(d==selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(14.dp)).clickable{select(d)}.padding(vertical=7.dp,horizontal=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(n.toString(),fontWeight=if(d==LocalDate.now()) FontWeight.Bold else FontWeight.Normal,color=if(d==LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if(entries.isNotEmpty()) {
                                Text(hours(entries.sumOf{it.paidSeconds}),style=MaterialTheme.typography.labelSmall)
                                Text(money(entries.sumOf{it.grossCents}).replace(".00", ""),style=MaterialTheme.typography.labelSmall,maxLines=1)
                                Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) { entries.map{it.shift.employerId}.distinct().take(4).forEach { id -> Box(Modifier.size(5.dp).background(Color(employers.first{it.id==id}.color),CircleShape)) } }
                            }
                        }
                    }
                }
            } }
        }
    }
}
@Composable private fun Summary(employers: List<Employer>,pay: List<ShiftPay>,payments: List<Payment>,from: LocalDate,through: LocalDate) {
    val balance=Payroll.balance(employers,pay,payments,through,from)
    val opening=Payroll.balance(employers,pay,payments,from.minusDays(1))
    val closing=Payroll.balance(employers,pay,payments,through)
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("$from — $through",style=MaterialTheme.typography.labelMedium)
            val entries = pay.filter { p -> employers.any { it.id == p.shift.employerId && p.shift.date(it) in from..through } }
            Text("${hours(entries.sumOf { it.paidSeconds })} оплачено · ${entries.size} смен",style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column { Text("Начислено"); Text(money(balance.gross),style=MaterialTheme.typography.titleLarge) }
                Column { Text("Получено"); Text(money(balance.received),style=MaterialTheme.typography.titleLarge) }
            }
            if(employers.any{it.taxMode=="MANUAL"}) Text("Фактические удержания: ${money(balance.actual)}")
            Text("Входящий остаток: ${money(opening.due)}",style=MaterialTheme.typography.bodySmall)
            Text("Осталось к выплате",style=MaterialTheme.typography.labelLarge)
            Text(money(closing.due),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            if(employers.any{it.taxMode=="ESTIMATE"}) Text("Оценка удержаний за период: ${money(balance.estimate)}. Показана отдельно и не вычитается из долга.",style=MaterialTheme.typography.bodySmall)
            Text("Отрицательный остаток означает переплату. Активные смены не включены.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun ActiveCard(e: Employer,shift: Shift?,start:()->Unit,stop:()->Unit,edit:(Shift)->Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shift?.id) { while(shift!=null) { now=System.currentTimeMillis();delay(1000) } }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(e.name,style=MaterialTheme.typography.titleMedium)
        if(shift!=null) {
            Text(hours(((now-shift.start)/1000).coerceAtLeast(0)),style=MaterialTheme.typography.headlineMedium)
            Text("Начало: ${Instant.ofEpochMilli(shift.start).atZone(ZoneId.of(e.zone)).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))}")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(stop){Text("Закончить работу")}; TextButton({edit(shift)}){Text("Изменить")} }
        } else Button(start){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(4.dp));Text("Начать работу")}
    } }
}
@Composable private fun DayDetails(day: LocalDate,employers: List<Employer>,shifts: List<Shift>,pay: List<ShiftPay>,edit:(Shift)->Unit,add:()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(day.format(DateTimeFormatter.ofPattern("d MMMM",Locale.forLanguageTag("ru"))),style=MaterialTheme.typography.titleLarge);TextButton(add){Text("+ Смена")} }
    val list=shifts.filter { s -> employers.any{it.id==s.employerId && s.date(it)==day} }
    if(list.isEmpty()) Text("Нет смен. Добавьте запись или начните работу.",color=MaterialTheme.colorScheme.secondary)
    list.forEach { s ->
        val e=employers.first{it.id==s.employerId};val p=pay.firstOrNull{it.shift.id==s.id}
        Card(onClick={edit(s)},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(10.dp).background(Color(e.color),CircleShape));Text(e.name,fontWeight=FontWeight.SemiBold)}
            val zone=ZoneId.of(e.zone);val fmt=DateTimeFormatter.ofPattern("dd.MM HH:mm")
            Text("${Instant.ofEpochMilli(s.start).atZone(zone).format(fmt)} — ${s.end?.let{Instant.ofEpochMilli(it).atZone(zone).format(fmt)} ?: "идёт смена"}")
            if(p!=null){Text("${hours(p.paidSeconds)} оплачено · ${money(p.grossCents)}",style=MaterialTheme.typography.titleMedium);if(p.overtimeSeconds+p.doubleSeconds>0)Text("Сверхурочно: ${hours(p.overtimeSeconds)} · Двойные: ${hours(p.doubleSeconds)}",style=MaterialTheme.typography.bodySmall)}
            if(s.comment.isNotBlank())Text(s.comment)
        } }
    }
}
