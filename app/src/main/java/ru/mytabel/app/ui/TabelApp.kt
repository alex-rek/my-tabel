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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.mytabel.app.TabelApplication
import ru.mytabel.app.settings.translate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
    val app=context.applicationContext as TabelApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val data by vm.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var screen by rememberSaveable { mutableIntStateOf(0) }
    val accent=if(screen==4)paletteColor(settings.palette)else data.employers.firstOrNull{it.id==selected}?.color ?: paletteColor(settings.palette)
    CompositionLocalProvider(LocalAppSettings provides settings) {
    MaterialTheme(colorScheme = appColorScheme(accent,settings.theme=="DARK" || (settings.theme=="SYSTEM" && isSystemInDarkTheme()))) {
        var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }; val month = YearMonth.parse(monthText)
        var dayText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; val day = LocalDate.parse(dayText)
        var employerForm by remember { mutableStateOf(false) }; var employerEdit by remember { mutableStateOf<Employer?>(null) }
        var shiftForm by remember { mutableStateOf(false) }; var shiftEdit by remember { mutableStateOf<Shift?>(null) }
        var paymentForm by remember { mutableStateOf(false) }; var paymentEdit by remember { mutableStateOf<Payment?>(null) }
        var pendingStart by remember { mutableStateOf<Long?>(null) }
        var scheduleEmployer by remember{mutableStateOf<Employer?>(null)}
        var dayOpen by remember{mutableStateOf(false)}
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.errors.collect { snackbar.showSnackbar(it) } }
        val chosen = data.employers.filter { selected == null || it.id == selected }
        val allPay = remember(data.employers, data.shifts) { data.employers.flatMap { Payroll.calculate(it,data.shifts) } }
        fun editShift(s: Shift?) { shiftEdit = s; shiftForm = true }
        fun requestStart(id: Long) {
            if(data.shifts.any { it.end == null && it.employerId != id }) pendingStart = id else vm.perform { this.start(id) }
        }
        Scaffold(
            topBar = { TopAppBar(title = { Column { AppText("Мой табель", fontWeight = FontWeight.SemiBold); AppText("Время работает на вас", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) } }, actions = {
                IconButton({screen=4}) { Icon(Icons.Default.Settings, translate("Настройки",settings.language)) }
            }) },
            bottomBar = { NavigationBar {
                listOf("Календарь" to Icons.Default.CalendarMonth, "Выплаты" to Icons.Default.AccountBalanceWallet, "Отчёты" to Icons.Default.Assessment, "Работа" to Icons.Default.Business).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(screen == index, { screen = index }, icon = { Icon(icon,label) }, label = { AppText(label) })
                }
            } },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = { if(screen == 0 || screen == 1 || screen == 3) FloatingActionButton({
                when { screen == 3 || data.employers.isEmpty() -> { employerEdit = null; employerForm = true }; screen == 1 -> { paymentEdit = null; paymentForm = true }; else -> editShift(null) }
            }) { Icon(Icons.Default.Add, when(screen) { 1 -> "Добавить выплату"; 3 -> "Добавить работодателя"; else -> "Добавить смену" }) } }
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                if(screen==4)SettingsScreen(data,vm) else if(data.employers.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(32.dp).widthIn(max=480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Schedule,null,Modifier.size(64.dp),MaterialTheme.colorScheme.primary)
                        AppText("Ваша работа. Ваше время.", style = MaterialTheme.typography.headlineSmall)
                        AppText("Добавьте первое рабочее место, чтобы отмечать смены, видеть заработок и учитывать выплаты.")
                        Button({ employerEdit = null; employerForm = true }) { AppText("Добавить работодателя") }
                    }
                } else when(screen) {
                    0 -> {
                        val calendar: @Composable () -> Unit = {
                            EmployerSelector(data.employers,selected) { selected = it }
                            Calendar(month,day,chosen,allPay,data, { monthText = it.toString() }, { dayText = it.toString();dayOpen=true })
                            val selectedPeriods = chosen.map { Payroll.period(it, if(day in month.atDay(1)..month.atEndOfMonth()) day else month.atDay(1)) }
                            if(chosen.size == 1) {
                                val period = selectedPeriods.first()
                                AppText("Расчётный период: ${period.from} — ${period.through}", style = MaterialTheme.typography.labelLarge)
                                Summary(chosen,allPay,data.payments,period.from,period.through)
                            } else Summary(chosen,allPay,data.payments,month.atDay(1),month.atEndOfMonth())
                        }
                        val details: @Composable () -> Unit = {
                            AppText("Сейчас на работе",style = MaterialTheme.typography.titleMedium)
                            chosen.forEach { e -> ActiveCard(e,data.shifts.firstOrNull { it.employerId == e.id && it.end == null }, { requestStart(e.id) }, { vm.perform { stop(e.id) } }, { editShift(it) }) }
                            DayDetails(day,chosen,data.shifts,allPay,{ editShift(it) },{ editShift(null) })
                        }
                        if(wide) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(bottom=96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { calendar() }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom=96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { details() }
                        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=96.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) { calendar(); details() }
                    }
                    1 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom=96.dp)) {
                        item { EmployerSelector(data.employers,selected) { selected = it } }
                        item { Summary(chosen,allPay,data.payments,month.atDay(1),month.atEndOfMonth()); AppText("История операций",style=MaterialTheme.typography.titleLarge) }
                        val operations = data.payments.filter { p -> chosen.any { it.id == p.employerId && (p.kind != "WITHHOLDING" || it.taxMode == "MANUAL") } }
                        if(operations.isEmpty()) item { AppText("Выплат пока нет. Добавьте аванс, зарплату или фактическое удержание.") }
                        items(operations,key={it.id}) { p ->
                            val e = data.employers.first { it.id == p.employerId }
                            Card(onClick={paymentEdit=p;paymentForm=true},modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    AppText("${e.name} · ${LocalDate.ofEpochDay(p.day)}",style=MaterialTheme.typography.labelLarge)
                                    AppText("${when(p.kind){"ADVANCE"->"Аванс";"WITHHOLDING"->"Фактическое удержание";else->"Зарплата"}} · ${money(p.cents)}",style=MaterialTheme.typography.titleMedium)
                                    if(p.comment.isNotBlank()) Text(p.comment)
                                }
                            }
                        }
                    }
                    2 -> ReportScreen(data,vm)
                    3 -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=96.dp)) {
                        item { AppText("Рабочие места",style=MaterialTheme.typography.headlineMedium); AppText("Независимые ставки, периоды и правила расчёта.") }
                        items(data.employers,key={it.id}) { e ->
                            Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(e.color).copy(alpha=.15f))) {
                                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Box(Modifier.size(12.dp).background(Color(e.color),CircleShape)); Text(e.name,style=MaterialTheme.typography.titleLarge) }
                                    AppText("${money(e.rateCents)}/ч · ${if(e.overtime) "Overtime включён" else "Без overtime"}")
                                    AppText("Налоги: ${when(e.taxMode){"MANUAL"->"фактические удержания";"ESTIMATE"->"оценка ${e.estimatePercent}%";else->"не учитываются"}}")
                                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                        TextButton({selected=e.id;employerEdit=e;employerForm=true}){AppText("Настроить",color=Color(e.color))}
                                        TextButton({selected=e.id;scheduleEmployer=e}){Icon(Icons.Default.DateRange,null);AppText("График",color=Color(e.color))}
                                    }
                                    if(data.shifts.none { it.employerId == e.id } && data.payments.none { it.employerId == e.id } && data.rules.none{it.employerId==e.id} && data.scheduleDays.none{it.employerId==e.id} && data.imports.none{it.employerId==e.id}) TextButton({ vm.perform { dao.deleteEmptyEmployer(e.id) }; if(selected==e.id) selected=null }) { AppText("Удалить пустое рабочее место") }
                                }
                            }
                        }
                        item { AppText(if(settings.language=="en")"Add widgets from your home screen. Choose an employer for each widget. Data is stored on this device." else "Виджеты добавляются с домашнего экрана. Для каждого выберите работодателя. Данные хранятся на устройстве.",style=MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        if(employerForm) EmployerForm(employerEdit,{employerForm=false}) { e -> vm.perform { dao.saveEmployer(e) }; employerForm=false }
        if(shiftForm && data.employers.isNotEmpty()) ShiftForm(data.employers,shiftEdit,selected,day,{shiftForm=false},{s->vm.perform { saveShift(s) };shiftForm=false},{s->vm.perform { dao.deleteShift(s) }})
        if(paymentForm && data.employers.isNotEmpty()) PaymentForm(data.employers,paymentEdit,selected,{paymentForm=false},{p->vm.perform { dao.savePayment(p) };paymentForm=false},{p->vm.perform { dao.deletePayment(p) }})
        scheduleEmployer?.let{e->ScheduleDialog(e,data,vm){scheduleEmployer=null}}
        if(dayOpen)AlertDialog(onDismissRequest={dayOpen=false},title={AppText(day.toString())},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            val entries=data.shifts.filter{s->chosen.any{it.id==s.employerId&&s.date(it)==day}}
            if(entries.isEmpty())AppText("Нет смен. Добавьте запись или начните работу.")
            entries.forEach{s->val e=data.employers.first{it.id==s.employerId};OutlinedButton({dayOpen=false;editShift(s)}){AppText("${e.name} · ${TimeFormats.dateTime(s.start,e.zone,settings.hour24)} · ${if(settings.language=="en")"Edit" else "Изменить"}")}}
            chosen.forEach{e->val plan=Schedules.day(e.id,day,data.rules,data.scheduleDays);if(plan.working)AppText("${e.name} · ${if(settings.language=="en")"Planned" else "План"}: ${TimeFormats.time(plan.startMinutes,settings.hour24)} – ${TimeFormats.time(plan.endMinutes,settings.hour24)}")}
            data.imports.filter{it.day==day.toEpochDay()&&it.note.isNotBlank()&&chosen.any{e->e.id==it.employerId}}.forEach{Text(it.note)}
        }},confirmButton={TextButton({dayOpen=false;editShift(null)}){AppText("Добавить время")}},dismissButton={TextButton({dayOpen=false}){AppText("Отмена")}})
        pendingStart?.let { id -> AlertDialog(onDismissRequest={pendingStart=null}, title={AppText("Ещё одна активная смена")},text={AppText("У другого работодателя уже идёт смена. Можно учитывать работу одновременно.")},confirmButton={TextButton({vm.perform { this.start(id) };pendingStart=null}){AppText("Начать ещё одну")}},dismissButton={TextButton({pendingStart=null}){AppText("Отмена")}}) }
    }
    }
}

@Composable private fun EmployerSelector(employers: List<Employer>, selected: Long?, change: (Long?) -> Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(selected==null,{change(null)},{AppText("Все места")})
        employers.forEach { e -> FilterChip(selected==e.id,{change(e.id)},{Text(e.name)},leadingIcon={Box(Modifier.size(8.dp).background(Color(e.color),CircleShape))}) }
    }
}
@Composable private fun Calendar(month: YearMonth, selected: LocalDate, employers: List<Employer>, pay: List<ShiftPay>, data:Snapshot, changeMonth: (YearMonth)->Unit, select: (LocalDate)->Unit) {
    val language=LocalAppSettings.current.language
    val firstDay=LocalAppSettings.current.weekStart
    val threshold=with(LocalDensity.current){56.dp.toPx()}
    Card(modifier=Modifier.semantics{contentDescription="calendar-month"}.pointerInput(month,threshold){var drag=0f;detectHorizontalDragGestures(onDragStart={drag=0f},onHorizontalDrag={change,amount->drag+=amount;change.consume()},onDragEnd={val step=CalendarTools.monthStep(drag,threshold);if(step!=0L)changeMonth(month.plusMonths(step))})},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                IconButton({changeMonth(month.minusMonths(1))}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,"Предыдущий месяц")}
                Column(horizontalAlignment=Alignment.CenterHorizontally) { AppText(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag(language))).replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.titleLarge); TextButton({changeMonth(YearMonth.now())}){AppText("Сегодня")} }
                IconButton({changeMonth(month.plusMonths(1))}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"Следующий месяц")}
            }
            Row { CalendarTools.weekdays(firstDay).forEach { AppText(listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1],Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center) } }
            val offset=CalendarTools.offset(month.atDay(1),firstDay)
            val daily = pay.filter { p -> employers.any { it.id==p.shift.employerId } }.groupBy { p -> p.shift.date(employers.first { it.id==p.shift.employerId }) }
            (0 until (offset+month.lengthOfMonth()+6)/7).forEach { week -> Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { weekday ->
                    val n=week*7+weekday-offset+1
                    if(n !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(76.dp)) else {
                        val d=month.atDay(n); val entries=daily[d].orEmpty()
                        val planned=employers.filter{Schedules.day(it.id,d,data.rules,data.scheduleDays).working}
                        val cellColor=entries.firstOrNull()?.let{p->employers.first{it.id==p.shift.employerId}.color} ?: planned.firstOrNull()?.color
                        Column(Modifier.weight(1f).heightIn(min=76.dp).background(if(cellColor!=null)Color(cellColor).copy(alpha=if(entries.isEmpty()).14f else .3f)else if(d==selected)MaterialTheme.colorScheme.primaryContainer else Color.Transparent,RoundedCornerShape(10.dp)).semantics{contentDescription="day-$d"}.clickable{select(d)}.padding(vertical=5.dp,horizontal=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            AppText(n.toString(),fontWeight=if(d==LocalDate.now()) FontWeight.Bold else FontWeight.Normal,color=if(d==LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if(entries.isNotEmpty()) {
                                AppText(hours(entries.sumOf{it.paidSeconds}),style=MaterialTheme.typography.labelSmall)
                                AppText(money(entries.sumOf{it.grossCents}).replace(".00", ""),style=MaterialTheme.typography.labelSmall,maxLines=1)
                                Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) { entries.map{it.shift.employerId}.distinct().take(4).forEach { id -> Box(Modifier.size(5.dp).background(Color(employers.first{it.id==id}.color),CircleShape)) } }
                            }
                            if(planned.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){planned.take(4).forEach{e->Box(Modifier.size(6.dp).background(Color(e.color),CircleShape))}}
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
            AppText("$from — $through",style=MaterialTheme.typography.labelMedium)
            val entries = pay.filter { p -> employers.any { it.id == p.shift.employerId && p.shift.date(it) in from..through } }
            AppText("${hours(entries.sumOf { it.paidSeconds })} оплачено · ${entries.size} смен",style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Column { AppText("Начислено"); AppText(money(balance.gross),style=MaterialTheme.typography.titleLarge) }
                Column { AppText("Получено"); AppText(money(balance.received),style=MaterialTheme.typography.titleLarge) }
            }
            if(employers.any{it.taxMode=="MANUAL"}) AppText("Фактические удержания: ${money(balance.actual)}")
            AppText("Входящий остаток: ${money(opening.due)}",style=MaterialTheme.typography.bodySmall)
            AppText("Осталось к выплате",style=MaterialTheme.typography.labelLarge)
            AppText(money(closing.due),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)
            if(employers.any{it.taxMode=="ESTIMATE"}) AppText("Оценка удержаний за период: ${money(balance.estimate)}. Показана отдельно и не вычитается из долга.",style=MaterialTheme.typography.bodySmall)
            AppText("Отрицательный остаток означает переплату. Активные смены не включены.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun ActiveCard(e: Employer,shift: Shift?,start:()->Unit,stop:()->Unit,edit:(Shift)->Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shift?.id) { while(shift!=null) { now=System.currentTimeMillis();delay(1000) } }
    OutlinedCard(Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=Color(e.color).copy(alpha=.12f))) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(e.name,style=MaterialTheme.typography.titleMedium)
        if(shift!=null) {
            AppText(hours(((now-shift.start)/1000).coerceAtLeast(0)),style=MaterialTheme.typography.headlineMedium)
            AppText("Начало: ${TimeFormats.dateTime(shift.start,e.zone,LocalAppSettings.current.hour24)}")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(stop,colors=ButtonDefaults.buttonColors(containerColor=Color(e.color),contentColor=Color.White)){AppText("Закончить работу")}; TextButton({edit(shift)}){AppText("Изменить")} }
        } else Button(start,colors=ButtonDefaults.buttonColors(containerColor=Color(e.color),contentColor=Color.White)){Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(4.dp));AppText("Начать работу")}
    } }
}
@Composable private fun DayDetails(day: LocalDate,employers: List<Employer>,shifts: List<Shift>,pay: List<ShiftPay>,edit:(Shift)->Unit,add:()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { AppText(day.format(DateTimeFormatter.ofPattern("d MMMM",Locale.forLanguageTag(LocalAppSettings.current.language))),style=MaterialTheme.typography.titleLarge);TextButton(add){AppText("+ Смена")} }
    val list=shifts.filter { s -> employers.any{it.id==s.employerId && s.date(it)==day} }
    if(list.isEmpty()) AppText("Нет смен. Добавьте запись или начните работу.",color=MaterialTheme.colorScheme.secondary)
    list.forEach { s ->
        val e=employers.first{it.id==s.employerId};val p=pay.firstOrNull{it.shift.id==s.id}
        Card(onClick={edit(s)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(e.color).copy(alpha=.16f))) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(10.dp).background(Color(e.color),CircleShape));Text(e.name,fontWeight=FontWeight.SemiBold)}
            AppText("${TimeFormats.dateTime(s.start,e.zone,LocalAppSettings.current.hour24)} — ${s.end?.let{TimeFormats.dateTime(it,e.zone,LocalAppSettings.current.hour24)} ?: "идёт смена"}")
            if(p!=null){AppText("${hours(p.paidSeconds)} оплачено · ${money(p.grossCents)}",style=MaterialTheme.typography.titleMedium);if(p.overtimeSeconds+p.doubleSeconds>0)AppText("Сверхурочно: ${hours(p.overtimeSeconds)} · Двойные: ${hours(p.doubleSeconds)}",style=MaterialTheme.typography.bodySmall)}
            if(s.comment.isNotBlank())Text(s.comment)
        } }
    }
}
