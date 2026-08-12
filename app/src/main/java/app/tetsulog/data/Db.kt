package app.tetsulog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sets")
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,        // "2026-08-12"
    val exercise: String,
    val weightKg: Float,
    val reps: Int,
    val at: Long             // epoch millis
)

@Dao
interface SetDao {
    @Insert
    suspend fun insert(set: WorkoutSet)

    @Delete
    suspend fun delete(set: WorkoutSet)

    @Query("SELECT * FROM sets WHERE date = :date ORDER BY id ASC")
    fun setsOn(date: String): Flow<List<WorkoutSet>>

    @Query("SELECT * FROM sets ORDER BY date DESC, id ASC")
    fun allSets(): Flow<List<WorkoutSet>>

    @Query("SELECT * FROM sets WHERE at >= :since ORDER BY at ASC")
    suspend fun setsSince(since: Long): List<WorkoutSet>

    @Query("SELECT * FROM sets ORDER BY date ASC, id ASC")
    suspend fun snapshot(): List<WorkoutSet>
}

@Database(entities = [WorkoutSet::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun setDao(): SetDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDb::class.java, "tetsulog.db"
            ).build().also { instance = it }
        }
    }
}
