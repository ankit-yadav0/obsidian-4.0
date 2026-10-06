package com.example.weblite.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.weblite.data.dao.BookmarkDao
import com.example.weblite.data.dao.DownloadDao
import com.example.weblite.data.model.Bookmark
import com.example.weblite.data.model.DownloadItem

@Database(entities = [DownloadItem::class, Bookmark::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            return synchronized(this) {
                // Re-check inside the lock so two racing callers can't each build a database.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "weblite_database"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
