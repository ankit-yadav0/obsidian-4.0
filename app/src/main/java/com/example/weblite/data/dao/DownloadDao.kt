package com.example.weblite.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.weblite.data.model.DownloadItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(download: DownloadItem): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    // COALESCE keeps the previously known path when DownloadManager has no local URI yet
    // (queued / failed downloads) instead of overwriting it with NULL.
    @Query("UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, filePath = COALESCE(:filePath, filePath) WHERE downloadManagerId = :managerId")
    suspend fun updateProgress(managerId: Long, status: Int, downloadedBytes: Long, totalBytes: Long, filePath: String?)
}
