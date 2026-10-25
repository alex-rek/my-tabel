package ru.mytabel.app.data

import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.*

object LegacyImport {
    data class Row(val key:String,val date:LocalDate,val paidMinutes:Int,val rate:Long,val startMinutes:Int,val endMinutes:Int,val rest:Int,val payment:Long,val note:String,val raw:String)
    data class Plan(val rows:List<Row>) {
        val shifts get()=rows.count{it.paidMinutes>0}
        val payments get()=rows.count{it.payment>0}
        val mismatches get()=rows.count{it.paidMinutes>0 && it.paidMinutes!=Math.floorMod(it.endMinutes-it.startMinutes,1440)-it.rest}
    }
    fun parseCsv(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val field=StringBuilder();var quoted=false;var i=0
        val input=text.removePrefix("\uFEFF")
        while(i<input.length){val c=input[i];when{
            c=='"' && quoted && i+1<input.length && input[i+1]=='"'->{field.append('"');i++}
            c=='"'->quoted=!quoted
            c==','&&!quoted->{row.add(field.toString());field.clear()}
            (c=='\n'||c=='\r')&&!quoted->{row.add(field.toString());field.clear();if(row.any{it.isNotBlank()})rows.add(row);row=mutableListOf();if(c=='\r'&&i+1<input.length&&input[i+1]=='\n')i++}
            else->field.append(c)
        };i++}
        require(!quoted){"Незакрытые кавычки в CSV"}
        row.add(field.toString());if(row.any{it.isNotBlank()})rows.add(row)
        return rows
    }
    fun plan(text:String):Plan {
        val rows=parseCsv(text);require(rows.isNotEmpty()){"Файл пуст"}
        return Plan(rows.mapIndexed{index,c->
            try {
                require(c.size>=26){"Ожидается резервная копия Work Hours с 26 полями"}
                fun decimal(i:Int)=if(c[i].isBlank())BigDecimal.ZERO else c[i].trim().toBigDecimal()
                fun minutes(i:Int)=decimal(i).multiply(BigDecimal(60)).setScale(0,RoundingMode.HALF_UP).intValueExact()
                val paid=minutes(1);val start=minutes(23);val end=minutes(24);val rest=minutes(25)
                val rate=decimal(5).movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact()
                val payment=decimal(18).movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact()
                require(paid in 0..1440 && start in 0..1440 && end in 0..1440 && rest in 0..1440 && rate>=0 && payment>=0){"Некорректное время или сумма"}
                require(decimal(12).signum()==0 && decimal(15).signum()==0){"Несколько тарифов в строке пока не поддерживаются"}
                val raw=c.joinToString("\u001F")
                val key=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
                Row(key,LocalDate.parse(c[2]),paid,rate,start,end,rest,payment,listOf(c[8],c[10]).filter{it.isNotBlank()}.joinToString(" · "),raw)
            }catch(e:Exception){throw IllegalArgumentException("CSV, строка ${index+1}: ${e.message}")}
        })
    }
    fun stored(raw:String):Row=plan(raw.split('\u001F').joinToString(","){"\"${it.replace("\"","\"\"")}\""}).rows.single()
    suspend fun apply(repository:Repository,plan:Plan,employerId:Long?,name:String,paymentKind:String="ADVANCE"):Pair<Int,Int> = repository.db.withTransaction {
        require(paymentKind in listOf("ADVANCE","SALARY"))
        val dao=repository.dao
        val id=employerId ?: dao.saveEmployer(Employer(name=name.trim().ifBlank{"Импорт Work Hours"},rateCents=plan.rows.last().rate,overtime=false,color=0xFF7C3AED))
        val employer=requireNotNull(dao.employer(id))
        val known=dao.allImports().filter{it.employerId==id}.map{it.key}.toSet()
        val batchId=java.util.UUID.randomUUID().toString();val importedAt=System.currentTimeMillis()
        var imported=0;var skipped=0
        plan.rows.forEach{row->
            val key="$id:${row.key}"
            if(key in known){skipped++;return@forEach}
            val record=ImportRecord(key,id,row.date.toEpochDay(),row.note,row.raw,batchId,importedAt)
            if(dao.saveImport(record)==-1L){skipped++;return@forEach}
            var shiftId:Long?=null;var paymentId:Long?=null
            if(row.paidMinutes>0){
                val zone=ZoneId.of(employer.zone)
                val start=row.date.atStartOfDay().plusMinutes(row.startMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
                var end=row.date.atStartOfDay().plusMinutes(row.endMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
                if(end<=start)end=row.date.plusDays(1).atStartOfDay().plusMinutes(row.endMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
                shiftId=dao.saveShift(Shift(employerId=id,start=start,end=end,rateCents=row.rate,breakMinutes=row.rest,comment=row.note,paidSecondsOverride=row.paidMinutes*60L))
            }
            if(row.payment>0)paymentId=dao.savePayment(Payment(employerId=id,day=row.date.toEpochDay(),cents=row.payment,kind=paymentKind,comment="Импорт Work Hours · ${row.note}"))
            dao.updateImport(record.copy(shiftId=shiftId,paymentId=paymentId))
            imported++
        }
        imported to skipped
    }
}
