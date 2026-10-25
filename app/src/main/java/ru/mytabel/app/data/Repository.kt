package ru.mytabel.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import java.time.Instant

class Repository(val db: TabelDatabase) {
    val dao = db.dao()
    private val core = combine(dao.employers(), dao.shifts(), dao.payments(), dao.templates()) { e, s, p, t -> Snapshot(e, s, p, t) }
    val snapshot = combine(core, dao.rules(), dao.scheduleDays(), dao.imports()) { s,r,d,i -> s.copy(rules=r,scheduleDays=d,imports=i) }
    suspend fun snapshotNow() = db.withTransaction { Snapshot(dao.allEmployers(),dao.allShifts(),dao.allPayments(),dao.allTemplates(),dao.allRules(),dao.allScheduleDays(),dao.allImports()) }
    suspend fun saveWeek(days:List<ScheduleDay>,today:java.time.LocalDate)=db.withTransaction{
        require(days.all{java.time.LocalDate.ofEpochDay(it.day)>=today.minusDays(7)}){"Можно редактировать только последние 7 дней и будущие дни"}
        days.forEach{require(dao.employer(it.employerId)!=null);ru.mytabel.app.domain.Schedules.validate("1",it.startMinutes,it.endMinutes,it.breakMinutes)}
        days.forEach{dao.saveScheduleDay(it)}
    }
    suspend fun start(employerId: Long) = db.withTransaction {
        val e = requireNotNull(dao.employer(employerId)) { "Работодатель не найден" }
        require(dao.allShifts().none { it.employerId == employerId && it.end == null }) { "У этого работодателя уже идёт смена" }
        saveShift(Shift(employerId = e.id, start = Instant.now().toEpochMilli(), rateCents = e.rateCents, breakMinutes = e.breakMinutes))
    }
    suspend fun stop(employerId: Long, now:Long=System.currentTimeMillis()) = db.withTransaction {
        val shift = dao.allShifts().firstOrNull { it.employerId == employerId && it.end == null } ?: return@withTransaction
        require(now >= shift.start) { "Время устройства раньше начала смены. Исправьте время или смену вручную." }
        val resumed=ru.mytabel.app.domain.Breaks.resume(shift,now)
        dao.saveShift(resumed.copy(end=now,breakMinutes=if(resumed.timedBreaks==null)minOf(resumed.breakMinutes,((now-shift.start)/60000).toInt())else(ru.mytabel.app.domain.Breaks.seconds(resumed,now)/60).toInt()))
    }
    suspend fun pause(employerId:Long,shiftId:Long,paused:Boolean,now:Long=System.currentTimeMillis())=db.withTransaction{
        val s=dao.allShifts().firstOrNull{it.employerId==employerId&&it.id==shiftId&&it.end==null} ?: return@withTransaction
        require(now>=s.start){"Время устройства раньше начала смены"}
        if(paused && s.pauseStartedAt==null){require(s.paidSecondsOverride==null);val changed=s.copy(timedBreaks=s.timedBreaks ?: "[]",pauseStartedAt=now,breakMinutes=if(s.timedBreaks==null)0 else s.breakMinutes);ru.mytabel.app.domain.Breaks.validate(changed);dao.saveShift(changed)}
        else if(!paused && s.pauseStartedAt!=null)dao.saveShift(ru.mytabel.app.domain.Breaks.resume(s,now))
    }
    suspend fun saveShift(shift: Shift) = db.withTransaction {
        ru.mytabel.app.domain.Breaks.validate(shift)
        require(shift.rateCents >= 0 && shift.breakMinutes >= 0) { "Ставка и перерыв не могут быть отрицательными" }
        val others = dao.allShifts().filter { it.employerId == shift.employerId && it.id != shift.id }
        require(shift.end == null || shift.end > shift.start) { "Окончание должно быть позже начала" }
        require(shift.end == null || shift.breakMinutes * 60000L <= shift.end - shift.start) { "Перерыв длиннее смены" }
        require(others.none { shift.start < (it.end ?: Long.MAX_VALUE) && it.start < (shift.end ?: Long.MAX_VALUE) }) { "Смены у одного работодателя не должны пересекаться" }
        dao.saveShift(shift)
    }
}
