package com.example.weblite.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.weblite.data.dao.DownloadDao
import com.example.weblite.data.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao
) {

    private val downloadManager =
        context.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val downloadsFlow: Flow<List<DownloadItem>> = downloadDao.getAllDownloads()

    suspend fun enqueueDownload(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?
    ): Long = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri).apply {
            if (mimeType.isNotEmpty()) {
                setMimeType(mimeType)
            }
            if (!userAgent.isNullOrEmpty()) {
                addRequestHeader("User-Agent", userAgent)
            }
            if (!cookie.isNullOrEmpty()) {
                addRequestHeader("Cookie", cookie)
            }
            setDescription("Obsidian offline media download")
            setTitle(fileName)
            allowScanningByMediaScanner()
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadId = downloadManager.enqueue(request)

        val downloadItem = DownloadItem(
            downloadManagerId = downloadId,
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            filePath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            ).absolutePath,
            status = DownloadManager.STATUS_RUNNING
        )

        downloadDao.insertOrUpdate(downloadItem)
        downloadId
    }

    suspend fun syncActiveDownloads() = withContext(Dispatchers.IO) {
        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query) ?: return@withContext

        cursor.use { c ->
            val idIndex = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            while (c.moveToNext()) {
                if (idIndex != -1) {
                    val managerId = c.getLong(idIndex)
                    val status = if (statusIndex != -1) c.getInt(statusIndex) else DownloadManager.STATUS_RUNNING
                    val downloadedBytes = if (downloadedIndex != -1) c.getLong(downloadedIndex) else 0L
                    val totalBytes = if (totalIndex != -1) c.getLong(totalIndex) else 0L
                    val localUriStr = if (uriIndex != -1) c.getString(uriIndex) else null

                    var filePath: String? = null
                    if (localUriStr != null) {
                        try {
                            filePath = Uri.parse(localUriStr).path
                        } catch (_: Exception) {}
                    }

                    downloadDao.updateProgress(managerId, status, downloadedBytes, totalBytes, filePath)
                }
            }
        }
    }

    suspend fun retryDownload(downloadItem: DownloadItem): Long {
        deleteDownload(downloadItem)
        return enqueueDownload(
            url = downloadItem.url,
            fileName = downloadItem.fileName,
            mimeType = downloadItem.mimeType,
            userAgent = null,
            cookie = null
        )
    }

    suspend fun deleteDownload(downloadItem: DownloadItem) = withContext(Dispatchers.IO) {
        try {
            downloadManager.remove(downloadItem.downloadManagerId)
        } catch (_: Exception) {}

        downloadItem.filePath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {}
        }

        downloadDao.deleteById(downloadItem.id)
    }
}
