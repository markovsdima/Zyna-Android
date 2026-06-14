package com.zyna.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedRoomDao {
    @Query("SELECT * FROM rooms WHERE userId = :userId")
    fun observeRooms(userId: String): Flow<List<CachedRoomEntity>>

    @Upsert
    suspend fun upsertRooms(rooms: List<CachedRoomEntity>)

    @Query("DELETE FROM rooms WHERE userId = :userId")
    suspend fun clearRooms(userId: String)

    @Query("DELETE FROM rooms")
    suspend fun clearAllRooms()
}
