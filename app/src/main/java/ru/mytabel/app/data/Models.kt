package ru.mytabel.app.data

import androidx.room.*
import java.time.*

@Entity(tableName = "employers")
data class Employer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF55765B,
    val rateCents: Long = 2000,
    val weekStart: Int = 1,
    val workdayStartMinutes: Int = 0,
    val period: String = "MONTH",
    val periodAnchor: Long = LocalDate.now().toEpochDay(),
    val breakMinutes: Int = 0,
    val overtime: Boolean = true,
    val dailyHours: Int = 8,
    val doubleHours: Int = 12,
    val weeklyHours: Int = 40,
    val seventhDay: Boolean = true,
    val overtimePercent: Int = 150,
    val doublePercent: Int = 200,
    val taxMode: String = "NONE",
    val estimatePercent: Int = 20,
    val zone: String = ZoneId.systemDefault().id
)

@Entity(tableName = "shifts", foreignKeys = [ForeignKey(entity = Employer::class, parentColumns = ["id"], childColumns = ["employerId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("employerId")])
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employerId: Long,
    val start: Long,
    val end: Long? = null,
    val rateCents: Long,
    val breakMinutes: Int = 0,
    val comment: String = ""
)

@Entity(tableName = "payments", foreignKeys = [ForeignKey(entity = Employer::class, parentColumns = ["id"], childColumns = ["employerId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("employerId")])
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employerId: Long,
    val day: Long,
    val cents: Long,
    val kind: String = "SALARY",
    val comment: String = ""
)

@Entity(tableName = "report_templates")
data class ReportTemplate(@PrimaryKey val name: String, val columns: String)

data class Snapshot(val employers: List<Employer> = emptyList(), val shifts: List<Shift> = emptyList(), val payments: List<Payment> = emptyList(), val templates: List<ReportTemplate> = emptyList())

fun Shift.date(employer: Employer): LocalDate = Instant.ofEpochMilli(start).atZone(ZoneId.of(employer.zone)).toLocalDate()
