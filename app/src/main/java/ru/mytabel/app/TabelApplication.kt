package ru.mytabel.app

import android.app.Application
import ru.mytabel.app.data.*

class TabelApplication : Application() {
    val settings by lazy { ru.mytabel.app.settings.SettingsStore(this) }
    override fun onCreate(){super.onCreate();settings}
    val repository by lazy { Repository(TabelDatabase.get(this)) }
}
