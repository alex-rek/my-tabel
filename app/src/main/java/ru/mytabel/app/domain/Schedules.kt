package ru.mytabel.app.domain

import ru.mytabel.app.data.*
import java.time.LocalDate

object Schedules {
    fun day(employer: Long, date: LocalDate, rules: List<ScheduleRule>, overrides: List<ScheduleDay>): ScheduleDay {
        val day=date.toEpochDay()
        overrides.firstOrNull{it.employerId==employer && it.day==day}?.let{return it}
        val rule=rules.filter{it.employerId==employer && it.effectiveFrom<=day}.maxWithOrNull(compareBy<ScheduleRule>{it.effectiveFrom}.thenBy{it.id}) ?: return ScheduleDay(employer,day,false)
        val index=Math.floorMod(day-rule.anchor,rule.pattern.length.toLong()).toInt()
        return ScheduleDay(employer,day,rule.pattern[index]=='1',rule.startMinutes,rule.endMinutes,rule.breakMinutes)
    }
    fun validate(pattern:String,start:Int,end:Int,rest:Int) {
        require(pattern.length in 1..31 && pattern.all{it=='1'||it=='0'}){"Цикл: от 1 до 31 символа, только 1 и 0"}
        require(start in 0..1439 && end in 0..1439){"Проверьте время"}
        require(rest>=0 && rest<=Math.floorMod(end-start-1,1440)+1){"Перерыв длиннее смены"}
    }
    fun paidMinutes(day:ScheduleDay)=if(!day.working)0 else (if(day.endMinutes>day.startMinutes)day.endMinutes-day.startMinutes else 1440-day.startMinutes+day.endMinutes)-day.breakMinutes
}
