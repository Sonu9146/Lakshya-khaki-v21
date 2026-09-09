package com.lakshyakhaki.app.data

import androidx.room.Query
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "quiz_results")
data class QuizResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val score: Int,
    val total: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "ground_results")
data class GroundResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface QuizDao {
    @Insert suspend fun insert(result: QuizResult)
    @Query("SELECT * FROM quiz_results ORDER BY timestamp DESC") fun all(): Flow<List<QuizResult>>
}

@Dao
interface GroundDao {
    @Insert suspend fun insert(result: GroundResult)
    @Query("SELECT * FROM ground_results ORDER BY timestamp DESC") fun all(): Flow<List<GroundResult>>
}

@Database(entities = [QuizResult::class, GroundResult::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quizDao(): QuizDao
    abstract fun groundDao(): GroundDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "lakshya_khaki.db"
                ).build().also { INSTANCE = it }
            }
    }
}
