package com.prestondihle.healthtracker.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prestondihle.healthtracker.R
import com.prestondihle.healthtracker.TrackerApp
import com.prestondihle.healthtracker.domain.Caffeine
import com.prestondihle.healthtracker.domain.CaffeineDose
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * The hour the projection is read against.
 *
 * The same 9 PM the dashboard's "At 9 PM" figure uses. Two different bedtimes
 * would have the card and the notification disagree about the same evening.
 */
private val BEDTIME: LocalTime = LocalTime.of(21, 0)

/** An ordinary cup, which is what "one more" has to mean to be worth saying. */
private const val NEXT_DOSE_MG = 70

const val CAFFEINE_CHANNEL_ID = "caffeine_last_call"
private const val CAFFEINE_NOTIFICATION_ID = 4201
private const val WORK_NAME = "caffeine-last-call"

/**
 * Checks, on the hour, whether the next coffee is the one that breaks bedtime.
 *
 * Periodic rather than fired when a dose is logged, because the interesting
 * moment usually arrives without anything being logged at all: caffeine already
 * drunk keeps decaying, and the afternoon crosses the threshold on its own. A
 * check that only ran on a log would stay silent exactly through the stretch it
 * exists to cover.
 *
 * Fifteen minutes is WorkManager's floor for periodic work and an hour is finer
 * than this moves, so the interval is an hour. The work is idempotent -- it
 * re-reads the doses each time and re-decides -- so a run WorkManager defers or
 * coalesces costs nothing.
 */
class CaffeineLastCallWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TrackerApp).container
        val repository = container.trackerRepository
        val zoneId = ZoneId.systemDefault()

        val limit = repository.getUserGoals().first()?.caffeineBedtimeLimitMg ?: return Result.success()

        val now = Instant.now()
        val bedtime = bedtimeAfter(now, zoneId)
        // The same history the curve is drawn from: a dose older than this is
        // under a thousandth of what was drunk and cannot move the answer.
        val since = now.minus(Duration.ofHours(Caffeine.RELEVANT_HISTORY_HOURS))
        val doses =
            repository.getCaffeineSince(since).first().map { CaffeineDose(it.timestamp, it.milligrams) }

        if (
            Caffeine.lastCallReached(
                doses = doses,
                now = now,
                bedtime = bedtime,
                limitMg = limit,
                nextDoseMg = NEXT_DOSE_MG,
            )
        ) {
            notify(applicationContext, limit)
        }
        return Result.success()
    }

    /**
     * Posts the warning, or does nothing if the permission was never granted.
     *
     * Checked rather than assumed: POST_NOTIFICATIONS is a runtime permission on
     * Android 13 and up, the app asks for it once, and "no" is an answer that has
     * to keep working rather than throwing here every hour.
     */
    private fun notify(context: Context, limitMg: Int) {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val notification =
            NotificationCompat.Builder(context, CAFFEINE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_caffeine)
                .setContentTitle("Last call for caffeine")
                .setContentText(
                    "Another cup now would leave you over $limitMg mg at 9 PM."
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

        // One fixed id, so an hour that re-reaches the same conclusion replaces
        // the standing notification rather than stacking a second one on it.
        NotificationManagerCompat.from(context).notify(CAFFEINE_NOTIFICATION_ID, notification)
    }

    companion object {

        /** Tonight's bedtime, or tomorrow's once tonight's has gone by. */
        internal fun bedtimeAfter(now: Instant, zoneId: ZoneId): Instant {
            // Not LocalDate.ofInstant, which is API 34 against a minSdk of 26 --
            // a crash on anything below Android 14, and invisible on the author's
            // own phone. The two say the same thing; only this one is available.
            val today = now.atZone(zoneId).toLocalDate()
            val tonight = today.atTime(BEDTIME).atZone(zoneId).toInstant()
            return if (tonight.isAfter(now)) tonight
            else today.plusDays(1).atTime(BEDTIME).atZone(zoneId).toInstant()
        }

        /**
         * Registers the hourly check, replacing any already scheduled.
         *
         * `UPDATE` rather than `KEEP`: the interval is part of the app, not of
         * the user's data, so a build that changes it should take effect rather
         * than waiting for an uninstall.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<CaffeineLastCallWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * Runs the check once, now.
         *
         * Periodic work will not run early -- WorkManager answers a forced run
         * with "executed before schedule" and reschedules -- so the hourly job
         * cannot cover the moment the limit is first set. That moment is exactly
         * when the answer is wanted: the limit gets switched on in the afternoon,
         * which is the only part of the day the warning is about, and waiting up
         * to an hour to say anything makes the setting look broken.
         */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<CaffeineLastCallWorker>().build())
        }

        /**
         * The channel the warning is posted on.
         *
         * Created at startup rather than at first post, because a channel has to
         * exist before anything can be delivered to it, and the first delivery is
         * exactly the one nobody is watching for.
         */
        fun createChannel(context: Context) {
            val channel =
                NotificationChannel(
                        CAFFEINE_CHANNEL_ID,
                        "Caffeine last call",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply {
                        description =
                            "Warns when one more coffee would leave too much caffeine at bedtime."
                    }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
