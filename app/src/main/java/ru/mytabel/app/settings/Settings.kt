package ru.mytabel.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class AppSettings(val language:String="ru",val theme:String="SYSTEM",val palette:String="BLUE",val currency:String="USD",val weekStart:Int=1,val hour24:Boolean=true)
object DisplayPreferences { @Volatile var currency="USD" }
class SettingsStore(context:Context) {
    private val prefs=context.getSharedPreferences("appearance",0)
    val state=MutableStateFlow(AppSettings(prefs.getString("language","ru")!!,prefs.getString("theme","SYSTEM")!!,prefs.getString("palette","BLUE")!!,prefs.getString("currency","USD")!!,prefs.getInt("weekStart",1),prefs.getBoolean("hour24",true)))
    init{DisplayPreferences.currency=state.value.currency}
    fun update(settings:AppSettings){
        require(settings.language in listOf("ru","en") && settings.theme in listOf("SYSTEM","LIGHT","DARK") && settings.palette in listOf("BLUE","PURPLE","GREEN","ORANGE") && settings.currency in listOf("USD","EUR","RUB","GBP","CAD"))
        require(settings.weekStart in 1..7)
        prefs.edit().putString("language",settings.language).putString("theme",settings.theme).putString("palette",settings.palette).putString("currency",settings.currency).putInt("weekStart",settings.weekStart).putBoolean("hour24",settings.hour24).apply()
        DisplayPreferences.currency=settings.currency;state.value=settings
    }
}
