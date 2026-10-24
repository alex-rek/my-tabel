package ru.mytabel.app

import android.app.Application
import ru.mytabel.app.data.*

class TabelApplication : Application() {
    val repository by lazy { Repository(TabelDatabase.get(this)) }
}
