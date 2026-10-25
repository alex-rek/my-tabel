package ru.mytabel.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.mytabel.app.TabelViewModel
import ru.mytabel.app.data.*
import ru.mytabel.app.domain.TimeFormats
import java.time.*

@Composable fun MaintenancePanel(data:Snapshot,vm:TabelViewModel,busy:Boolean,task:(suspend()->String)->Unit){
    val language=LocalAppSettings.current.language
    var ids by remember{mutableStateOf(data.employers.map{it.id}.toSet())}
    var from by remember{mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())};var through by remember{mutableStateOf(LocalDate.now().toString())};var graph by remember{mutableStateOf(false)}
    var undo by remember{mutableStateOf<Pair<String,RemovalPreview>?>(null)}
    var clear by remember{mutableStateOf<Triple<Set<Long>,Pair<LocalDate,LocalDate>,Pair<Boolean,RemovalPreview>>?>(null)}
    AppText("История импорта",style=MaterialTheme.typography.titleLarge)
    val batches=data.imports.groupBy{Maintenance.batchKey(it)}.entries.sortedByDescending{it.value.maxOf{r->r.importedAt}}
    if(batches.isEmpty())AppText("Нет импортированных записей")
    batches.forEach{(key,rows)->val e=data.employers.firstOrNull{it.id==rows.first().employerId}
        OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(e?.name.orEmpty(),style=MaterialTheme.typography.titleMedium)
            AppText(if(rows.first().importedAt==0L)"Импорт из старой версии"else TimeFormats.dateTime(rows.first().importedAt,ZoneId.systemDefault().id,LocalAppSettings.current.hour24))
            AppText("${if(language=="en")"Rows"else"Строк"}: ${rows.size}")
            TextButton({task{val preview=withContext(Dispatchers.IO){Maintenance.previewUndo(vm.repository,key)};undo=key to preview;""}},enabled=!busy){AppText("Откатить импорт")}
        }}
    }
    HorizontalDivider();AppText("Очистка периода",style=MaterialTheme.typography.titleLarge)
    data.employers.forEach{e->Row{Checkbox(e.id in ids,{ids=if(it)ids+e.id else ids-e.id});Text(e.name,Modifier.padding(top=12.dp))}}
    DateField("С",from){from=it};DateField("По (включительно)",through){through=it}
    Toggle("Удалить также график",graph){graph=it}
    AppText("Будут удалены завершённые смены, выплаты и пометки импорта за выбранные даты. Активные смены, работодатели и настройки сохранятся.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton({task{val start=LocalDate.parse(from);val end=LocalDate.parse(through);val selected=ids.toSet();val includeGraph=graph;val preview=withContext(Dispatchers.IO){Maintenance.previewPeriod(vm.repository.snapshotNow(),selected,start,end,includeGraph)};clear=Triple(selected,start to end,includeGraph to preview);""}},enabled=!busy&&data.employers.isNotEmpty()){AppText("Очистить период")}
    undo?.let{(batch,preview)->AlertDialog(onDismissRequest={undo=null},title={AppText("Откатить импорт")},text={Column{
        Counts(preview)
        AppText("Удалятся записи этой загрузки, включая их последующие изменения. Другие ручные записи сохранятся.")
        if(preview.unresolved>0)AppText("Старые записи не удалось связать однозначно. Используйте очистку периода.",color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton({undo=null;task{withContext(Dispatchers.IO){Maintenance.undoImport(vm.repository,batch)};"Импорт отменён"}},enabled=preview.unresolved==0){AppText("Удалить")}},dismissButton={TextButton({undo=null}){AppText("Отмена")}})}
    clear?.let{value->AlertDialog(onDismissRequest={clear=null},title={AppText("Очистить период?")},text={Column{AppText("${value.second.first} — ${value.second.second}");Counts(value.third.second);AppText("Это действие нельзя отменить. Сначала можно сохранить резервную копию.")}},confirmButton={TextButton({clear=null;task{withContext(Dispatchers.IO){Maintenance.clearPeriod(vm.repository,value.first,value.second.first,value.second.second,value.third.first)};"Период очищен"}}){AppText("Удалить")}},dismissButton={TextButton({clear=null}){AppText("Отмена")}})}
}
@Composable private fun Counts(p:RemovalPreview){
    val english=LocalAppSettings.current.language=="en"
    AppText(if(english)"Shifts: ${p.shifts}; payments: ${p.payments}; import rows: ${p.notes}; schedule days: ${p.scheduleDays}."else"Смен: ${p.shifts}; выплат: ${p.payments}; строк импорта: ${p.notes}; дней графика: ${p.scheduleDays}.")
}
