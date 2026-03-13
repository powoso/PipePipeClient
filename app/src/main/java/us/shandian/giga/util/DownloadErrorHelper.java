package us.shandian.giga.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.schabi.newpipe.R;

import java.util.Locale;

import us.shandian.giga.get.DownloadMission;

import static us.shandian.giga.get.DownloadMission.ERROR_CONNECT_HOST;
import static us.shandian.giga.get.DownloadMission.ERROR_FILE_CREATION;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_NO_CONTENT;
import static us.shandian.giga.get.DownloadMission.ERROR_INSUFFICIENT_STORAGE;
import static us.shandian.giga.get.DownloadMission.ERROR_NOTHING;
import static us.shandian.giga.get.DownloadMission.ERROR_PATH_CREATION;
import static us.shandian.giga.get.DownloadMission.ERROR_PERMISSION_DENIED;
import static us.shandian.giga.get.DownloadMission.ERROR_POSTPROCESSING;
import static us.shandian.giga.get.DownloadMission.ERROR_POSTPROCESSING_HOLD;
import static us.shandian.giga.get.DownloadMission.ERROR_POSTPROCESSING_STOPPED;
import static us.shandian.giga.get.DownloadMission.ERROR_PROGRESS_LOST;
import static us.shandian.giga.get.DownloadMission.ERROR_RESOURCE_GONE;
import static us.shandian.giga.get.DownloadMission.ERROR_SSL_EXCEPTION;
import static us.shandian.giga.get.DownloadMission.ERROR_TIMEOUT;
import static us.shandian.giga.get.DownloadMission.ERROR_UNKNOWN_EXCEPTION;
import static us.shandian.giga.get.DownloadMission.ERROR_UNKNOWN_HOST;

public final class DownloadErrorHelper {
    private DownloadErrorHelper() {
    }

    @StringRes
    public static int getReasonStringRes(@NonNull final DownloadMission mission) {
        switch (mission.errCode) {
            case 416:
                return R.string.error_http_unsupported_range;
            case 401:
                return R.string.error_http_auth_download;
            case 403:
                return R.string.error_http_forbidden_download;
            case 404:
                return R.string.error_http_not_found;
            case ERROR_NOTHING:
                return 0;
            case ERROR_FILE_CREATION:
                return R.string.error_file_creation;
            case ERROR_HTTP_NO_CONTENT:
                return R.string.error_http_no_content;
            case ERROR_PATH_CREATION:
                return R.string.error_path_creation;
            case ERROR_PERMISSION_DENIED:
                return R.string.permission_denied;
            case ERROR_SSL_EXCEPTION:
                return R.string.error_ssl_exception;
            case ERROR_UNKNOWN_HOST:
                return R.string.error_unknown_host;
            case ERROR_CONNECT_HOST:
                return R.string.error_connect_host;
            case ERROR_POSTPROCESSING_STOPPED:
                return R.string.error_postprocessing_stopped_new;
            case ERROR_POSTPROCESSING:
            case ERROR_POSTPROCESSING_HOLD:
                return R.string.error_postprocessing_failed;
            case ERROR_INSUFFICIENT_STORAGE:
                return R.string.error_insufficient_storage;
            case ERROR_UNKNOWN_EXCEPTION:
                return mission.errObject == null ? R.string.msg_error : R.string.general_error;
            case ERROR_PROGRESS_LOST:
                return R.string.error_progress_lost;
            case ERROR_TIMEOUT:
                return R.string.error_timeout;
            case ERROR_RESOURCE_GONE:
                return R.string.error_download_resource_gone;
            default:
                if (mission.errCode >= 100 && mission.errCode < 600) {
                    return 0;
                }
                return mission.errObject == null ? R.string.msg_error : R.string.general_error;
        }
    }

    @NonNull
    public static String getReasonText(@NonNull final Context context,
                                       @NonNull final DownloadMission mission) {
        final int reasonRes = getReasonStringRes(mission);
        if (reasonRes != 0) {
            return context.getString(reasonRes);
        }

        if (mission.errCode >= 100 && mission.errCode < 600) {
            return String.format(Locale.US, "HTTP %d", mission.errCode);
        }

        return context.getString(R.string.general_error);
    }

    @NonNull
    public static String getActionText(@NonNull final Context context,
                                       @NonNull final DownloadMission mission) {
        switch (mission.errCode) {
            case 416:
                return context.getString(R.string.download_error_hint_single_thread);
            case 401:
            case 403:
            case ERROR_RESOURCE_GONE:
                return context.getString(R.string.download_error_hint_refresh_from_video);
            case ERROR_TIMEOUT:
            case ERROR_UNKNOWN_HOST:
            case ERROR_CONNECT_HOST:
            case ERROR_SSL_EXCEPTION:
                return mission.threadCount > 1
                        ? context.getString(R.string.download_error_hint_retry_or_lower_threads)
                        : context.getString(R.string.download_error_hint_retry_connection);
            case ERROR_INSUFFICIENT_STORAGE:
                return context.getString(R.string.download_error_hint_storage);
            case ERROR_PERMISSION_DENIED:
            case ERROR_FILE_CREATION:
            case ERROR_PATH_CREATION:
            case ERROR_PROGRESS_LOST:
                return context.getString(R.string.download_error_hint_pick_folder);
            case ERROR_HTTP_NO_CONTENT:
            case 404:
                return context.getString(R.string.download_error_hint_retry_later);
            default:
                if (mission.errCode >= 500 && mission.errCode < 600) {
                    return context.getString(R.string.download_error_hint_retry_later);
                }
                return "";
        }
    }

    @NonNull
    public static String getFullMessage(@NonNull final Context context,
                                        @NonNull final DownloadMission mission) {
        final String reason = getReasonText(context, mission);
        final String action = getActionText(context, mission);
        if (action.isEmpty()) {
            return reason;
        }

        return reason + "\n\n" + action;
    }
}
