package ru.mytabel.app.reports

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import android.webkit.WebView
import android.webkit.WebViewClient

object Printing {
    fun print(activity:Activity,report:ReportData){
        val view=WebView(activity)
        view.settings.javaScriptEnabled=false
        view.settings.allowFileAccess=false
        var submitted=false
        view.webViewClient=object:WebViewClient(){
            override fun onPageFinished(webView:WebView,url:String?){
                if(submitted)return
                submitted=true
                val delegate=webView.createPrintDocumentAdapter("My Tabel")
                val adapter=object:PrintDocumentAdapter(){
                    override fun onStart()=delegate.onStart()
                    override fun onLayout(old:PrintAttributes?,new:PrintAttributes?,signal:CancellationSignal?,callback:LayoutResultCallback?,extras:Bundle?)=delegate.onLayout(old,new,signal,callback,extras)
                    override fun onWrite(pages:Array<out PageRange>?,destination:ParcelFileDescriptor?,signal:CancellationSignal?,callback:WriteResultCallback?)=delegate.onWrite(pages,destination,signal,callback)
                    override fun onFinish(){delegate.onFinish();webView.destroy()}
                }
                (activity.getSystemService(Context.PRINT_SERVICE) as PrintManager).print("My Tabel",adapter,PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape()).build())
            }
        }
        view.loadDataWithBaseURL(null,Reports.html(report),"text/html","UTF-8",null)
    }
}
