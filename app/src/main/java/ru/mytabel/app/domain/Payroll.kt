package ru.mytabel.app.domain

import ru.mytabel.app.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.temporal.TemporalAdjusters

data class ShiftPay(val shift: Shift, val elapsedSeconds: Long, val paidSeconds: Long, val regularSeconds: Long, val overtimeSeconds: Long, val doubleSeconds: Long, val grossCents: Long)
data class Balance(val gross: Long, val received: Long, val actual: Long, val estimate: Long) {
    val due get() = gross - received - actual
}
data class Period(val from: LocalDate, val through: LocalDate)

object Payroll {
    // Workday boundaries follow employer time zone, including DST. Unlocated unpaid breaks
    // are deducted from the end of the shift; this policy is visible in the shift editor.
    fun calculate(employer: Employer, shifts: List<Shift>): List<ShiftPay> {
        data class Slice(val shift: Shift, val day: LocalDate, val seconds: Long, val start: Long)
        val zone = ZoneId.of(employer.zone)
        val slices = mutableListOf<Slice>()
        val complete = shifts.filter { it.employerId == employer.id && it.end != null }.sortedWith(compareBy<Shift> { it.start }.thenBy { it.id })
        for (s in complete) {
            var cursor = Instant.ofEpochMilli(s.start)
            val end = if(s.paidSecondsOverride != null) cursor.plusSeconds(s.paidSecondsOverride) else Instant.ofEpochMilli(s.end!!).minusSeconds(s.breakMinutes * 60L).coerceAtLeast(cursor)
            while (cursor < end) {
                val local = cursor.atZone(zone)
                var day = local.toLocalDate()
                if (local.toLocalTime().toSecondOfDay() < employer.workdayStartMinutes * 60) day = day.minusDays(1)
                val boundary = day.plusDays(1).atStartOfDay().plusMinutes(employer.workdayStartMinutes.toLong()).atZone(zone).toInstant()
                val next = minOf(end, boundary)
                slices += Slice(s, day, Duration.between(cursor, next).seconds, cursor.toEpochMilli())
                cursor = next
            }
        }
        fun week(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(employer.weekStart)))
        val workedDays = slices.filter { it.seconds > 0 }.map { it.day }.toSet()
        val daily = mutableMapOf<LocalDate, Long>()
        val weeklyRegular = mutableMapOf<LocalDate, Long>()
        val values = mutableMapOf<Long, LongArray>()
        slices.sortedWith(compareBy<Slice> { it.start }.thenBy { it.shift.id }).forEach { p ->
            val w = week(p.day)
            val d = daily[p.day] ?: 0L
            val seventh = employer.seventhDay && p.day == w.plusDays(6) && (0L..6L).all { w.plusDays(it) in workedDays }
            val regularLimit = if (seventh) 0L else employer.dailyHours * 3600L
            val doubleLimit = if (seventh) employer.dailyHours * 3600L else employer.doubleHours * 3600L
            var normal = if (employer.overtime) minOf(p.seconds, (regularLimit - d).coerceAtLeast(0)) else p.seconds
            val double = if (employer.overtime) (d + p.seconds - maxOf(d, doubleLimit)).coerceAtLeast(0) else 0L
            var overtime = p.seconds - normal - double
            if (employer.overtime) {
                val used = weeklyRegular[w] ?: 0L
                val weeklyOT = (normal - (employer.weeklyHours * 3600L - used).coerceAtLeast(0)).coerceAtLeast(0)
                normal -= weeklyOT
                overtime += weeklyOT
            }
            weeklyRegular[w] = (weeklyRegular[w] ?: 0L) + normal
            daily[p.day] = d + p.seconds
            val v = values.getOrPut(p.shift.id) { LongArray(3) }
            v[0] += normal; v[1] += overtime; v[2] += double
        }
        return complete.map { s ->
            val v = values[s.id] ?: LongArray(3)
            val weighted = BigDecimal(v[0]).add(BigDecimal(v[1]).multiply(BigDecimal(employer.overtimePercent)).divide(BigDecimal(100)))
                .add(BigDecimal(v[2]).multiply(BigDecimal(employer.doublePercent)).divide(BigDecimal(100)))
            val cents = weighted.multiply(BigDecimal(s.rateCents)).divide(BigDecimal(3600), 0, RoundingMode.HALF_UP).longValueExact()
            ShiftPay(s, (s.end!! - s.start) / 1000, v.sum(), v[0], v[1], v[2], cents)
        }
    }

    fun period(employer: Employer, date: LocalDate): Period = when (employer.period) {
        "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(employer.weekStart))).let { Period(it, it.plusDays(6)) }
        "BIWEEK" -> LocalDate.ofEpochDay(employer.periodAnchor).let { a -> a.plusDays(Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(a, date), 14) * 14) }.let { Period(it, it.plusDays(13)) }
        "HALF" -> if (date.dayOfMonth <= 15) Period(date.withDayOfMonth(1), date.withDayOfMonth(15)) else Period(date.withDayOfMonth(16), date.withDayOfMonth(date.lengthOfMonth()))
        else -> Period(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()))
    }

    fun balance(employers: List<Employer>, allPay: List<ShiftPay>, payments: List<Payment>, through: LocalDate, from: LocalDate? = null): Balance {
        var gross = 0L; var received = 0L; var actual = 0L; var estimate = 0L
        employers.forEach { e ->
            val entries = allPay.filter { it.shift.employerId == e.id && it.shift.date(e) <= through && (from == null || it.shift.date(e) >= from) }
            val amount = entries.sumOf { it.grossCents }
            val operations = payments.filter { it.employerId == e.id && it.day <= through.toEpochDay() && (from == null || it.day >= from.toEpochDay()) }
            gross += amount
            received += operations.filter { it.kind != "WITHHOLDING" }.sumOf { it.cents }
            if (e.taxMode == "MANUAL") actual += operations.filter { it.kind == "WITHHOLDING" }.sumOf { it.cents }
            if (e.taxMode == "ESTIMATE") estimate += entries.sumOf { BigDecimal(it.grossCents).multiply(BigDecimal(e.estimatePercent)).divide(BigDecimal(100), 0, RoundingMode.HALF_UP).toLong() }
        }
        return Balance(gross, received, actual, estimate)
    }
}

fun money(cents: Long, currency: String = ru.mytabel.app.settings.DisplayPreferences.currency): String = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString() + " " + when(currency){"USD"->"$";"EUR"->"€";"RUB"->"₽";"GBP"->"£";else->currency}
fun hours(seconds: Long): String = "%d:%02d".format(seconds / 3600, (seconds % 3600) / 60)
fun parseMoney(value: String): Long = value.trim().replace(',', '.').toBigDecimal().setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
