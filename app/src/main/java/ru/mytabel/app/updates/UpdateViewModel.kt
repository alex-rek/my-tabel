package ru.mytabel.app.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class UpdateState(val busy:Boolean=false,val checked:Boolean=false,val release:UpdateRelease?=null,val file:File?=null,val progress:Float?=null,val error:String?=null)
class UpdateViewModel(app:Application):AndroidViewModel(app) {
    val updater=GitHubUpdater(app)
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    fun check(){if(mutable.value.busy)return;mutable.value=UpdateState(busy=true)
        viewModelScope.launch{try{mutable.value=UpdateState(checked=true,release=updater.check())}
        catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=UpdateState(error=e.message ?: "Не удалось проверить обновления")}}
    }
    fun download(){val release=mutable.value.release ?: return;if(mutable.value.busy)return
        mutable.value=mutable.value.copy(busy=true,error=null,progress=0f,file=null)
        viewModelScope.launch{try{
            val file=updater.download(release){mutable.value=mutable.value.copy(progress=it)}
            mutable.value=mutable.value.copy(busy=false,file=file,progress=null)
        }catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(busy=false,progress=null,error=e.message ?: "Не удалось скачать обновление")}}
    }
    fun error(message:String){mutable.value=mutable.value.copy(error=message)}
}
