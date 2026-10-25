package ru.mytabel.app.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.mytabel.app.data.*
import java.time.*

data class BreakSpan(val from:Long,val through:Long)
object Breaks {
    fun closed(s:Shift):List<BreakSpan> = s.timedBreaks?.let { raw ->
        JsonParser.parseString(raw).asJsonArray.map{v->val a=v.asJsonArray;require(a.size()==2);BreakSpan(a[0].asLong,a[1].asLong)}
    } ?: emptyList()
    fun encode(values:List<BreakSpan>):String=Gson().toJson(values.map{listOf(it.from,it.through)})
    fun validate(s:Shift){
        require(s.pauseStartedAt==null || s.timedBreaks!=null && s.end==null){"Некорректное состояние перерыва"}
        if(s.timedBreaks==null)return
        require(s.paidSecondsOverride==null){"Уберите импортированное время перед учётом перерывов"}
        var cursor=s.start
        closed(s).forEach{require(it.from>=cursor && it.through>=it.from && (s.end==null||it.through<=s.end)){"Перерывы выходят за границы смены"};cursor=it.through}
        require(s.pauseStartedAt==null || s.pauseStartedAt>=cursor){"Некорректное начало перерыва"}
    }
    fun seconds(s:Shift,now:Long=s.end ?: System.currentTimeMillis()):Long {
        if(s.timedBreaks==null)return minOf(s.breakMinutes*60L,((now-s.start)/1000).coerceAtLeast(0))
        fun tick(time:Long)=((time-s.start)/1000).coerceAtLeast(0)
        return closed(s).sumOf{tick(it.through)-tick(it.from)}+(s.pauseStartedAt?.let{(tick(now)-tick(it)).coerceAtLeast(0)} ?: 0)
    }
    fun worked(s:Shift,now:Long=s.end ?: System.currentTimeMillis())=((now-s.start)/1000-seconds(s,now)).coerceAtLeast(0)
    fun resume(s:Shift,now:Long):Shift {
        val from=s.pauseStartedAt ?: return s
        require(now>=from){"Время устройства раньше начала перерыва"}
        val log=encode(closed(s)+BreakSpan(from,now))
        val resumed=s.copy(timedBreaks=log,pauseStartedAt=null)
        return resumed.copy(breakMinutes=(seconds(resumed,now)/60).toInt())
    }
    fun workIntervals(s:Shift):List<Pair<Long,Long>> {
        val end=requireNotNull(s.end)
        if(s.timedBreaks==null)return listOf(s.start to (end-s.breakMinutes*60000L).coerceAtLeast(s.start))
        validate(s);var cursor=s.start;val result=mutableListOf<Pair<Long,Long>>()
        closed(s).forEach{if(it.from>cursor)result.add(cursor to it.from);cursor=it.through}
        if(cursor<end)result.add(cursor to end)
        return result
    }
}
data class WidgetPlan(val from:Long,val through:Long,val breakMinutes:Int){
    fun progress(now:Long)=((now-from).toDouble()/(through-from)).coerceIn(0.0,1.0).toFloat()
    val paidSeconds get()=((through-from)/1000-breakMinutes*60).coerceAtLeast(0)
}
object ShiftPlans {
    fun find(e:Employer,s:Shift?,now:Long,rules:List<ScheduleRule>,days:List<ScheduleDay>):WidgetPlan? {
        val date=Instant.ofEpochMilli(s?.start ?: now).atZone(ZoneId.of(e.zone)).toLocalDate()
        val d=Schedules.day(e.id,date,rules,days);if(!d.working)return null
        val zone=ZoneId.of(e.zone)
        val from=date.atStartOfDay().plusMinutes(d.startMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
        val endDate=if(d.endMinutes<=d.startMinutes)date.plusDays(1)else date
        val through=endDate.atStartOfDay().plusMinutes(d.endMinutes.toLong()).atZone(zone).toInstant().toEpochMilli()
        return if(through>from)WidgetPlan(from,through,d.breakMinutes)else null
    }
}
