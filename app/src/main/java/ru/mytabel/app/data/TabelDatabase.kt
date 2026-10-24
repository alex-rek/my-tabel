package ru.mytabel.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface TabelDao {
    @Query("SELECT * FROM employers ORDER BY name") fun employers(): Flow<List<Employer>>
    @Query("SELECT * FROM shifts ORDER BY start") fun shifts(): Flow<List<Shift>>
    @Query("SELECT * FROM payments ORDER BY day DESC, id DESC") fun payments(): Flow<List<Payment>>
    @Query("SELECT * FROM report_templates ORDER BY name") fun templates(): Flow<List<ReportTemplate>>
    @Query("SELECT * FROM employers ORDER BY name") suspend fun allEmployers(): List<Employer>
    @Query("SELECT * FROM shifts ORDER BY start") suspend fun allShifts(): List<Shift>
    @Query("SELECT * FROM payments ORDER BY day, id") suspend fun allPayments(): List<Payment>
    @Upsert suspend fun saveEmployer(value: Employer): Long
    @Upsert suspend fun saveShift(value: Shift): Long
    @Upsert suspend fun savePayment(value: Payment): Long
    @Upsert suspend fun saveTemplate(value: ReportTemplate)
    @Delete suspend fun deleteShift(value: Shift)
    @Delete suspend fun deletePayment(value: Payment)
    @Query("DELETE FROM employers WHERE id=:id AND NOT EXISTS (SELECT 1 FROM shifts WHERE employerId=:id) AND NOT EXISTS (SELECT 1 FROM payments WHERE employerId=:id) AND NOT EXISTS (SELECT 1 FROM schedule_rules WHERE employerId=:id) AND NOT EXISTS (SELECT 1 FROM schedule_days WHERE employerId=:id) AND NOT EXISTS (SELECT 1 FROM import_records WHERE employerId=:id)") suspend fun deleteEmptyEmployer(id: Long): Int
    @Query("SELECT * FROM employers WHERE id=:id") suspend fun employer(id: Long): Employer?
    @Query("SELECT * FROM schedule_rules ORDER BY effectiveFrom, id") fun rules(): Flow<List<ScheduleRule>>
    @Query("SELECT * FROM schedule_days") fun scheduleDays(): Flow<List<ScheduleDay>>
    @Query("SELECT * FROM import_records") fun imports(): Flow<List<ImportRecord>>
    @Query("SELECT * FROM report_templates") suspend fun allTemplates(): List<ReportTemplate>
    @Query("SELECT * FROM schedule_rules") suspend fun allRules(): List<ScheduleRule>
    @Query("SELECT * FROM schedule_days") suspend fun allScheduleDays(): List<ScheduleDay>
    @Query("SELECT * FROM import_records") suspend fun allImports(): List<ImportRecord>
    @Upsert suspend fun saveRule(value: ScheduleRule): Long
    @Upsert suspend fun saveScheduleDay(value: ScheduleDay)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveImport(value: ImportRecord): Long
}

val MIGRATION_1_2 = object : Migration(1,2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE shifts ADD COLUMN paidSecondsOverride INTEGER")
        db.execSQL("UPDATE employers SET color = CASE color WHEN 0xFF55765B THEN 0xFF00855A WHEN 0xFF5C6FA8 THEN 0xFF1464F4 WHEN 0xFFB16B49 THEN 0xFFD44A15 WHEN 0xFF8A5C95 THEN 0xFF7C3AED ELSE color END")
        db.execSQL("CREATE TABLE IF NOT EXISTS schedule_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, employerId INTEGER NOT NULL, effectiveFrom INTEGER NOT NULL, anchor INTEGER NOT NULL, pattern TEXT NOT NULL, startMinutes INTEGER NOT NULL, endMinutes INTEGER NOT NULL, breakMinutes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_rules_employerId ON schedule_rules (employerId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS schedule_days (employerId INTEGER NOT NULL, day INTEGER NOT NULL, working INTEGER NOT NULL, startMinutes INTEGER NOT NULL, endMinutes INTEGER NOT NULL, breakMinutes INTEGER NOT NULL, PRIMARY KEY(employerId, day))")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_records (`key` TEXT NOT NULL PRIMARY KEY, employerId INTEGER NOT NULL, day INTEGER NOT NULL, note TEXT NOT NULL, raw TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_import_records_employerId ON import_records (employerId)")
    }
}
@Database(entities = [Employer::class, Shift::class, Payment::class, ReportTemplate::class, ScheduleRule::class, ScheduleDay::class, ImportRecord::class], version = 2, exportSchema = true)
abstract class TabelDatabase : RoomDatabase() {
    abstract fun dao(): TabelDao
    companion object {
        @Volatile private var instance: TabelDatabase? = null
        fun get(context: Context): TabelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TabelDatabase::class.java, "my-tabel.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
