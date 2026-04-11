package com.prism.launcher

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.prism.launcher.messaging.*

@Database(entities = [InstalledAppEntity::class, AppLaunchStatEntity::class, AiMessageEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun installedAppDao(): InstalledAppDao
    abstract fun appLaunchStatDao(): AppLaunchStatDao
    abstract fun aiMessageDao(): AiMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "prism_apps.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
