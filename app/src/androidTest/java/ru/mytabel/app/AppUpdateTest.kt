package ru.mytabel.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.mytabel.app.updates.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun installerOpensForVerifiedNewerApkWhenFixtureIsProvided()=runBlocking {
        val fixture=File(context.getExternalFilesDir(null),"update-fixture.apk")
        org.junit.Assume.assumeTrue("Optional local newer-version APK fixture",fixture.isFile)
        val apk=File(context.cacheDir,"updates/update.apk").apply{parentFile!!.mkdirs()}
        fixture.copyTo(apk,overwrite=true)
        val release=UpdateRelease("v0.4.3",AppVersion(0,4,3),"",1,apk.length(),"a".repeat(64))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        try{
            assertTrue(context.packageManager.canRequestPackageInstalls())
            val intent=GitHubUpdater(context).installIntent(apk,release)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var opened=false
            repeat(50){
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()?.contains("packageinstaller")==true)opened=true
                if(!opened)kotlinx.coroutines.delay(100)
            }
            assertTrue("Android package installer did not open",opened)
        }finally{
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            apk.delete();fixture.delete()
        }
    }
    @Test @Suppress("DEPRECATION") fun validatesRealApkMetadataAndInstallerFileSharing(){
        val manager=context.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val source=context.applicationInfo.sourceDir
        val candidate=manager.getPackageArchiveInfo(source,flags)!!
        val older=manager.getPackageArchiveInfo(source,flags)!!.apply{versionCode=8;versionName="0.4.1"}
        val release=UpdateRelease("v0.4.2",AppVersion(0,4,2),"",1,File(source).length(),"a".repeat(64))
        ApkChecks.verify(candidate,older,release)
        fun rejected(block:()->Unit){try{block();fail("Expected APK rejection")}catch(expected:IllegalArgumentException){}}
        rejected{ApkChecks.verify(candidate,candidate,release)}
        rejected{ApkChecks.verify(candidate,older,release.copy(tag="v9.9.9"))}
        older.packageName="another.app";rejected{ApkChecks.verify(candidate,older,release)}
        older.packageName=candidate.packageName
        if(Build.VERSION.SDK_INT>=28)older.signingInfo=null else older.signatures=null
        rejected{ApkChecks.verify(candidate,older,release)}
        val dir=File(context.cacheDir,"updates").apply{mkdirs()};val file=File(dir,"provider-test.apk")
        try{
            file.writeText("APK test")
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
            assertEquals("content",uri.scheme)
            assertEquals("APK test",context.contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()})
            assertEquals(PackageManager.PERMISSION_GRANTED,manager.checkPermission("android.permission.INTERNET",context.packageName))
            val info=manager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
            assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
            try{GitHubUpdater(context).verify(file,release);fail("Invalid APK accepted")}catch(expected:IllegalStateException){}
        }finally{file.delete()}
    }
    @Test fun publicGitHubCheckAndRealDownloadRejectDowngradeAndRemovePartialFile()=runBlocking {
        val updater=GitHubUpdater(context)
        assertEquals("0.4.2",updater.installedVersion())
        // Check the published catalog without authentication and download an actual older release.
        val release=updater.check("0.0.0")!!
        if(release.version>=AppVersion(0,4,2))return@runBlocking // Future releases can be newer than this test build.
        var progress=0f
        try{updater.download(release){progress=it};fail("Downgrade accepted")}
        catch(expected:IllegalArgumentException){assertTrue(expected.message.orEmpty().contains("Версия APK"))}
        assertEquals(1f,progress)
        assertFalse(File(context.cacheDir,"updates/update.part").exists())
        assertFalse(File(context.cacheDir,"updates/update.apk").exists())
        assertNull(updater.check())
    }
}
