package org.schabi.newpipe

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.update.AppUpdateManager
import org.schabi.newpipe.util.ReleaseVersionUtil.coerceUpdateCheckExpiry
import org.schabi.newpipe.util.ReleaseVersionUtil.isLastUpdateCheckExpired
import java.io.IOException

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    private data class ReleaseUpdate(
        val versionName: String,
        val buildId: String?,
        val apkUrl: String?
    )

    /**
     * Compare the current build with the latest available build and show an update notification
     * when an update is available.
     */
    private fun compareAppVersionAndShowNotification(releaseUpdate: ReleaseUpdate, isManual: Boolean) {
        val currentVersion = parseVersion(BuildConfig.VERSION_NAME)
        val newVersion = parseVersion(releaseUpdate.versionName)
        val versionCompare = compareVersions(currentVersion, newVersion)
        val hasNewRollingBuild = versionCompare == 0
            && BuildConfig.UPDATE_ROLLING_RELEASE
            && isNewerBuildId(BuildConfig.UPDATE_BUILD_ID, releaseUpdate.buildId)

        if (versionCompare >= 0 && !hasNewRollingBuild) {
            AppUpdateManager.clearLatestRelease(applicationContext)
            if (isManual) {
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(
                            R.string.app_update_unavailable_toast_channel,
                            BuildConfig.UPDATE_CHANNEL_NAME
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }

        AppUpdateManager.storeLatestRelease(
            applicationContext,
            releaseUpdate.versionName,
            releaseUpdate.buildId,
            releaseUpdate.apkUrl
        )

        val downloadPendingIntent = AppUpdateManager.createStartUpdateDownloadPendingIntent(
            applicationContext,
            releaseUpdate.versionName,
            releaseUpdate.buildId,
            releaseUpdate.apkUrl,
            2000
        )
        val channelPendingIntent =
            AppUpdateManager.createOpenUpdateChannelPendingIntent(applicationContext, 2001)
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_newpipe_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(downloadPendingIntent)
            .setAutoCancel(true)
            .setContentTitle(applicationContext.getString(
                R.string.app_update_notification_content_title_channel,
                BuildConfig.UPDATE_CHANNEL_NAME
            ))
            .setContentText(
                applicationContext.getString(
                    R.string.app_update_notification_content_text_release,
                    formatReleaseDisplayName(releaseUpdate)
                )
            )
            .addAction(
                R.drawable.ic_newpipe_update,
                applicationContext.getString(R.string.app_update_notification_action_download),
                downloadPendingIntent
            )
            .addAction(
                R.drawable.ic_newpipe_update,
                applicationContext.getString(R.string.app_update_notification_action_view_channel),
                channelPendingIntent
            )
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        notificationManager.notify(2000, notificationBuilder.build())
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        // Check if the current apk is a github one or not.
//        if (!isReleaseApk()) {
//            return
//        }

        if (!inputData.getBoolean(IS_MANUAL, false)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Check if the last request has happened a certain time ago
            // to reduce the number of API requests.
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0)
            if (!isLastUpdateCheckExpired(expiry)) {
                return
            }
        }

        val response = DownloaderImpl.getInstance().get(BuildConfig.UPDATE_API_URL)
        handleResponse(response)
    }

    private fun handleResponse(response: Response) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            // Store a timestamp which needs to be exceeded,
            // before a new request to the API is made.
            val newExpiry = coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit {
                putLong(applicationContext.getString(R.string.update_expiry_key), newExpiry)
            }
        } catch (e: Exception) {
            if (DEBUG) {
                Log.w(TAG, "Could not extract and save new expiry date", e)
            }
        }

        try {
            val includePreRelease = prefs.getBoolean(applicationContext.getString(R.string.show_prerelease_key), false)
            val selectedRelease = selectRelease(parseReleaseObjects(response.responseBody()),
                includePreRelease)

            if (selectedRelease == null) {
                AppUpdateManager.clearLatestRelease(applicationContext)
            }

            selectedRelease?.let { release ->
                compareAppVersionAndShowNotification(
                    ReleaseUpdate(
                        versionName = extractReleaseVersionName(release),
                        buildId = extractReleaseBuildId(release),
                        apkUrl = findCompatibleApkUrl(release, Build.SUPPORTED_ABIS)
                    ),
                    inputData.getBoolean(IS_MANUAL, false)
                )
            }
        } catch (e: JsonParserException) {
            if (DEBUG) Log.w(TAG, "Could not parse update response", e)
        }
    }

    private fun selectRelease(releases: List<JsonObject>, includePreRelease: Boolean): JsonObject? {
        if (releases.isEmpty()) {
            return null
        }
        if (BuildConfig.UPDATE_ROLLING_RELEASE) {
            return releases.first()
        }

        var selectedRelease: JsonObject? = null
        for (release in releases) {
            if (!includePreRelease && release.getBoolean("prerelease")) {
                continue
            }
            if (selectedRelease == null || isNewerRelease(release, selectedRelease)) {
                selectedRelease = release
            }
        }
        return selectedRelease
    }

    private fun parseReleaseObjects(responseBody: String): List<JsonObject> {
        val normalizedBody = responseBody.trim()
        if (normalizedBody.startsWith("[")) {
            val githubReleases = JsonParser.`array`().from(normalizedBody)
            return List(githubReleases.size) { index -> githubReleases.getObject(index) }
        }
        return listOf(JsonParser.`object`().from(normalizedBody))
    }

    private fun extractReleaseVersionName(release: JsonObject): String {
        val releaseBody = release.getString("body")
        return readReleaseMetadataValue(releaseBody, "Version-Name")
            ?: extractVersionString(release.getString("name"))
            ?: extractVersionString(release.getString("tag_name"))
            ?: BuildConfig.VERSION_NAME
    }

    private fun extractReleaseBuildId(release: JsonObject): String? {
        return readReleaseMetadataValue(release.getString("body"), "Build-ID")
    }

    private fun readReleaseMetadataValue(body: String?, key: String): String? {
        if (body.isNullOrBlank()) {
            return null
        }

        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isNewerRelease(newRelease: JsonObject, currentRelease: JsonObject): Boolean {
        val newVersion = parseVersion(extractReleaseVersionName(newRelease))
        val currentVersion = parseVersion(extractReleaseVersionName(currentRelease))
        return compareVersions(newVersion, currentVersion) > 0
    }

    private fun formatReleaseDisplayName(releaseUpdate: ReleaseUpdate): String {
        if (!releaseUpdate.buildId.isNullOrBlank()) {
            return applicationContext.getString(
                R.string.update_release_display_with_build,
                releaseUpdate.versionName,
                releaseUpdate.buildId
            )
        }
        return releaseUpdate.versionName
    }

    private fun isNewerBuildId(currentBuildId: String?, candidateBuildId: String?): Boolean {
        if (candidateBuildId.isNullOrBlank()) {
            return false
        }
        if (currentBuildId.isNullOrBlank()) {
            return true
        }

        val currentTimestamp = currentBuildId.substringBefore('-')
        val candidateTimestamp = candidateBuildId.substringBefore('-')
        if (currentTimestamp.length == 14
            && candidateTimestamp.length == 14
            && currentTimestamp.all(Char::isDigit)
            && candidateTimestamp.all(Char::isDigit)
        ) {
            return candidateTimestamp > currentTimestamp
        }

        return candidateBuildId != currentBuildId
    }

    private fun findCompatibleApkUrl(release: JsonObject, abis: Array<String>): String? {
        val assets = release.getArray("assets")
        var universalUrl: String? = null
        for (i in 0 until assets.size) {
            val asset = assets.getObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                when {
                    name.contains("universal") -> universalUrl = asset.getString("browser_download_url")
                    abis.any { name.contains(it) } -> return asset.getString("browser_download_url")
                }
            }
        }
        return universalUrl
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch update metadata: probably a network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val IS_MANUAL = "isManual"
        /**
         * Start a new worker which checks if all conditions for performing a version check are met,
         * fetches the configured update endpoint containing info about the latest available
         * version and displays a notification about an available update if one is available.
         * <br></br>
         * Following conditions need to be met, before data is requested from the server:
         *
         *  *  The app is signed with the correct signing key (by TeamNewPipe / schabi).
         * If the signing key differs from the one used upstream, the update cannot be installed.
         *  * The user enabled searching for and notifying about updates in the settings.
         *  * The app did not recently check for updates.
         * We do not want to make unnecessary connections and DOS our servers.
         */
        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}

data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val betaVersion: Int?
)

private fun parseVersion(versionStr: String): Version {
    val normalized = (extractVersionString(versionStr) ?: versionStr).removePrefix("v")
    val parts = normalized.split("-beta", limit = 2)
    val mainPart = parts[0]

    val mainParts = mainPart.split(".").map {
        it.toIntOrNull() ?: throw IllegalArgumentException("Invalid version part: $it")
    }

    val (major, minor, patch) = mainParts

    val betaVersion = when {
        parts.size == 1 -> null
        parts[1].isEmpty() -> 0
        else -> parts[1].toIntOrNull()
    }

    return Version(major, minor, patch, betaVersion)
}

private fun extractVersionString(text: String?): String? {
    if (text.isNullOrBlank()) {
        return null
    }

    return Regex("""\d+\.\d+\.\d+(?:-beta\d*)?""").find(text)?.value
}

private fun compareVersions(v1: Version, v2: Version): Int {
    val mainCompare = when {
        v1.major != v2.major -> v1.major.compareTo(v2.major)
        v1.minor != v2.minor -> v1.minor.compareTo(v2.minor)
        v1.patch != v2.patch -> v1.patch.compareTo(v2.patch)
        else -> 0
    }

    if (mainCompare != 0) return mainCompare

    return when {
        v1.betaVersion == null && v2.betaVersion == null -> 0
        v1.betaVersion == null -> 1
        v2.betaVersion == null -> -1
        else -> v1.betaVersion.compareTo(v2.betaVersion)
    }
}
