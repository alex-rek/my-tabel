@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ru.mytabel.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val number=label.contains("ставк",true)||label.contains("час",true)||label.contains("минут",true)||label.contains("Сумма")||label.contains("порог")||label.contains("%")
    OutlinedTextField(value, onChange, label = { AppText(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=if(number)26.sp else 20.sp),keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text))
}
@Composable fun <T> Choices(title: String, items: List<Pair<T, String>>, selected: T, localize:Boolean=true, choose: (T) -> Unit) {
    AppText(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (value, label) -> FilterChip(selected == value, { choose(value) }, { if(localize)AppText(label)else Text(label) }) }
    }
}
@Composable fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { AppText(label, Modifier.weight(1f)); Switch(value, change) }
}
@Composable fun DateField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, change, label = { AppText(label) }, singleLine = true, modifier = Modifier.weight(1f),textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=22.sp))
        TextButton({ val d = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now()); DatePickerDialog(context, { _, y, m, day -> change(LocalDate.of(y, m+1, day).toString()) }, d.year, d.monthValue-1, d.dayOfMonth).show() }) { AppText("Дата") }
    }
}
@Composable fun TimeField(label: String, value: String, change: (String) -> Unit) {
    val parts=value.split(':');val hour=parts.getOrElse(0){""};val minute=parts.getOrElse(1){""}
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        AppText(label,style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            OutlinedTextField(hour,{change(it.filter(Char::isDigit).take(2)+":"+minute)},label={AppText("Часы")},singleLine=true,modifier=Modifier.weight(1f),textStyle=MaterialTheme.typography.headlineMedium.copy(fontSize=30.sp,textAlign=TextAlign.Center),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            AppText(":",style=MaterialTheme.typography.headlineMedium)
            OutlinedTextField(minute,{change(hour+":"+it.filter(Char::isDigit).take(2))},label={AppText("Минуты")},singleLine=true,modifier=Modifier.weight(1f),textStyle=MaterialTheme.typography.headlineMedium.copy(fontSize=30.sp,textAlign=TextAlign.Center),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        }
    }
}
fun parseClock(value:String):LocalTime=LocalTime.of(value.substringBefore(':').toInt(),value.substringAfter(':').toInt())
@Composable fun FormDialog(title: String, dismiss: () -> Unit, save: () -> Unit, error: String?, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { AppText(title) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(); if(error != null) AppText(error, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton(save) { AppText("Сохранить") } }, dismissButton = { TextButton(dismiss) { AppText("Отмена") } })
}

@Composable fun EmployerForm(original: Employer?, dismiss: () -> Unit, save: (Employer) -> Unit) {
    val initial = original ?: Employer(name = "")
    var name by remember { mutableStateOf(initial.name) }; var rate by remember { mutableStateOf(initial.rateCents.toBigDecimal().movePointLeft(2).toPlainString()) }
    var color by remember { mutableStateOf(initial.color) }; var week by remember { mutableIntStateOf(initial.weekStart) }
    var period by remember { mutableStateOf(initial.period) }; var anchor by remember { mutableStateOf(LocalDate.ofEpochDay(initial.periodAnchor).toString()) }
    var breakMinutes by remember { mutableStateOf(initial.breakMinutes.toString()) }; var overtime by remember { mutableStateOf(initial.overtime) }
    var daily by remember { mutableStateOf(initial.dailyHours.toString()) }; var double by remember { mutableStateOf(initial.doubleHours.toString()) }
    var weekly by remember { mutableStateOf(initial.weeklyHours.toString()) }; var seventh by remember { mutableStateOf(initial.seventhDay) }
    var otPercent by remember { mutableStateOf(initial.overtimePercent.toString()) }; var dtPercent by remember { mutableStateOf(initial.doublePercent.toString()) }
    var workday by remember { mutableStateOf(LocalTime.of(initial.workdayStartMinutes/60, initial.workdayStartMinutes%60).toString()) }
    var taxMode by remember { mutableStateOf(initial.taxMode) }; var estimate by remember { mutableStateOf(initial.estimatePercent.toString()) }
    var zone by remember { mutableStateOf(initial.zone) }; var error by remember { mutableStateOf<String?>(null) }
    FormDialog(if(original == null) "Новое рабочее место" else "Настройки работодателя", dismiss, {
        try {
            require(name.isNotBlank()) { "Введите название" }
            val cents = parseMoney(rate); require(cents in 0..10000000000L) { "Проверьте ставку" }
            require(breakMinutes.toInt() in 0..1440) { "Перерыв: от 0 до 1440 минут" }
            require(daily.toInt() in 1..24 && double.toInt() in daily.toInt()..24 && weekly.toInt() in 1..168) { "Проверьте пороги overtime" }
            require(otPercent.toInt() in 100..1000 && dtPercent.toInt() in otPercent.toInt()..1000) { "Проверьте множители оплаты" }
            require(estimate.toInt() in 0..100) { "Удержания: от 0 до 100%" }; ZoneId.of(zone)
            val t = parseClock(workday)
            save(initial.copy(name = name.trim(), rateCents = cents, color = color, weekStart = week, period = period,
                periodAnchor = LocalDate.parse(anchor).toEpochDay(), breakMinutes = breakMinutes.toInt(), overtime = overtime, dailyHours = daily.toInt(), doubleHours = double.toInt(),
                weeklyHours = weekly.toInt(), seventhDay = seventh, overtimePercent = otPercent.toInt(), doublePercent = dtPercent.toInt(), workdayStartMinutes = t.hour*60+t.minute,
                taxMode = taxMode, estimatePercent = estimate.toInt(), zone = zone))
        } catch(e: Exception) { error = e.message ?: "Проверьте значения" }
    }, error) {
        Field("Название", name) { name = it }; Field("Ставка, $/час", rate) { rate = it }
        Choices("Цвет", listOf(0xFF00855A to "Зелёный", 0xFF1464F4 to "Синий", 0xFFD44A15 to "Оранжевый", 0xFF7C3AED to "Фиолетовый"), color) { color = it }
        Choices("Начало рабочей недели", (1..7).map { it to listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1] }, week) { week = it }
        TimeField("Начало рабочего дня", workday) { workday = it }
        Field("Часовой пояс (IANA)", zone) { zone = it }
        Choices("Расчётный период", listOf("WEEK" to "Неделя", "BIWEEK" to "2 недели", "HALF" to "Полмесяца", "MONTH" to "Месяц"), period) { period = it }
        if(period == "BIWEEK") DateField("Начало первого периода", anchor) { anchor = it }
        Field("Перерыв по умолчанию, минут", breakMinutes) { breakMinutes = it }
        Toggle("Сверхурочные (California)", overtime) { overtime = it }
        if(overtime) {
            AppText("Стандарт: после 8 ч — 150%, после 12 ч — 200%, свыше 40 ч/неделю — 150%. Применимость 7-го дня задаётся ниже.", style = MaterialTheme.typography.bodySmall)
            Field("Дневной порог, часов", daily) { daily = it }; Field("Двойная оплата после, часов", double) { double = it }
            Field("Недельный порог, часов", weekly) { weekly = it }
            Field("Оплата сверхурочных, % ставки", otPercent) { otPercent = it }; Field("Двойная оплата, % ставки", dtPercent) { dtPercent = it }
            Toggle("Применять правило 7-го дня", seventh) { seventh = it }
        }
        Choices("Налоги", listOf("NONE" to "Не учитывать", "ESTIMATE" to "Оценка", "MANUAL" to "Фактические"), taxMode) { taxMode = it }
        if(taxMode == "ESTIMATE") { Field("Оценочная доля удержаний, %", estimate) { estimate = it }; AppText("Приблизительно: процент от начислений, без налоговых таблиц. Показывается отдельно от долга.") }
        if(taxMode == "MANUAL") AppText("Фактические удержания добавляются отдельными операциями на вкладке «Выплаты».")
        if(original != null) AppText("Изменение правил overtime пересчитывает историю. Ставки сохранённых смен остаются прежними.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun ShiftForm(employers: List<Employer>, original: Shift?, selected: Long?, day: LocalDate, dismiss: () -> Unit, save: (Shift) -> Unit, delete: ((Shift) -> Unit)?) {
    val first = employers.first { it.id == (original?.employerId ?: selected ?: employers.first().id) }
    val zone = ZoneId.of(first.zone)
    val startInitial = original?.let { Instant.ofEpochMilli(it.start).atZone(zone) }
    val endInitial = original?.end?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var employerId by remember { mutableLongStateOf(first.id) }
    var startDate by remember { mutableStateOf((startInitial?.toLocalDate() ?: day).toString()) }
    var endDate by remember { mutableStateOf((endInitial?.toLocalDate() ?: startInitial?.toLocalDate() ?: day).toString()) }
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    var startTime by remember { mutableStateOf(startInitial?.format(fmt) ?: "09:00") }; var endTime by remember { mutableStateOf(endInitial?.format(fmt) ?: "18:00") }
    var rate by remember { mutableStateOf((original?.rateCents ?: first.rateCents).toBigDecimal().movePointLeft(2).toPlainString()) }
    var rest by remember { mutableStateOf((original?.breakMinutes ?: first.breakMinutes).toString()) }; var comment by remember { mutableStateOf(original?.comment ?: "") }
    var error by remember { mutableStateOf<String?>(null) }; var confirmDelete by remember { mutableStateOf(false) }
    var keepImportedHours by remember{mutableStateOf(original?.paidSecondsOverride!=null)}
    FormDialog(if(original == null) "Добавить смену" else "Редактировать смену", dismiss, {
        try {
            val z = ZoneId.of(employers.first { it.id == employerId }.zone)
            fun timestamp(date: String, time: String): Long {
                val local = LocalDateTime.of(LocalDate.parse(date),parseClock(time))
                require(z.rules.getValidOffsets(local).isNotEmpty()) { "Это время пропущено при переводе часов" }
                return local.atZone(z).toInstant().toEpochMilli()
            }
            val start = timestamp(startDate,startTime); var end = timestamp(endDate,endTime)
            if(end < start && endDate == startDate) end = timestamp(LocalDate.parse(endDate).plusDays(1).toString(),endTime)
            val breakValue = rest.toInt(); require(breakValue >= 0) { "Перерыв не может быть отрицательным" }
            val active = original != null && original.end == null
            require(active || end > start) { "Окончание должно быть позже начала" }
            require(active || breakValue * 60000L <= end-start) { "Перерыв длиннее смены" }
            val cents = parseMoney(rate); require(cents in 0..10000000000L) { "Проверьте ставку" }
            save(Shift(original?.id ?: 0, employerId,start, if(active) null else end,cents,breakValue,comment.trim(),if(keepImportedHours)original?.paidSecondsOverride else null))
        } catch(e: Exception) { error = e.message ?: "Проверьте дату и время" }
    }, error) {
        Choices("Работодатель", employers.map { it.id to it.name }, employerId,localize=false) { id -> employerId = id; if(original == null) { val e = employers.first { it.id == id }; rate = e.rateCents.toBigDecimal().movePointLeft(2).toPlainString(); rest = e.breakMinutes.toString() } }
        DateField("Дата начала", startDate) { startDate = it }; TimeField("Начало", startTime) { startTime = it }
        if(original == null || original.end != null) {
            DateField("Дата окончания", endDate) { endDate = it }; TimeField("Окончание", endTime) { endTime = it }
            AppText("Если время окончания раньше начала в той же дате, окончание переносится на следующий день.", style = MaterialTheme.typography.bodySmall)
        } else AppText("Активная смена. Для завершения нажмите «Закончить работу» на главном экране.")
        Field("Ставка этой смены, $/час", rate) { rate = it }; Field("Неоплачиваемый перерыв, минут", rest) { rest = it }
        if(original?.paidSecondsOverride!=null){AppText("${if(LocalAppSettings.current.language=="en")"Imported paid time" else "Оплаченное время из импорта"}: ${hours(original.paidSecondsOverride)}");Toggle(if(LocalAppSettings.current.language=="en")"Keep imported paid hours" else "Сохранить оплаченные часы из импорта",keepImportedHours){keepImportedHours=it}}
        AppText("Перерыв вычитается из конца смены. Это влияет на распределение часов при переходе рабочего дня.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(comment, { comment = it }, label = { AppText("Комментарий") }, modifier = Modifier.fillMaxWidth())
        if(original != null && delete != null) TextButton({ confirmDelete = true }) { AppText("Удалить смену", color = MaterialTheme.colorScheme.error) }
    }
    if(confirmDelete) ConfirmDelete("Удалить смену?", { confirmDelete = false }) { delete?.invoke(original!!); dismiss() }
}

@Composable fun PaymentForm(employers: List<Employer>, original: Payment?, selected: Long?, dismiss: () -> Unit, save: (Payment) -> Unit, delete: ((Payment) -> Unit)?) {
    var employer by remember { mutableLongStateOf(original?.employerId ?: selected ?: employers.first().id) }
    var date by remember { mutableStateOf(original?.let { LocalDate.ofEpochDay(it.day).toString() } ?: LocalDate.now().toString()) }
    var amount by remember { mutableStateOf(original?.cents?.toBigDecimal()?.movePointLeft(2)?.toPlainString() ?: "") }
    var kind by remember { mutableStateOf(original?.kind ?: "SALARY") }; var comment by remember { mutableStateOf(original?.comment ?: "") }
    var error by remember { mutableStateOf<String?>(null) }; var confirmDelete by remember { mutableStateOf(false) }
    val manual = employers.first { it.id == employer }.taxMode == "MANUAL"
    FormDialog(if(original == null) "Новая операция" else "Редактировать операцию", dismiss, {
        try {
            val cents = parseMoney(amount); require(cents in 1..10000000000000L) { "Введите положительную сумму" }
            require(kind != "WITHHOLDING" || manual) { "Включите режим фактических удержаний у работодателя" }
            save(Payment(original?.id ?: 0, employer, LocalDate.parse(date).toEpochDay(), cents, kind, comment.trim()))
        } catch(e: Exception) { error = e.message ?: "Проверьте значения" }
    }, error) {
        Choices("Работодатель", employers.map { it.id to it.name }, employer,localize=false) { employer = it; if(kind == "WITHHOLDING") kind = "SALARY" }
        DateField("Дата", date) { date = it }; Field("Сумма, $", amount) { amount = it }
        Choices("Тип операции", listOf("ADVANCE" to "Аванс", "SALARY" to "Зарплата") + if(manual) listOf("WITHHOLDING" to "Удержание (факт)") else emptyList(), kind) { kind = it }
        OutlinedTextField(comment, { comment = it }, label = { AppText("Комментарий") }, modifier = Modifier.fillMaxWidth())
        if(original != null && delete != null) TextButton({ confirmDelete = true }) { AppText("Удалить операцию", color = MaterialTheme.colorScheme.error) }
    }
    if(confirmDelete) ConfirmDelete("Удалить операцию?", { confirmDelete = false }) { delete?.invoke(original!!); dismiss() }
}
@Composable fun ConfirmDelete(title: String, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { AppText(title) }, text = { AppText("Это действие нельзя отменить.") }, confirmButton = { TextButton(delete) { AppText("Удалить") } }, dismissButton = { TextButton(dismiss) { AppText("Отмена") } })
}
