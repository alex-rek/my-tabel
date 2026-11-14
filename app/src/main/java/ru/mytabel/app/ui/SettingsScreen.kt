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
import kotlinx.coroutines.*
import ru.mytabel.app.TabelApplication
import ru.mytabel.app.TabelViewModel
import ru.mytabel.app.data.*
import ru.mytabel.app.widgets.refreshWidgets
import java.time.LocalDate

@Composable fun SettingsScreen(data:Snapshot,vm:TabelViewModel) {
    val context=LocalContext.current;val app=context.applicationContext as TabelApplication
    val settings by app.settings.state.collectAsState()
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf("")}
    var employer by remember{mutableLongStateOf(0)};var name by remember{mutableStateOf("Импорт Work Hours")};var plan by remember{mutableStateOf<LegacyImport.Plan?>(null)}
    var backupPreview by remember{mutableStateOf<BackupEnvelope?>(null)}
    var paymentKind by remember{mutableStateOf("ADVANCE")}
    fun task(block:suspend()->String){busy=true;scope.launch{try{status=block()}catch(e:Exception){status=e.message?:"Ошибка"}finally{busy=false}}}
    fun update(v:ru.mytabel.app.settings.AppSettings){app.settings.update(v);scope.launch(Dispatchers.IO){runCatching{refreshWidgets(context)}}}
    suspend fun read(uri:android.net.Uri):String=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{stream->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=20_000_000){"Файл больше 20 МБ"};output.write(buffer,0,count)}
        output.toString("UTF-8")
    }?:error("Не удалось открыть файл")}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)task{val p=withContext(Dispatchers.Default){LegacyImport.plan(read(uri))};plan=p;"CSV прочитан"}}
    val saveBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)task{
        withContext(Dispatchers.IO){val text=Backup.encode(app.settings.state.value,vm.repository.snapshotNow());context.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray(Charsets.UTF_8))}?:error("Не удалось открыть файл")};"Копия сохранена"
    }}
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)task{backupPreview=withContext(Dispatchers.Default){Backup.decode(read(uri))};"Копия прочитана"}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        AppText("Настройки",style=MaterialTheme.typography.headlineMedium)
        UpdatePanel()
        Choices("Язык",listOf("ru" to "Русский","en" to "English"),settings.language){update(settings.copy(language=it))}
        Choices("Первый день рабочей недели",(1..7).map{it to listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1]},settings.weekStart){update(settings.copy(weekStart=it))}
        AppText("Порядок дней календаря и графика. Начало недели для overtime задаётся у работодателя.",style=MaterialTheme.typography.bodySmall)
        Choices("Формат времени",listOf(true to "24 часа",false to "12 часов · AM/PM"),settings.hour24){update(settings.copy(hour24=it))}
        Choices("Тема",listOf("SYSTEM" to "Системная","LIGHT" to "Светлая","DARK" to "Тёмная"),settings.theme){update(settings.copy(theme=it))}
        Choices("Цветовая схема",listOf("BLUE" to "Синий","PURPLE" to "Фиолетовый","GREEN" to "Зелёный","ORANGE" to "Оранжевый"),settings.palette){update(settings.copy(palette=it))}
        Choices("Валюта",listOf("USD","EUR","RUB","GBP","CAD").map{it to it},settings.currency){update(settings.copy(currency=it))}
        AppText(if(settings.language=="en")"Currency changes the display unit; existing amounts are not converted." else "Валюта меняет единицу отображения. Существующие суммы не конвертируются.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider();AppText("Импорт CSV",style=MaterialTheme.typography.titleLarge)
        Choices("Работодатель",listOf(0L to "Новый работодатель")+data.employers.map{it.id to it.name},employer){employer=it}
        if(employer==0L)Field("Название",name){name=it}
        OutlinedButton({import.launch(arrayOf("text/*","application/octet-stream","application/vnd.ms-excel"))},enabled=!busy){AppText("Выбрать CSV")}
        AppText(if(settings.language=="en")"Work Hours backup: hours, rates, comments and payments. Re-importing into the same employer skips duplicates. Imported paid hours are preserved; overtime is disabled for a newly imported employer." else "Копия Work Hours: часы, ставки, комментарии и выплаты. Повторный импорт к тому же работодателю пропускает дубли. Оплаченные часы сохраняются; у нового работодателя overtime отключён.",style=MaterialTheme.typography.bodySmall)
        plan?.let{p->
            Choices("Тип выплат",listOf("ADVANCE" to "Аванс","SALARY" to "Зарплата"),paymentKind){paymentKind=it}
            AppText(if(settings.language=="en")"Rows: ${p.rows.size}; shifts: ${p.shifts}; payments: ${p.payments}. Paid-time differences: ${p.mismatches}." else "Строк: ${p.rows.size}; смен: ${p.shifts}; выплат: ${p.payments}. Расхождений оплаченного времени: ${p.mismatches}.")
            Button({task{val result=withContext(Dispatchers.IO){LegacyImport.apply(vm.repository,p,employer.takeIf{it>0},name,paymentKind)};plan=null;refreshWidgets(context);"${if(settings.language=="en")"Imported / skipped" else "Импортировано / пропущено"}: ${result.first} / ${result.second}"}},enabled=!busy){AppText("Импортировать")}
        }
        HorizontalDivider();AppText("Резервная копия",style=MaterialTheme.typography.titleLarge)
        AppText(if(settings.language=="en")"Full JSON backup: employers, shifts, payments, schedule history, templates and settings." else "Полная JSON-копия: работодатели, смены, выплаты, история графиков, шаблоны и настройки.")
        Button({saveBackup.launch("my-tabel-backup-${LocalDate.now()}.json")},enabled=!busy){AppText("Сохранить локально")}
        OutlinedButton({status=if(settings.language=="en")"Choose Google Drive in the system file picker (Drive must be installed)." else "В системном окне выберите Google Диск (приложение Диск должно быть установлено).";saveBackup.launch("my-tabel-backup-${LocalDate.now()}.json")},enabled=!busy){AppText("Сохранить в Google Диск")}
        OutlinedButton({restore.launch(arrayOf("application/json","text/*","application/octet-stream"))},enabled=!busy){AppText("Восстановить копию")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.isNotBlank())AppText(status)
        HorizontalDivider()
        MaintenancePanel(data,vm,busy){block->task{val message=block();withContext(Dispatchers.IO){refreshWidgets(context)};message}}
        Spacer(Modifier.height(32.dp))
    }
    backupPreview?.let{b->AlertDialog(onDismissRequest={backupPreview=null},title={AppText("Восстановить копию")},text={AppText(if(settings.language=="en")"Add ${b.data.employers.size} employers, ${b.data.shifts.size} shifts and ${b.data.payments.size} transactions. Existing entries are preserved, identical entries are skipped. Restore settings too." else "Добавить ${b.data.employers.size} работодателей, ${b.data.shifts.size} смен и ${b.data.payments.size} операций. Текущие записи сохранятся, одинаковые будут пропущены. Настройки также восстановятся.")},confirmButton={TextButton({backupPreview=null;task{withContext(Dispatchers.IO){Backup.restore(vm.repository,b)};update(b.settings);"Копия восстановлена"}}){AppText("Восстановить копию")}},dismissButton={TextButton({backupPreview=null}){AppText("Отмена")}})}
}
