package ru.mytabel.app.data

import androidx.room.withTransaction
import java.time.*

data class RemovalPreview(val shifts:Int,val payments:Int,val notes:Int,val scheduleDays:Int=0,val unresolved:Int=0)
object Maintenance {
    fun batchKey(record:ImportRecord)="${record.employerId}:${record.batchId ?: "legacy"}"
    private fun selectedRecords(s:Snapshot,batch:String)=s.imports.filter{batchKey(it)==batch}

    // Version 0.2 did not store created row IDs. Link only unique exact matches.
    // Ambiguous or edited legacy rows are kept; the UI can offer explicit period cleanup.
    private suspend fun linkLegacy(repository:Repository){
        val s=repository.snapshotNow();val usedShifts=s.imports.mapNotNull{it.shiftId}.toMutableSet();val usedPayments=s.imports.mapNotNull{it.paymentId}.toMutableSet()
        s.imports.filter{it.batchId==null}.forEach{record->
            val e=s.employers.firstOrNull{it.id==record.employerId}?:return@forEach
            val row=runCatching{LegacyImport.stored(record.raw)}.getOrNull()?:return@forEach
            val start=row.date.atStartOfDay().plusMinutes(row.startMinutes.toLong()).atZone(ZoneId.of(e.zone)).toInstant().toEpochMilli()
            var end=row.date.atStartOfDay().plusMinutes(row.endMinutes.toLong()).atZone(ZoneId.of(e.zone)).toInstant().toEpochMilli()
            if(end<=start)end=row.date.plusDays(1).atStartOfDay().plusMinutes(row.endMinutes.toLong()).atZone(ZoneId.of(e.zone)).toInstant().toEpochMilli()
            val shift=record.shiftId ?: s.shifts.filter{it.id !in usedShifts && it.employerId==e.id && it.start==start && it.end==end && it.rateCents==row.rate && it.breakMinutes==row.rest && it.comment==row.note && it.paidSecondsOverride==row.paidMinutes*60L}.singleOrNull()?.id
            val payment=record.paymentId ?: s.payments.filter{it.id !in usedPayments && it.employerId==e.id && it.day==record.day && it.cents==row.payment && it.comment=="Импорт Work Hours · ${row.note}"}.singleOrNull()?.id
            shift?.let{usedShifts.add(it)};payment?.let{usedPayments.add(it)}
            val resolved=(row.paidMinutes==0||shift!=null)&&(row.payment==0L||payment!=null)
            repository.dao.updateImport(record.copy(batchId=if(resolved)"legacy"else null,shiftId=shift,paymentId=payment))
        }
    }
    private fun undoPreview(s:Snapshot,batch:String):RemovalPreview {
        val records=selectedRecords(s,batch);val shiftIds=records.mapNotNull{it.shiftId}.toSet();val paymentIds=records.mapNotNull{it.paymentId}.toSet()
        return RemovalPreview(s.shifts.count{it.id in shiftIds},s.payments.count{it.id in paymentIds},records.size,unresolved=records.count{it.batchId==null})
    }
    suspend fun previewUndo(repository:Repository,batch:String):RemovalPreview=repository.db.withTransaction{
        linkLegacy(repository);undoPreview(repository.snapshotNow(),batch)
    }
    suspend fun undoImport(repository:Repository,batch:String):RemovalPreview=repository.db.withTransaction{
        linkLegacy(repository);val s=repository.snapshotNow();val records=selectedRecords(s,batch);val preview=undoPreview(s,batch)
        require(preview.unresolved==0){"Старые записи не удалось связать однозначно. Используйте очистку периода."}
        val shiftIds=records.mapNotNull{it.shiftId}.toSet();val paymentIds=records.mapNotNull{it.paymentId}.toSet()
        require(s.shifts.none{it.id in shiftIds&&it.end==null}){"Сначала завершите активную смену"}
        s.shifts.filter{it.id in shiftIds}.forEach{repository.dao.deleteShift(it)}
        s.payments.filter{it.id in paymentIds}.forEach{repository.dao.deletePayment(it)}
        records.forEach{repository.dao.deleteImport(it)}
        preview
    }
    fun previewPeriod(s:Snapshot,ids:Set<Long>,from:LocalDate,through:LocalDate,graph:Boolean):RemovalPreview {
        require(ids.isNotEmpty()&&from<=through){"Выберите работодателя и проверьте даты"}
        if(graph)require(java.time.temporal.ChronoUnit.DAYS.between(from,through)<=3660){"График можно очистить за период до 10 лет"}
        val shifts=s.shifts.filter{it.employerId in ids && it.end!=null && it.date(s.employers.first{e->e.id==it.employerId}) in from..through}
        val payments=s.payments.filter{it.employerId in ids && it.day in from.toEpochDay()..through.toEpochDay()}
        return RemovalPreview(shifts.size,payments.size,s.imports.count{it.employerId in ids&&it.day in from.toEpochDay()..through.toEpochDay()},if(graph)(java.time.temporal.ChronoUnit.DAYS.between(from,through).toInt()+1)*ids.size else 0)
    }
    suspend fun clearPeriod(repository:Repository,ids:Set<Long>,from:LocalDate,through:LocalDate,graph:Boolean):RemovalPreview=repository.db.withTransaction{
        val s=repository.snapshotNow();val preview=previewPeriod(s,ids,from,through,graph)
        val removedShifts=s.shifts.filter{it.employerId in ids&&it.end!=null&&it.date(s.employers.first{e->e.id==it.employerId}) in from..through}
        val removedPayments=s.payments.filter{it.employerId in ids&&it.day in from.toEpochDay()..through.toEpochDay()}
        val retainedShifts=(s.shifts-removedShifts.toSet()).map{it.id}.toSet();val retainedPayments=(s.payments-removedPayments.toSet()).map{it.id}.toSet()
        removedShifts.forEach{repository.dao.deleteShift(it)};removedPayments.forEach{repository.dao.deletePayment(it)}
        s.imports.filter{it.employerId in ids&&it.day in from.toEpochDay()..through.toEpochDay()&&it.shiftId !in retainedShifts&&it.paymentId !in retainedPayments}.forEach{repository.dao.deleteImport(it)}
        if(graph){var date=from;while(date<=through){ids.forEach{repository.dao.saveScheduleDay(ScheduleDay(it,date.toEpochDay(),false))};date=date.plusDays(1)}}
        preview
    }
}
