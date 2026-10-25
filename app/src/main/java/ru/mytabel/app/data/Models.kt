package ru.mytabel.app.data

import androidx.room.*
import java.time.*

@Entity(tableName = "employers")
data class Employer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF00855A,
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
    val comment: String = "",
    val paidSecondsOverride: Long? = null,
    val timedBreaks: String? = null,
    val pauseStartedAt: Long? = null
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

@Entity(tableName = "schedule_rules", indices = [Index("employerId")])
data class ScheduleRule(@PrimaryKey(autoGenerate = true) val id: Long = 0, val employerId: Long, val effectiveFrom: Long,
    val anchor: Long, val pattern: String = "1111100", val startMinutes: Int = 540, val endMinutes: Int = 1080, val breakMinutes: Int = 60)

@Entity(tableName = "schedule_days", primaryKeys = ["employerId", "day"])
data class ScheduleDay(val employerId: Long, val day: Long, val working: Boolean, val startMinutes: Int = 540, val endMinutes: Int = 1080, val breakMinutes: Int = 60)

@Entity(tableName = "import_records", indices = [Index("employerId")])
data class ImportRecord(@PrimaryKey val key: String, val employerId: Long, val day: Long, val note: String, val raw: String,
    val batchId:String?=null,@ColumnInfo(defaultValue="0") val importedAt:Long=0,val shiftId:Long?=null,val paymentId:Long?=null)

data class Snapshot(val employers: List<Employer> = emptyList(), val shifts: List<Shift> = emptyList(), val payments: List<Payment> = emptyList(), val templates: List<ReportTemplate> = emptyList(),
    val rules: List<ScheduleRule> = emptyList(), val scheduleDays: List<ScheduleDay> = emptyList(), val imports: List<ImportRecord> = emptyList())

fun Shift.date(employer: Employer): LocalDate = Instant.ofEpochMilli(start).atZone(ZoneId.of(employer.zone)).toLocalDate()
