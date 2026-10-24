package ru.mytabel.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    @Query("DELETE FROM employers WHERE id=:id AND NOT EXISTS (SELECT 1 FROM shifts WHERE employerId=:id) AND NOT EXISTS (SELECT 1 FROM payments WHERE employerId=:id)") suspend fun deleteEmptyEmployer(id: Long): Int
    @Query("SELECT * FROM employers WHERE id=:id") suspend fun employer(id: Long): Employer?
}

@Database(entities = [Employer::class, Shift::class, Payment::class, ReportTemplate::class], version = 1, exportSchema = true)
abstract class TabelDatabase : RoomDatabase() {
    abstract fun dao(): TabelDao
    companion object {
        @Volatile private var instance: TabelDatabase? = null
        fun get(context: Context): TabelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TabelDatabase::class.java, "my-tabel.db").build().also { instance = it }
        }
    }
}
