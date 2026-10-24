package ru.mytabel.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import java.time.Instant

class Repository(val db: TabelDatabase) {
    val dao = db.dao()
    private val core = combine(dao.employers(), dao.shifts(), dao.payments(), dao.templates()) { e, s, p, t -> Snapshot(e, s, p, t) }
    val snapshot = combine(core, dao.rules(), dao.scheduleDays(), dao.imports()) { s,r,d,i -> s.copy(rules=r,scheduleDays=d,imports=i) }
    suspend fun snapshotNow() = db.withTransaction { Snapshot(dao.allEmployers(),dao.allShifts(),dao.allPayments(),dao.allTemplates(),dao.allRules(),dao.allScheduleDays(),dao.allImports()) }
    suspend fun start(employerId: Long) = db.withTransaction {
        val e = requireNotNull(dao.employer(employerId)) { "Работодатель не найден" }
        require(dao.allShifts().none { it.employerId == employerId && it.end == null }) { "У этого работодателя уже идёт смена" }
        saveShift(Shift(employerId = e.id, start = Instant.now().toEpochMilli(), rateCents = e.rateCents, breakMinutes = e.breakMinutes))
    }
    suspend fun stop(employerId: Long) = db.withTransaction {
        val shift = dao.allShifts().firstOrNull { it.employerId == employerId && it.end == null } ?: return@withTransaction
        val now = System.currentTimeMillis()
        require(now >= shift.start) { "Время устройства раньше начала смены. Исправьте время или смену вручную." }
        dao.saveShift(shift.copy(end = now, breakMinutes = minOf(shift.breakMinutes, ((now - shift.start) / 60000).toInt())))
    }
    suspend fun saveShift(shift: Shift) = db.withTransaction {
        require(shift.rateCents >= 0 && shift.breakMinutes >= 0) { "Ставка и перерыв не могут быть отрицательными" }
        val others = dao.allShifts().filter { it.employerId == shift.employerId && it.id != shift.id }
        require(shift.end == null || shift.end > shift.start) { "Окончание должно быть позже начала" }
        require(shift.end == null || shift.breakMinutes * 60000L <= shift.end - shift.start) { "Перерыв длиннее смены" }
        require(others.none { shift.start < (it.end ?: Long.MAX_VALUE) && it.start < (shift.end ?: Long.MAX_VALUE) }) { "Смены у одного работодателя не должны пересекаться" }
        dao.saveShift(shift)
    }
}
