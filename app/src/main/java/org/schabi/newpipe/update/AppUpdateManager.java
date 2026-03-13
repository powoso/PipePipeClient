package org.schabi.newpipe.update;

import static android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.external_communication.ShareUtils;

import java.io.File;
import java.util.Locale;

public final class AppUpdateManager {
    public static final String ACTION_START_UPDATE_DOWNLOAD
            = "org.schabi.newpipe.action.START_UPDATE_DOWNLOAD";
    public static final String ACTION_INSTALL_DOWNLOADED_UPDATE
            = "org.schabi.newpipe.action.INSTALL_DOWNLOADED_UPDATE";

    private static final String EXTRA_VERSION_NAME = "version_name";
    private static final String EXTRA_BUILD_ID = "build_id";
    private static final String EXTRA_APK_URL = "apk_url";

    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final int UPDATE_NOTIFICATION_ID = 2000;

    private AppUpdateManager() {
    }

    public enum InstallActionMode {
        UNAVAILABLE,
        DOWNLOAD,
        OPEN_DOWNLOADS,
        INSTALL
    }

    public static void storeLatestRelease(@NonNull final Context context,
                                          @NonNull final String versionName,
                                          @Nullable final String buildId,
                                          @Nullable final String apkUrl) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        preferences.edit()
                .putString(context.getString(R.string.latest_update_version_key), versionName)
                .putString(context.getString(R.string.latest_update_build_id_key), buildId)
                .putString(context.getString(R.string.latest_update_apk_url_key), apkUrl)
                .apply();
    }

    public static void clearLatestRelease(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        preferences.edit()
                .remove(context.getString(R.string.latest_update_version_key))
                .remove(context.getString(R.string.latest_update_build_id_key))
                .remove(context.getString(R.string.latest_update_apk_url_key))
                .apply();
        clearDownloadedUpdateIfInstalled(context);
    }

    @NonNull
    public static PendingIntent createStartUpdateDownloadPendingIntent(
            @NonNull final Context context,
            @NonNull final String versionName,
            @Nullable final String buildId,
            @Nullable final String apkUrl,
            final int requestCode
    ) {
        final Intent intent = new Intent(context, AppUpdateReceiver.class)
                .setAction(ACTION_START_UPDATE_DOWNLOAD)
                .putExtra(EXTRA_VERSION_NAME, versionName)
                .putExtra(EXTRA_BUILD_ID, buildId)
                .putExtra(EXTRA_APK_URL, apkUrl);

        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                pendingIntentFlags()
        );
    }

    @NonNull
    public static PendingIntent createInstallDownloadedUpdatePendingIntent(
            @NonNull final Context context,
            final int requestCode
    ) {
        final Intent intent = new Intent(context, AppUpdateReceiver.class)
                .setAction(ACTION_INSTALL_DOWNLOADED_UPDATE);

        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                pendingIntentFlags()
        );
    }

    @NonNull
    public static PendingIntent createOpenUpdateChannelPendingIntent(
            @NonNull final Context context,
            final int requestCode
    ) {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.UPDATE_RELEASES_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                pendingIntentFlags()
        );
    }

    public static void handleBroadcast(@NonNull final Context context,
                                       @NonNull final Intent intent) {
        final String action = intent.getAction();
        if (TextUtils.isEmpty(action)) {
            return;
        }

        if (ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            handleDownloadComplete(context,
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L));
            return;
        }

        if (ACTION_START_UPDATE_DOWNLOAD.equals(action)) {
            startDownloadOrInstall(
                    context,
                    intent.getStringExtra(EXTRA_VERSION_NAME),
                    intent.getStringExtra(EXTRA_BUILD_ID),
                    intent.getStringExtra(EXTRA_APK_URL));
            return;
        }

        if (ACTION_INSTALL_DOWNLOADED_UPDATE.equals(action)) {
            installDownloadedUpdate(context);
        }
    }

    public static boolean handleLatestUpdateAction(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        return startDownloadOrInstall(
                context,
                preferences.getString(context.getString(R.string.latest_update_version_key), null),
                preferences.getString(context.getString(R.string.latest_update_build_id_key), null),
                preferences.getString(context.getString(R.string.latest_update_apk_url_key), null)
        );
    }

    public static boolean openUpdateDownload(@NonNull final Context context) {
        try {
            context.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (final Exception ignored) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }
    }

    public static boolean installDownloadedUpdate(@NonNull final Context context) {
        clearDownloadedUpdateIfInstalled(context);

        final File apkFile = getStoredDownloadedApkFile(context);
        if (apkFile == null) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            final Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settingsIntent);
            Toast.makeText(context,
                    context.getString(R.string.app_update_install_permission_toast),
                    Toast.LENGTH_LONG).show();
            return true;
        }

        final Uri apkUri = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                apkFile
        );

        final Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(installIntent);
            return true;
        } catch (final Exception ignored) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }
    }

    @NonNull
    public static InstallActionMode getInstallActionMode(@NonNull final Context context) {
        clearDownloadedUpdateIfInstalled(context);

        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final String latestVersionName = preferences.getString(
                context.getString(R.string.latest_update_version_key), null);
        final boolean storedMatchesLatest = isStoredDownloadForLatestRelease(context, preferences);

        if (storedMatchesLatest && hasDownloadedUpdateReady(context)) {
            return InstallActionMode.INSTALL;
        }
        if (storedMatchesLatest && isStoredUpdateDownloadActive(context)) {
            return InstallActionMode.OPEN_DOWNLOADS;
        }
        if (!TextUtils.isEmpty(latestVersionName)) {
            return InstallActionMode.DOWNLOAD;
        }
        if (hasDownloadedUpdateReady(context)) {
            return InstallActionMode.INSTALL;
        }
        if (isStoredUpdateDownloadActive(context)) {
            return InstallActionMode.OPEN_DOWNLOADS;
        }
        return InstallActionMode.UNAVAILABLE;
    }

    @NonNull
    public static String getActionReleaseDisplayName(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final String storedVersionName = preferences.getString(
                context.getString(R.string.update_download_version_key), null);
        final String storedBuildId = preferences.getString(
                context.getString(R.string.update_download_build_id_key), null);
        if (!TextUtils.isEmpty(storedVersionName)) {
            return formatReleaseDisplayName(context, storedVersionName, storedBuildId);
        }

        final String latestVersionName = preferences.getString(
                context.getString(R.string.latest_update_version_key), null);
        final String latestBuildId = preferences.getString(
                context.getString(R.string.latest_update_build_id_key), null);
        return formatReleaseDisplayName(context, latestVersionName, latestBuildId);
    }

    public static void handleDownloadComplete(@NonNull final Context context,
                                              final long completedDownloadId) {
        if (completedDownloadId <= 0) {
            return;
        }

        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final long trackedDownloadId = preferences.getLong(
                context.getString(R.string.update_download_id_key), -1L);
        if (trackedDownloadId != completedDownloadId) {
            return;
        }

        final DownloadManager downloadManager = getSystemDownloadManager(context);
        if (downloadManager == null) {
            clearStoredDownload(context, true);
            return;
        }

        final DownloadManager.Query query = new DownloadManager.Query().setFilterById(completedDownloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                clearStoredDownload(context, true);
                return;
            }

            final int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                clearStoredDownload(context, true);
                return;
            }
        }

        final File apkFile = getStoredDownloadedApkFile(context);
        if (apkFile == null) {
            clearStoredDownload(context, true);
            return;
        }

        showInstallReadyNotification(context);
    }

    private static boolean startDownloadOrInstall(@NonNull final Context context,
                                                  @Nullable final String versionName,
                                                  @Nullable final String buildId,
                                                  @Nullable final String apkUrl) {
        final InstallActionMode actionMode = getInstallActionMode(context);
        switch (actionMode) {
            case INSTALL:
                return installDownloadedUpdate(context);
            case OPEN_DOWNLOADS:
                return openUpdateDownload(context);
            case DOWNLOAD:
                return enqueueDownload(context, versionName, buildId, apkUrl);
            default:
                return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }
    }

    private static boolean enqueueDownload(@NonNull final Context context,
                                           @Nullable final String versionName,
                                           @Nullable final String buildId,
                                           @Nullable final String apkUrl) {
        if (TextUtils.isEmpty(apkUrl)) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }

        final DownloadManager downloadManager = getSystemDownloadManager(context);
        if (downloadManager == null) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }

        final File downloadRoot = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadRoot == null) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }

        final File updatesDir = new File(downloadRoot, "updates");
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            return ShareUtils.openUrlInBrowser(context, BuildConfig.UPDATE_RELEASES_URL, false);
        }

        final File targetFile = new File(updatesDir,
                buildApkFileName(versionName, buildId));
        clearStoredDownload(context, false);

        final DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle(context.getString(R.string.app_update_download_started_title,
                        formatReleaseDisplayName(context, versionName, buildId)))
                .setDescription(context.getString(R.string.app_update_download_started_text,
                        BuildConfig.UPDATE_CHANNEL_NAME))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType(APK_MIME_TYPE)
                .setDestinationUri(Uri.fromFile(targetFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        final long downloadId = downloadManager.enqueue(request);
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(context.getString(R.string.update_download_id_key), downloadId)
                .putString(context.getString(R.string.update_download_version_key), versionName)
                .putString(context.getString(R.string.update_download_build_id_key), buildId)
                .putString(context.getString(R.string.update_download_path_key),
                        targetFile.getAbsolutePath())
                .apply();

        NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID);
        Toast.makeText(context,
                context.getString(R.string.app_update_download_started_toast,
                        formatReleaseDisplayName(context, versionName, buildId)),
                Toast.LENGTH_SHORT).show();
        return true;
    }

    private static boolean hasDownloadedUpdateReady(@NonNull final Context context) {
        final File apkFile = getStoredDownloadedApkFile(context);
        if (apkFile == null) {
            return false;
        }

        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final String storedVersionName = preferences.getString(
                context.getString(R.string.update_download_version_key), null);
        return !TextUtils.isEmpty(storedVersionName);
    }

    private static boolean isStoredDownloadForLatestRelease(
            @NonNull final Context context,
            @NonNull final SharedPreferences preferences
    ) {
        final String storedVersionName = preferences.getString(
                context.getString(R.string.update_download_version_key), null);
        final String storedBuildId = preferences.getString(
                context.getString(R.string.update_download_build_id_key), null);
        final String latestVersionName = preferences.getString(
                context.getString(R.string.latest_update_version_key), null);
        final String latestBuildId = preferences.getString(
                context.getString(R.string.latest_update_build_id_key), null);

        if (TextUtils.isEmpty(storedVersionName)) {
            return false;
        }
        if (TextUtils.isEmpty(latestVersionName)) {
            return true;
        }

        return TextUtils.equals(storedVersionName, latestVersionName)
                && TextUtils.equals(emptyToNull(storedBuildId), emptyToNull(latestBuildId));
    }

    private static boolean isStoredUpdateDownloadActive(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final long downloadId = preferences.getLong(
                context.getString(R.string.update_download_id_key), -1L);
        if (downloadId <= 0) {
            return false;
        }

        final DownloadManager downloadManager = getSystemDownloadManager(context);
        if (downloadManager == null) {
            return false;
        }

        final DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }

            final int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }
    }

    @Nullable
    private static File getStoredDownloadedApkFile(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final String path = preferences.getString(
                context.getString(R.string.update_download_path_key), null);
        if (TextUtils.isEmpty(path)) {
            return null;
        }

        final File apkFile = new File(path);
        return apkFile.isFile() ? apkFile : null;
    }

    private static void showInstallReadyNotification(@NonNull final Context context) {
        final String releaseDisplayName = getActionReleaseDisplayName(context);
        final NotificationCompat.Builder notificationBuilder = new NotificationCompat
                .Builder(context, context.getString(R.string.app_update_notification_channel_id))
                .setSmallIcon(R.drawable.ic_newpipe_update)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(createInstallDownloadedUpdatePendingIntent(context, 2100))
                .setContentTitle(context.getString(
                        R.string.app_update_download_ready_title, releaseDisplayName))
                .setContentText(context.getString(R.string.app_update_download_ready_text))
                .addAction(
                        R.drawable.ic_newpipe_update,
                        context.getString(R.string.app_update_download_action_install),
                        createInstallDownloadedUpdatePendingIntent(context, 2101))
                .addAction(
                        R.drawable.ic_newpipe_update,
                        context.getString(R.string.app_update_notification_action_view_channel),
                        createOpenUpdateChannelPendingIntent(context, 2102)
                );

        NotificationManagerCompat.from(context)
                .notify(UPDATE_NOTIFICATION_ID, notificationBuilder.build());
    }

    private static void clearStoredDownload(@NonNull final Context context,
                                            final boolean cancelSystemDownload) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final long downloadId = preferences.getLong(
                context.getString(R.string.update_download_id_key), -1L);
        final String path = preferences.getString(
                context.getString(R.string.update_download_path_key), null);

        if (cancelSystemDownload && downloadId > 0) {
            final DownloadManager downloadManager = getSystemDownloadManager(context);
            if (downloadManager != null) {
                try {
                    downloadManager.remove(downloadId);
                } catch (final Exception ignored) {
                    // Ignore; the download may already be gone.
                }
            }
        }

        if (!TextUtils.isEmpty(path)) {
            final File apkFile = new File(path);
            if (apkFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
            }
        }

        preferences.edit()
                .remove(context.getString(R.string.update_download_id_key))
                .remove(context.getString(R.string.update_download_version_key))
                .remove(context.getString(R.string.update_download_build_id_key))
                .remove(context.getString(R.string.update_download_path_key))
                .apply();
    }

    private static void clearDownloadedUpdateIfInstalled(@NonNull final Context context) {
        final SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        final String storedVersionName = preferences.getString(
                context.getString(R.string.update_download_version_key), null);
        final String storedBuildId = preferences.getString(
                context.getString(R.string.update_download_build_id_key), null);

        if (TextUtils.isEmpty(storedVersionName)) {
            return;
        }

        final boolean sameVersion = TextUtils.equals(storedVersionName, BuildConfig.VERSION_NAME);
        final boolean sameBuild = TextUtils.isEmpty(storedBuildId)
                || TextUtils.equals(storedBuildId, BuildConfig.UPDATE_BUILD_ID);

        if (sameVersion && sameBuild) {
            clearStoredDownload(context, false);
        }
    }

    @Nullable
    private static DownloadManager getSystemDownloadManager(@NonNull final Context context) {
        return (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @NonNull
    private static String formatReleaseDisplayName(@NonNull final Context context,
                                                   @Nullable final String versionName,
                                                   @Nullable final String buildId) {
        if (TextUtils.isEmpty(versionName)) {
            return context.getString(R.string.unknown_content);
        }
        if (!TextUtils.isEmpty(buildId)) {
            return context.getString(R.string.update_release_display_with_build,
                    versionName, buildId);
        }
        return versionName;
    }

    @NonNull
    private static String buildApkFileName(@Nullable final String versionName,
                                           @Nullable final String buildId) {
        final String versionPart = sanitizeFileNamePart(
                TextUtils.isEmpty(versionName) ? BuildConfig.VERSION_NAME : versionName);
        final String buildPart = sanitizeFileNamePart(
                TextUtils.isEmpty(buildId) ? BuildConfig.UPDATE_BUILD_ID : buildId);

        if (TextUtils.isEmpty(buildPart)) {
            return "pipepipe-update-" + versionPart + ".apk";
        }
        return "pipepipe-update-" + versionPart + "-" + buildPart + ".apk";
    }

    @NonNull
    private static String sanitizeFileNamePart(@Nullable final String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }

        return value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
    }

    @Nullable
    private static String emptyToNull(@Nullable final String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
