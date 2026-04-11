package com.prism.launcher

import android.content.Context
import java.util.Calendar

object HotseatPredictor {

    suspend fun getPredictions(context: Context, limit: Int = 4): List<String> {
        val db = AppDatabase.get(context)
        val dao = db.appLaunchStatDao()
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 1. Get predictions for this specific hour
        val predictions = dao.getTopForHour(currentHour, limit).toMutableList()

        // 2. If short, fallback to overall most used apps
        if (predictions.size < limit) {
            val overall = dao.getTopOverall(limit)
            for (cn in overall) {
                if (predictions.size >= limit) break
                if (!predictions.contains(cn)) {
                    predictions.add(cn)
                }
            }
        }

        // 3. Still short? Fallback to whatever is installed (randomly sorted)
        if (predictions.size < limit) {
            val installed = db.installedAppDao().getAll()
                .map { "${it.packageName}/${it.activityClass}" }
                .shuffled() // Random selection for new users
            
            for (cn in installed) {
                if (predictions.size >= limit) break
                if (!predictions.contains(cn)) {
                    predictions.add(cn)
                }
            }
        }

        return predictions
    }
}
