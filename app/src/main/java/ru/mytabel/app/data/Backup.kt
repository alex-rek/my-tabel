package ru.mytabel.app.data

import androidx.room.withTransaction
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import ru.mytabel.app.settings.AppSettings
import java.time.*

data class BackupEnvelope(val format:String="my-tabel",val version:Int=1,val createdAt:String=Instant.now().toString(),val settings:AppSettings,val data:Snapshot)
object Backup {
    private val gson=GsonBuilder().setPrettyPrinting().create()
    fun encode(settings:AppSettings,data:Snapshot)=gson.toJson(BackupEnvelope(settings=settings,data=data))
    fun decode(text:String):BackupEnvelope {
        val json=JsonParser.parseString(text).asJsonObject
        json.getAsJsonObject("settings")?.let{if(!it.has("weekStart"))it.addProperty("weekStart",1);if(!it.has("hour24"))it.addProperty("hour24",true)}
        val b=gson.fromJson(json,BackupEnvelope::class.java) ?: error("Пустая копия")
        require(b.format=="my-tabel" && b.version==1){"Неподдерживаемый формат резервной копии"}
        require(b.settings.language in listOf("ru","en") && b.settings.currency in listOf("USD","EUR","GBP","RUB","CAD") && b.settings.theme in listOf("SYSTEM","LIGHT","DARK") && b.settings.palette in listOf("BLUE","PURPLE","GREEN","ORANGE")){"Некорректные настройки"}
        require(b.settings.weekStart in 1..7)
        val s=b.data;val ids=s.employers.map{it.id}.toSet()
        require(ids.size==s.employers.size && ids.all{it>0}){"Повторяющиеся работодатели"}
        s.employers.forEach{require(it.name.isNotBlank()&&it.rateCents>=0&&it.weekStart in 1..7&&it.workdayStartMinutes in 0..1439&&it.dailyHours in 1..24&&it.doubleHours in it.dailyHours..24&&it.weeklyHours in 1..168&&it.overtimePercent in 100..1000&&it.doublePercent in it.overtimePercent..1000&&it.estimatePercent in 0..100);ZoneId.of(it.zone)}
        s.shifts.forEach{ru.mytabel.app.domain.Breaks.validate(it);require(it.employerId in ids&&it.rateCents>=0&&it.breakMinutes>=0&&(it.end==null||it.end>it.start)&&(it.paidSecondsOverride==null||it.paidSecondsOverride in 0..86400));Instant.ofEpochMilli(it.start)}
        require(s.shifts.filter{it.end==null}.groupBy{it.employerId}.all{it.value.size==1}){"Две активные смены у одного работодателя"}
        s.payments.forEach{require(it.employerId in ids&&it.cents>0&&it.kind in listOf("SALARY","ADVANCE","WITHHOLDING"));LocalDate.ofEpochDay(it.day)}
        s.rules.forEach{require(it.employerId in ids);ru.mytabel.app.domain.Schedules.validate(it.pattern,it.startMinutes,it.endMinutes,it.breakMinutes)}
        s.scheduleDays.forEach{require(it.employerId in ids);ru.mytabel.app.domain.Schedules.validate("1",it.startMinutes,it.endMinutes,it.breakMinutes)}
        s.imports.forEach{require(it.employerId in ids)}
        return b
    }
    suspend fun restore(repository:Repository,b:BackupEnvelope)=repository.db.withTransaction {
        val dao=repository.dao;val existing=repository.snapshotNow();val employerIds=mutableMapOf<Long,Long>()
        b.data.employers.forEach{e->employerIds[e.id]=existing.employers.firstOrNull{it.copy(id=0)==e.copy(id=0)}?.id ?: dao.saveEmployer(e.copy(id=0))}
        val shifts=existing.shifts.toMutableList();val payments=existing.payments.toMutableList();val rules=existing.rules.toMutableList()
        val shiftIds=mutableMapOf<Long,Long>();val paymentIds=mutableMapOf<Long,Long>()
        b.data.shifts.forEach{s->val v=s.copy(id=0,employerId=employerIds.getValue(s.employerId));val match=shifts.indexOfFirst{it.copy(id=0)==v};shiftIds[s.id]=if(match>=0)shifts.removeAt(match).id else{require(v.end!=null||dao.allShifts().none{it.employerId==v.employerId&&it.end==null}){"У работодателя уже идёт другая смена"};dao.saveShift(v)}}
        b.data.payments.forEach{p->val v=p.copy(id=0,employerId=employerIds.getValue(p.employerId));val match=payments.indexOfFirst{it.copy(id=0)==v};paymentIds[p.id]=if(match>=0)payments.removeAt(match).id else dao.savePayment(v)}
        b.data.rules.forEach{r->val v=r.copy(id=0,employerId=employerIds.getValue(r.employerId));val match=rules.indexOfFirst{it.copy(id=0)==v};if(match>=0)rules.removeAt(match)else dao.saveRule(v)}
        b.data.scheduleDays.forEach{d->val v=d.copy(employerId=employerIds.getValue(d.employerId));if(existing.scheduleDays.none{it.employerId==v.employerId&&it.day==v.day})dao.saveScheduleDay(v)}
        b.data.imports.forEach{i->val id=employerIds.getValue(i.employerId);dao.saveImport(i.copy(employerId=id,key="$id:${i.key.substringAfter(':')}",shiftId=i.shiftId?.let{shiftIds[it]},paymentId=i.paymentId?.let{paymentIds[it]}))}
        b.data.templates.forEach{t->if(existing.templates.none{it.name==t.name})dao.saveTemplate(t)}
    }
}
