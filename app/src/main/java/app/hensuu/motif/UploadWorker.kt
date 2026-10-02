package app.hensuu.motif

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * One job per recording, scheduled when the recording stops. It waits for a network (the system
 * batches this; no polling, no sync service), uploads once, and is gone.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val c = applicationContext
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val file = Recordings.file(c, name)
        if (!file.exists()) return Result.success()

        Prefs.setStatus(c, name, Prefs.ST_UPLOADING)
        return try {
            Drive.upload(c, file)
            Prefs.setStatus(c, name, Prefs.ST_DONE)
            Result.success()
        } catch (e: Drive.AuthRequired) {
            needsReconnect(c, name)
        } catch (e: ExecutionException) {
            // AuthorizationClient failed outright (e.g. OAuth client not set up for this build).
            needsReconnect(c, name)
        } catch (e: Drive.Rejected) {
            Prefs.setStatus(c, name, Prefs.ST_FAILED)
            Result.failure()
        } catch (e: Exception) {
            // IOException, TimeoutException, JSON hiccups: back off and try again.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Prefs.setStatus(c, name, Prefs.ST_QUEUED)
                Result.retry()
            } else {
                Prefs.setStatus(c, name, Prefs.ST_FAILED)
                Result.failure()
            }
        }
    }

    private fun needsReconnect(c: Context, name: String): Result {
        Prefs.setDriveConnected(c, false)
        Prefs.setStatus(c, name, Prefs.ST_AUTH)
        return Result.failure()
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val MAX_ATTEMPTS = 8

        private fun workName(name: String) = "upload:$name"

        fun enqueue(c: Context, name: String) {
            Prefs.setStatus(c, name, Prefs.ST_QUEUED)
            if (!Prefs.driveConnected(c)) {
                Prefs.setStatus(c, name, Prefs.ST_AUTH)
                return
            }
            val network = if (Prefs.mobileData(c)) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequest.Builder(UploadWorker::class.java)
                .setInputData(Data.Builder().putString(KEY_NAME, name).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(c)
                .enqueueUniqueWork(workName(name), ExistingWorkPolicy.REPLACE, request)
        }

        /** Re-queue everything not yet on Drive (after reconnecting, or a network-setting change). */
        fun enqueuePending(c: Context) {
            for (f in Recordings.list(c)) {
                val st = Prefs.status(c, f.name)
                if (st != Prefs.ST_DONE && st != Prefs.ST_UPLOADING) enqueue(c, f.name)
            }
        }

        fun cancel(c: Context, name: String) {
            WorkManager.getInstance(c).cancelUniqueWork(workName(name))
        }
    }
}
