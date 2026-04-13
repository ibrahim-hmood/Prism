package com.prism.launcher

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.prism.launcher.messaging.*

@Database(entities = [
    InstalledAppEntity::class, 
    AppLaunchStatEntity::class, 
    AiMessageEntity::class,
    com.prism.launcher.social.SocialPostEntity::class,
    com.prism.launcher.social.SocialBotEntity::class,
    com.prism.launcher.social.SocialMessageEntity::class,
    com.prism.launcher.social.SocialCommentEntity::class,
    com.prism.launcher.social.SocialFollowEntity::class,
    com.prism.launcher.social.SocialInteractionEntity::class
], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun installedAppDao(): InstalledAppDao
    abstract fun appLaunchStatDao(): AppLaunchStatDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun socialDao(): com.prism.launcher.social.SocialDao

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
