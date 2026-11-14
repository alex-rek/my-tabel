package ru.mytabel.app.updates

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GitHubUpdater(private val context:Context) {
    private val manager get()=context.packageManager
    @Suppress("DEPRECATION")
    private fun installed()=manager.getPackageInfo(context.packageName,0)
    fun installedVersion():String=installed().versionName.orEmpty()

    private fun connection(url:URL,accept:String):HttpURLConnection {
        require(url.protocol=="https"&&url.userInfo==null&&url.port in listOf(-1,443))
        require(url.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"))
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false;connectTimeout=15000;readTimeout=30000
            setRequestProperty("User-Agent","MyTabel-Android")
            setRequestProperty("Accept",accept)
            if(url.host=="api.github.com")setRequestProperty("X-GitHub-Api-Version","2022-11-28")
        }
    }
    private fun open(url:String,accept:String):HttpURLConnection {
        var next=URL(url)
        repeat(6){
            val c=connection(next,accept)
            try {
                when(c.responseCode){
                    200->return c
                    301,302,303,307,308->{next=URL(next,c.getHeaderField("Location") ?: error("GitHub не вернул адрес загрузки"));c.disconnect()}
                    403,429->error("Лимит запросов GitHub. Повторите позже.")
                    404->error("Релизы GitHub недоступны. Проверьте доступ к репозиторию.")
                    else->error("Ошибка GitHub: HTTP ${c.responseCode}")
                }
            }catch(e:Exception){c.disconnect();throw e}
        }
        error("Слишком много перенаправлений GitHub")
    }
    suspend fun check(currentVersion:String=installedVersion()):UpdateRelease?=withContext(Dispatchers.IO){
        val c=open("https://api.github.com/repos/${ReleaseCatalog.REPOSITORY}/releases?per_page=100","application/vnd.github+json")
        try {
            val body=c.inputStream.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4_000_000){"Ответ GitHub слишком большой"};out.write(buffer,0,n)}
                out.toString("UTF-8")
            }
            ReleaseCatalog.newest(body,currentVersion)
        }finally{c.disconnect()}
    }
    suspend fun download(release:UpdateRelease,onProgress:(Float)->Unit):File=withContext(Dispatchers.IO){
        val dir=File(context.cacheDir,"updates").apply{mkdirs()}
        val part=File(dir,"update.part");val apk=File(dir,"update.apk")
        apk.delete();part.delete()
        try {
            val c=open("https://api.github.com/repos/${ReleaseCatalog.REPOSITORY}/releases/assets/${release.assetId}","application/octet-stream")
            try {
                val hash=MessageDigest.getInstance("SHA-256");var count=0L
                c.inputStream.use{input->part.outputStream().use{output->
                    val buffer=ByteArray(131072)
                    while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        count+=n;require(count<=release.size&&count<=ReleaseCatalog.MAX_APK_BYTES){"Неверный размер APK"}
                        output.write(buffer,0,n);hash.update(buffer,0,n);onProgress(count.toFloat()/release.size)
                    }
                }}
                require(count==release.size){"APK загружен не полностью"}
                require(hash.digest().joinToString(""){"%02x".format(it)}==release.sha256){"Контрольная сумма APK не совпадает"}
                verify(part,release)
                check(part.renameTo(apk)){"Не удалось сохранить APK"}
                apk
            }finally{c.disconnect()}
        }catch(e:Exception){part.delete();apk.delete();throw e}
    }
    @Suppress("DEPRECATION")
    fun verify(file:File,release:UpdateRelease) {
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate=manager.getPackageArchiveInfo(file.absolutePath,flags) ?: error("Некорректный APK")
        val current=manager.getPackageInfo(context.packageName,flags)
        ApkChecks.verify(candidate,current,release)
    }
    fun installIntent(file:File,release:UpdateRelease):Intent {
        verify(file,release)
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("APK",uri)}
    }
}

internal object ApkChecks {
    @Suppress("DEPRECATION")
    fun verify(candidate:PackageInfo,current:PackageInfo,release:UpdateRelease) {
        require(candidate.packageName==current.packageName){"APK предназначен для другого приложения"}
        require(candidate.versionName==release.tag.removePrefix("v")&&PackageInfoCompat.getLongVersionCode(candidate)>PackageInfoCompat.getLongVersionCode(current)){"Версия APK не является обновлением"}
        fun signers(info:PackageInfo):Set<String> {
            val values=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)}}.toSet()
        }
        val trusted=signers(current)
        require(trusted.isNotEmpty()&&signers(candidate)==trusted){"Подпись APK отличается от установленного приложения"}
    }
}
