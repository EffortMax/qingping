package com.qingping.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatDao {

    @Query("SELECT * FROM stats ORDER BY (skipCount + backCount) DESC, lastTime DESC")
    fun observeAll(): Flow<List<StatEntity>>

    @Query("SELECT * FROM stats WHERE packageName = :pkg LIMIT 1")
    suspend fun getByPackage(pkg: String): StatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StatEntity)

    @Query("SELECT COALESCE(SUM(skipCount), 0) FROM stats")
    fun observeTotalSkip(): Flow<Int>

    @Query("SELECT COALESCE(SUM(backCount), 0) FROM stats")
    fun observeTotalBack(): Flow<Int>

    @Query("DELETE FROM stats")
    suspend fun clearAll()
}
