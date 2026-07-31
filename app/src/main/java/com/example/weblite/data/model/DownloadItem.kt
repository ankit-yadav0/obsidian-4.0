package com.example.weblite.data.model

import android.app.DownloadManager
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val downloadManagerId: Long,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val filePath: String?,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val status: Int = DownloadManager.STATUS_PENDING,
    val timestamp: Long = System.currentTimeMillis()
)
