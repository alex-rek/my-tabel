@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ru.mytabel.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mytabel.app.TabelViewModel
import ru.mytabel.app.data.*
import ru.mytabel.app.reports.*
import java.time.*

@Composable fun ReportScreen(data: Snapshot, vm: TabelViewModel) {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var ids by remember { mutableStateOf(data.employers.map{it.id}.toSet()) }
    var from by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var through by remember { mutableStateOf(LocalDate.now().toString()) }
    var columns by remember { mutableStateOf(builtInTemplates.getValue("Краткий")) }
    var templateName by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ReportData?>(null) }; var busy by remember { mutableStateOf(false) }
    fun export(uri: android.net.Uri?, word: Boolean) {
        val report=pending ?: return
        if(uri==null){pending=null;return}
        busy=true
        scope.launch {
            status=try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { if(word) Reports.docx(report,it) else Reports.xlsx(report,it) } ?: error("Не удалось открыть файл") }; "Отчёт сохранён" }
                catch(e:Exception){"Ошибка экспорта: ${e.message}"}
            busy=false;pending=null
        }
    }
    val xlsx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")){export(it,false)}
    val docx=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){export(it,true)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Ваш отчёт",style=MaterialTheme.typography.headlineMedium)
        Text("Выберите период, рабочие места и порядок столбцов.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("3 дня","Неделя","Месяц","Полгода","С начала года").forEachIndexed { index,label -> AssistChip({
                val today=LocalDate.now();through=today.toString();from=when(index){0->today.minusDays(2);1->today.minusDays(6);2->today.withDayOfMonth(1);3->today.minusMonths(6).plusDays(1);else->today.withDayOfYear(1)}.toString()
            },{Text(label)}) }
        }
        DateField("С",from){from=it};DateField("По (включительно)",through){through=it}
        Text("Рабочие места",style=MaterialTheme.typography.titleMedium)
        data.employers.forEach { e -> Row { Checkbox(e.id in ids,{ids=if(it)ids+e.id else ids-e.id});Text(e.name,Modifier.padding(top=12.dp)) } }
        Text("Шаблон",style=MaterialTheme.typography.titleMedium)
        val templates=builtInTemplates + data.templates.associate { t -> t.name to t.columns.split(',').mapNotNull{runCatching{ReportColumn.valueOf(it)}.getOrNull()} }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){templates.forEach{(name,fields)->AssistChip({columns=fields},{Text(name)})}}
        Text("Столбцы в выбранном порядке",style=MaterialTheme.typography.titleMedium)
        Text("Стрелки меняют порядок. Снимите отметку, чтобы исключить поле.",style=MaterialTheme.typography.bodySmall)
        (columns + ReportColumn.entries.filter{it !in columns}).forEach { c ->
            val index=columns.indexOf(c)
            Row(Modifier.fillMaxWidth()) {
                Checkbox(index>=0,{checked->columns=if(checked)columns+c else columns-c})
                Text(c.title,Modifier.weight(1f).padding(top=12.dp))
                if(index>=0) {
                    TextButton({columns=columns.toMutableList().apply{val old=this[index-1];this[index-1]=c;this[index]=old}},enabled=index>0){Text("↑")}
                    TextButton({columns=columns.toMutableList().apply{val old=this[index+1];this[index+1]=c;this[index]=old}},enabled=index<columns.lastIndex){Text("↓")}
                }
            }
        }
        Field("Имя своего шаблона",templateName){templateName=it}
        OutlinedButton({
            if(templateName.isBlank()||columns.isEmpty())status="Введите имя и выберите поля"
            else if(templateName.trim() in builtInTemplates)status="Выберите другое имя: это встроенный шаблон"
            else { vm.perform{dao.saveTemplate(ReportTemplate(templateName.trim(),columns.joinToString(","){it.name}))};status="Шаблон сохранён" }
        }){Text("Сохранить шаблон")}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({
                try { require(ids.isNotEmpty()){ "Выберите работодателя" };pending=Reports.create(data,ids,LocalDate.parse(from),LocalDate.parse(through),columns);xlsx.launch("Мой табель $from $through.xlsx") }catch(e:Exception){status=e.message?:"Проверьте даты"}
            },enabled=!busy){Text("Excel · XLSX")}
            OutlinedButton({
                try { require(ids.isNotEmpty()){ "Выберите работодателя" };pending=Reports.create(data,ids,LocalDate.parse(from),LocalDate.parse(through),columns);docx.launch("Мой табель $from $through.docx") }catch(e:Exception){status=e.message?:"Проверьте даты"}
            },enabled=!busy){Text("Word · DOCX")}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.isNotEmpty())Text(status)
        Text("Активные смены в отчёт не входят. Остаток учитывает предыдущие периоды. Оценочные налоги не смешиваются с фактическими удержаниями.",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
