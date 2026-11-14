package ru.mytabel.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.mytabel.app.updates.UpdateViewModel

@Composable fun UpdatePanel(vm:UpdateViewModel=viewModel()) {
    val state by vm.state.collectAsState();val context=LocalContext.current
    HorizontalDivider()
    AppText("Обновление приложения",style=MaterialTheme.typography.titleLarge)
    AppText("Установлена версия: ${vm.updater.installedVersion()}")
    AppText("Источник: GitHub · alex-rek/my-tabel",style=MaterialTheme.typography.bodySmall)
    OutlinedButton({vm.check()},enabled=!state.busy){AppText("Проверить обновления")}
    if(state.busy){
        val progress=state.progress
        if(progress==null){LinearProgressIndicator(Modifier.fillMaxWidth());AppText("Проверка обновлений…")}
        else {LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());AppText("Загрузка APK: ${(progress*100).toInt()}%")}
    }
    if(state.checked&&state.release==null&&!state.busy)AppText("Установлена последняя версия")
    state.release?.let{release->
        AppText("Доступна версия: ${release.tag.removePrefix("v")}",style=MaterialTheme.typography.titleMedium)
        if(release.notes.isNotBlank())AppText(release.notes,style=MaterialTheme.typography.bodySmall)
        if(state.file==null)Button({vm.download()},enabled=!state.busy){AppText("Скачать обновление")}
        else Button({
            try {
                if(!context.packageManager.canRequestPackageInstalls()){
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
                    vm.error("Разрешите установку из этого источника, вернитесь и нажмите «Установить».")
                }else context.startActivity(vm.updater.installIntent(state.file!!,release))
            }catch(e:Exception){vm.error(e.message ?: "Не удалось открыть установку")}
        }){AppText("Установить")}
        AppText("Android запросит подтверждение установки. Данные приложения сохранятся.",style=MaterialTheme.typography.bodySmall)
    }
    state.error?.let{AppText(it,color=MaterialTheme.colorScheme.error)}
    Spacer(Modifier.height(4.dp))
}
