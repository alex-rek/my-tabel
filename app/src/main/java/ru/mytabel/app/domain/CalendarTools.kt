package ru.mytabel.app.domain

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object CalendarTools {
    fun weekStart(date:LocalDate,first:Int):LocalDate=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(first)))
    fun weekdays(first:Int)=(0..6).map{(first-1+it)%7+1}
    fun offset(firstOfMonth:LocalDate,first:Int)=Math.floorMod(firstOfMonth.dayOfWeek.value-first,7)
    fun monthStep(horizontalPixels:Float,threshold:Float):Long=when{horizontalPixels<=-threshold->1;horizontalPixels>=threshold->-1;else->0}
    fun editable(date:LocalDate,today:LocalDate)=date>=today.minusDays(7)
}
object TimeFormats {
    fun time(minutes:Int,hour24:Boolean=true):String=LocalTime.of(Math.floorMod(minutes,1440)/60,Math.floorMod(minutes,60)).format(DateTimeFormatter.ofPattern(if(hour24)"HH:mm"else"h:mm a",Locale.US))
    fun dateTime(millis:Long,zone:String,hour24:Boolean=true):String=Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern(if(hour24)"dd.MM HH:mm"else"dd.MM h:mm a",Locale.US))
    fun encodeInput(hour:String,minute:String,pm:Boolean,hour24:Boolean):String?{
        val h=hour.toIntOrNull()?:return null;val m=minute.toIntOrNull()?:return null
        if(m !in 0..59||h !in if(hour24)0..23 else 1..12)return null
        val encoded=if(hour24)h else h%12+if(pm)12 else 0
        return "%02d:%02d".format(Locale.US,encoded,m)
    }
}
