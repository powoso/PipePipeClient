package org.schabi.newpipe.util

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream

private const val ARCHIVE_TYPE_APP_DATA = "app_data"
private const val ARCHIVE_TYPE_SETTINGS_ONLY = "settings_only"
private const val ARCHIVE_TYPE_DEBUG_BUNDLE = "debug_bundle"

object DebugBundleHelper {
    const val ZIP_MIME_TYPE = "application/zip"

    private const val ARCHIVE_MANIFEST_ENTRY = "pipepipe-archive-manifest.json"
    private const val DEBUG_INFO_JSON_ENTRY = "pipepipe-debug-info.json"
    private const val DEBUG_INFO_TEXT_ENTRY = "pipepipe-debug-info.txt"
    private const val ERROR_REPORT_MARKDOWN_ENTRY = "pipepipe-error-report.md"

    private val archiveNameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    @JvmStatic
    fun createExportFilename(prefix: String): String {
        return prefix + archiveNameDateFormat.format(Date()) + ".zip"
    }

    @JvmStatic
    @Throws(Exception::class)
    fun addAppDataArchiveEntries(
        context: Context,
        outZip: ZipOutputStream
    ) {
        addArchiveEntries(
            context = context,
            outZip = outZip,
            archiveType = ARCHIVE_TYPE_APP_DATA,
            includesDatabase = true,
            includesSettings = true,
            errorInfo = null,
            userComment = null
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun addSettingsArchiveEntries(
        context: Context,
        outZip: ZipOutputStream
    ) {
        addArchiveEntries(
            context = context,
            outZip = outZip,
            archiveType = ARCHIVE_TYPE_SETTINGS_ONLY,
            includesDatabase = false,
            includesSettings = true,
            errorInfo = null,
            userComment = null
        )
    }

    @JvmStatic
    @Throws(Exception::class)
    fun exportDebugBundle(
        context: Context,
        file: StoredFileHelper,
        errorInfo: ErrorInfo?,
        userComment: String?
    ) {
        file.create()
        ZipOutputStream(BufferedOutputStream(SharpOutputStream(file.stream))).use { outZip ->
            addArchiveEntries(
                context = context,
                outZip = outZip,
                archiveType = ARCHIVE_TYPE_DEBUG_BUNDLE,
                includesDatabase = false,
                includesSettings = false,
                errorInfo = errorInfo,
                userComment = userComment
            )
        }
    }

    @JvmStatic
    fun readArchiveSummary(
        context: Context,
        file: StoredFileHelper
    ): String? {
        val manifestText = try {
            ZipHelper.readTextFileFromZip(file, ARCHIVE_MANIFEST_ENTRY)
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            buildArchiveSummary(context, JSONObject(manifestText))
        } catch (_: Exception) {
            null
        }
    }

    private fun addArchiveEntries(
        context: Context,
        outZip: ZipOutputStream,
        archiveType: String,
        includesDatabase: Boolean,
        includesSettings: Boolean,
        errorInfo: ErrorInfo?,
        userComment: String?
    ) {
        val exportedAt = Date()
        ZipHelper.addStringToZip(
            outZip,
            ARCHIVE_MANIFEST_ENTRY,
            buildArchiveManifest(
                context,
                archiveType,
                exportedAt,
                includesDatabase,
                includesSettings
            ).toString(2)
        )
        ZipHelper.addStringToZip(
            outZip,
            DEBUG_INFO_JSON_ENTRY,
            buildDebugInfoJson(
                context,
                archiveType,
                exportedAt,
                includesDatabase,
                includesSettings,
                errorInfo,
                userComment
            ).toString(2)
        )
        ZipHelper.addStringToZip(
            outZip,
            DEBUG_INFO_TEXT_ENTRY,
            buildDebugInfoText(
                context,
                archiveType,
                exportedAt,
                includesDatabase,
                includesSettings,
                errorInfo,
                userComment
            )
        )
        if (errorInfo != null) {
            ZipHelper.addStringToZip(
                outZip,
                ERROR_REPORT_MARKDOWN_ENTRY,
                buildErrorMarkdown(context, errorInfo, userComment)
            )
        }
    }

    private fun buildArchiveManifest(
        context: Context,
        archiveType: String,
        exportedAt: Date,
        includesDatabase: Boolean,
        includesSettings: Boolean
    ): JSONObject {
        return JSONObject()
            .put("schema_version", 1)
            .put("archive_type", archiveType)
            .put("app_name", context.getString(R.string.app_name))
            .put("package_name", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("build_channel", BuildConfig.BUILD_CHANNEL)
            .put("build_variant", BuildConfig.BUILD_VARIANT_LABEL)
            .put("created_at_display", displayDateFormat.format(exportedAt))
            .put("contains_database", includesDatabase)
            .put("contains_settings", includesSettings)
            .put("contains_debug_info", true)
    }

    private fun buildDebugInfoJson(
        context: Context,
        archiveType: String,
        exportedAt: Date,
        includesDatabase: Boolean,
        includesSettings: Boolean,
        errorInfo: ErrorInfo?,
        userComment: String?
    ): JSONObject {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        return JSONObject()
            .put("exported_at_display", displayDateFormat.format(exportedAt))
            .put("archive_type", archiveType)
            .put("app", buildAppJson(context))
            .put("update_channel", buildUpdateJson(context, preferences))
            .put("device", buildDeviceJson(context))
            .put("archive_contents", JSONObject()
                .put("database", includesDatabase)
                .put("settings", includesSettings)
                .put("debug_info", true))
            .put("error", buildErrorJson(context, errorInfo, userComment))
    }

    private fun buildAppJson(context: Context): JSONObject {
        return JSONObject()
            .put("name", context.getString(R.string.app_name))
            .put("package_name", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("build_channel", BuildConfig.BUILD_CHANNEL)
            .put("build_variant", BuildConfig.BUILD_VARIANT_LABEL)
            .put("app_commit", BuildConfig.APP_GIT_COMMIT)
            .put("extractor_commit", BuildConfig.EXTRACTOR_GIT_COMMIT)
            .put("current_build_id", BuildConfig.UPDATE_BUILD_ID)
    }

    private fun buildUpdateJson(context: Context, preferences: android.content.SharedPreferences): JSONObject {
        val latestVersion = preferences.getString(
            context.getString(R.string.latest_update_version_key), ""
        ).orEmpty()
        val latestBuildId = preferences.getString(
            context.getString(R.string.latest_update_build_id_key), ""
        ).orEmpty()
        val latestApkUrl = preferences.getString(
            context.getString(R.string.latest_update_apk_url_key), ""
        ).orEmpty()
        val downloadedVersion = preferences.getString(
            context.getString(R.string.update_download_version_key), ""
        ).orEmpty()
        val downloadedBuildId = preferences.getString(
            context.getString(R.string.update_download_build_id_key), ""
        ).orEmpty()

        return JSONObject()
            .put("name", BuildConfig.UPDATE_CHANNEL_NAME)
            .put("source_label", BuildConfig.UPDATE_SOURCE_LABEL)
            .put("releases_url", BuildConfig.UPDATE_RELEASES_URL)
            .put("api_url", BuildConfig.UPDATE_API_URL)
            .put("rolling_release", BuildConfig.UPDATE_ROLLING_RELEASE)
            .put("latest_known_release", formatReleaseDisplayName(latestVersion, latestBuildId))
            .put("latest_known_apk_url", latestApkUrl)
            .put(
                "downloaded_update",
                formatReleaseDisplayName(downloadedVersion, downloadedBuildId)
            )
    }

    private fun buildDeviceJson(context: Context): JSONObject {
        val supportedAbis = JSONArray()
        Build.SUPPORTED_ABIS.forEach { abi -> supportedAbis.put(abi) }

        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("android_release", Build.VERSION.RELEASE)
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("supported_abis", supportedAbis)
            .put("app_language", Localization.getAppLocale(context).toString())
            .put(
                "content_language",
                Localization.getPreferredLocalization(context).localizationCode
            )
            .put(
                "content_country",
                Localization.getPreferredContentCountry(context).countryCode
            )
    }

    private fun buildErrorJson(
        context: Context,
        errorInfo: ErrorInfo?,
        userComment: String?
    ): JSONObject {
        val payload = JSONObject()
            .put("has_error", errorInfo != null)
            .put("user_comment", userComment.orEmpty())

        if (errorInfo == null) {
            return payload
        }

        val stackTraces = JSONArray()
        errorInfo.stackTraces.forEach { stackTrace -> stackTraces.put(stackTrace) }

        return payload
            .put("message", context.getString(errorInfo.messageStringId))
            .put("user_action", errorInfo.userAction.message)
            .put("request", errorInfo.request)
            .put("service", errorInfo.serviceName)
            .put("stack_traces", stackTraces)
    }

    private fun buildDebugInfoText(
        context: Context,
        archiveType: String,
        exportedAt: Date,
        includesDatabase: Boolean,
        includesSettings: Boolean,
        errorInfo: ErrorInfo?,
        userComment: String?
    ): String {
        val contents = mutableListOf<String>()
        if (includesDatabase) {
            contents += context.getString(R.string.backup_summary_contains_database)
        }
        if (includesSettings) {
            contents += context.getString(R.string.backup_summary_contains_settings)
        }
        if (contents.isEmpty()) {
            contents += context.getString(R.string.backup_summary_contains_debug)
        }

        val lines = mutableListOf(
            "${context.getString(R.string.backup_summary_created_label)}: " +
                displayDateFormat.format(exportedAt),
            "${context.getString(R.string.debug_bundle_archive_type_label)}: " +
                archiveTypeToLabel(context, archiveType),
            "${context.getString(R.string.debug_bundle_app_label)}: " +
                "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "${context.getString(R.string.debug_bundle_build_label)}: " +
                "${BuildConfig.BUILD_CHANNEL} / ${BuildConfig.BUILD_VARIANT_LABEL}",
            "${context.getString(R.string.debug_bundle_package_label)}: " +
                BuildConfig.APPLICATION_ID,
            "${context.getString(R.string.debug_bundle_update_channel_label)}: " +
                "${BuildConfig.UPDATE_CHANNEL_NAME} via ${BuildConfig.UPDATE_SOURCE_LABEL}",
            "${context.getString(R.string.debug_bundle_build_id_label)}: " +
                BuildConfig.UPDATE_BUILD_ID,
            "${context.getString(R.string.debug_bundle_commits_label)}: " +
                "app ${BuildConfig.APP_GIT_COMMIT}, extractor ${BuildConfig.EXTRACTOR_GIT_COMMIT}",
            "${context.getString(R.string.backup_summary_contents_label)}: " +
                contents.joinToString(", "),
            "${context.getString(R.string.debug_bundle_device_label)}: " +
                "${Build.MANUFACTURER} ${Build.MODEL} (${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT})",
            "${context.getString(R.string.debug_bundle_abis_label)}: " +
                Build.SUPPORTED_ABIS.joinToString(", "),
            "${context.getString(R.string.debug_bundle_languages_label)}: " +
                "app ${Localization.getAppLocale(context)}, content " +
                "${Localization.getPreferredLocalization(context).localizationCode}, " +
                "country ${Localization.getPreferredContentCountry(context).countryCode}"
        )

        if (errorInfo != null) {
            lines += ""
            lines += "${context.getString(R.string.debug_bundle_error_heading)}:"
            lines += "${context.getString(R.string.debug_bundle_error_message_label)}: " +
                context.getString(errorInfo.messageStringId)
            lines += "${context.getString(R.string.debug_bundle_error_action_label)}: " +
                errorInfo.userAction.message
            lines += "${context.getString(R.string.debug_bundle_error_request_label)}: " +
                errorInfo.request
            lines += "${context.getString(R.string.debug_bundle_error_service_label)}: " +
                errorInfo.serviceName
            if (!userComment.isNullOrBlank()) {
                lines += "${context.getString(R.string.debug_bundle_error_comment_label)}: " +
                    userComment
            }
            errorInfo.stackTraces.forEachIndexed { index, stackTrace ->
                lines += ""
                lines += "${context.getString(R.string.debug_bundle_error_stack_label)} ${index + 1}:"
                lines += stackTrace
            }
        }

        return lines.joinToString(separator = "\n")
    }

    private fun buildErrorMarkdown(
        context: Context,
        errorInfo: ErrorInfo,
        userComment: String?
    ): String {
        val report = StringBuilder()
        if (!userComment.isNullOrBlank()) {
            report.append(userComment).append("\n")
        }
        report.append("## Exception")
            .append("\n* __Message:__ ").append(context.getString(errorInfo.messageStringId))
            .append("\n* __User Action:__ ").append(errorInfo.userAction.message)
            .append("\n* __Request:__ ").append(errorInfo.request)
            .append("\n* __Service:__ ").append(errorInfo.serviceName)
            .append("\n* __Version:__ ").append(BuildConfig.VERSION_NAME)
            .append("\n* __Build:__ ").append(BuildConfig.BUILD_CHANNEL)
            .append(" / ").append(BuildConfig.BUILD_VARIANT_LABEL)
            .append("\n* __Package:__ ").append(BuildConfig.APPLICATION_ID)
            .append("\n* __Android:__ ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        errorInfo.stackTraces.forEachIndexed { index, stackTrace ->
            report.append("\n<details><summary><b>Crash log ")
                .append(index + 1)
                .append("</b></summary><p>\n\n```\n")
                .append(stackTrace)
                .append("\n```\n</p></details>\n")
        }
        report.append("<hr>\n")
        return report.toString()
    }

    private fun buildArchiveSummary(context: Context, manifest: JSONObject): String {
        val createdAt = manifest.optString("created_at_display")
        val versionName = manifest.optString("version_name")
        val buildChannel = manifest.optString("build_channel")
        val buildVariant = manifest.optString("build_variant")
        val appName = manifest.optString("app_name", context.getString(R.string.app_name))
        val contents = mutableListOf<String>()
        if (manifest.optBoolean("contains_database")) {
            contents += context.getString(R.string.backup_summary_contains_database)
        }
        if (manifest.optBoolean("contains_settings")) {
            contents += context.getString(R.string.backup_summary_contains_settings)
        }
        if (contents.isEmpty()) {
            contents += context.getString(R.string.backup_summary_contains_debug)
        }

        val source = buildString {
            append(appName)
            if (versionName.isNotBlank()) {
                append(" ").append(versionName)
            }
            val buildDescriptor = listOf(buildChannel, buildVariant)
                .filter { it.isNotBlank() }
                .joinToString(separator = "/")
            if (buildDescriptor.isNotBlank()) {
                append(" (").append(buildDescriptor).append(")")
            }
        }

        return listOf(
            context.getString(R.string.backup_summary_created, createdAt),
            context.getString(R.string.backup_summary_source, source),
            context.getString(
                R.string.backup_summary_contents,
                contents.joinToString(separator = ", ")
            )
        ).joinToString(separator = "\n")
    }

    private fun formatReleaseDisplayName(versionName: String, buildId: String): String {
        if (versionName.isBlank()) {
            return ""
        }
        return if (buildId.isBlank()) {
            versionName
        } else {
            "$versionName ($buildId)"
        }
    }

    private fun archiveTypeToLabel(context: Context, archiveType: String): String {
        return when (archiveType) {
            ARCHIVE_TYPE_APP_DATA -> context.getString(R.string.backup_archive_type_app_data)
            ARCHIVE_TYPE_SETTINGS_ONLY ->
                context.getString(R.string.backup_archive_type_settings_only)
            ARCHIVE_TYPE_DEBUG_BUNDLE ->
                context.getString(R.string.backup_archive_type_debug_bundle)
            else -> archiveType
        }
    }
}
