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
            setDescription("Obsidian download")
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

    /**
     * Copies DownloadManager's state for this app's downloads into the database.
     * @return true while at least one download is still pending, running or paused, so the caller
     *         knows whether polling has to continue.
     */
    suspend fun syncActiveDownloads(): Boolean = withContext(Dispatchers.IO) {
        val cursor = downloadManager.query(DownloadManager.Query()) ?: return@withContext false
        var anyActive = false

        cursor.use { c ->
            val idIndex = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val downloadedIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            while (c.moveToNext()) {
                if (idIndex == -1) continue
                val managerId = c.getLong(idIndex)
                val status = if (statusIndex != -1) c.getInt(statusIndex) else DownloadManager.STATUS_RUNNING
                val downloadedBytes = if (downloadedIndex != -1) c.getLong(downloadedIndex) else 0L
                val totalBytes = if (totalIndex != -1) c.getLong(totalIndex) else 0L
                val localUriStr = if (uriIndex != -1) c.getString(uriIndex) else null

                if (status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED
                ) {
                    anyActive = true
                }

                // Only file:// URIs carry a usable path; anything else keeps the path we already have.
                var filePath: String? = null
                if (localUriStr != null) {
                    try {
                        val parsed = Uri.parse(localUriStr)
                        if (parsed.scheme == "file") filePath = parsed.path
                    } catch (_: Exception) {
                    }
                }

                downloadDao.updateProgress(managerId, status, downloadedBytes, totalBytes, filePath)
            }
        }
        anyActive
    }

    /** Re-downloads a failed item. The caller supplies fresh cookies / user agent (they are not stored). */
    suspend fun retryDownload(downloadItem: DownloadItem, userAgent: String?, cookie: String?): Long {
        deleteDownload(downloadItem, deleteFile = true)
        return enqueueDownload(
            url = downloadItem.url,
            fileName = downloadItem.fileName,
            mimeType = downloadItem.mimeType,
            userAgent = userAgent,
            cookie = cookie
        )
    }

    /**
     * Removes the entry from the list. With [deleteFile] the downloaded file is deleted from storage too;
     * without it only the history entry goes away and the file stays in the Downloads folder.
     */
    suspend fun deleteDownload(downloadItem: DownloadItem, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        val completed = downloadItem.status == DownloadManager.STATUS_SUCCESSFUL
        // A download that is still pending / running / failed must be cancelled in DownloadManager,
        // otherwise it keeps running invisibly. For a finished one remove() would delete the file,
        // so it is only called when the file is meant to go too.
        if (!completed || deleteFile) {
            try {
                downloadManager.remove(downloadItem.downloadManagerId)
            } catch (_: Exception) {
            }
        }
        if (deleteFile) {
            downloadItem.filePath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
        downloadDao.deleteById(downloadItem.id)
    }
}
