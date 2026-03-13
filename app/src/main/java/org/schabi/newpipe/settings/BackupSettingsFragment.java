package org.schabi.newpipe.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.DebugBundleHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ZipHelper;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.schabi.newpipe.extractor.utils.Utils.isBlank;
import static org.schabi.newpipe.util.Localization.assureCorrectAppLanguage;

public class BackupSettingsFragment extends BasePreferenceFragment {
    private static final String ZIP_MIME_TYPE = "application/zip";

    private enum BackupType {
        APP_DATA,
        SETTINGS_ONLY,
        DEBUG_BUNDLE
    }

    private ContentSettingsManager manager;
    private String importExportDataPathKey;
    private String pendingImportArchiveSummary;
    private BackupType pendingImportType = BackupType.APP_DATA;
    private BackupType pendingExportType = BackupType.APP_DATA;

    private final ActivityResultLauncher<Intent> requestImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestImportPathResult);
    private final ActivityResultLauncher<Intent> requestExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::requestExportPathResult);

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        final File homeDir = ContextCompat.getDataDir(requireContext());
        Objects.requireNonNull(homeDir);
        manager = new ContentSettingsManager(new NewPipeFileLocator(homeDir));
        manager.deleteSettingsFile();

        importExportDataPathKey = getString(R.string.import_export_data_path);

        addPreferencesFromResourceRegistry();

        final Preference importDataPreference = requirePreference(R.string.import_data);
        importDataPreference.setOnPreferenceClickListener((Preference p) -> {
            pendingImportType = BackupType.APP_DATA;
            launchImportPicker();
            return true;
        });

        final Preference exportDataPreference = requirePreference(R.string.export_data);
        exportDataPreference.setOnPreferenceClickListener((Preference p) -> {
            pendingExportType = BackupType.APP_DATA;
            launchExportPicker("PipePipeData-v" + org.schabi.newpipe.BuildConfig.VERSION_NAME + "-");
            return true;
        });

        final Preference importSettingsPreference =
                requirePreference(R.string.import_settings_data);
        importSettingsPreference.setOnPreferenceClickListener((Preference p) -> {
            pendingImportType = BackupType.SETTINGS_ONLY;
            launchImportPicker();
            return true;
        });

        final Preference exportSettingsPreference =
                requirePreference(R.string.export_settings_data);
        exportSettingsPreference.setOnPreferenceClickListener((Preference p) -> {
            pendingExportType = BackupType.SETTINGS_ONLY;
            launchExportPicker("PipePipeSettings-v"
                    + org.schabi.newpipe.BuildConfig.VERSION_NAME + "-");
            return true;
        });

        final Preference exportDebugBundlePreference =
                requirePreference(R.string.export_debug_bundle);
        exportDebugBundlePreference.setOnPreferenceClickListener((Preference p) -> {
            pendingExportType = BackupType.DEBUG_BUNDLE;
            launchExportPicker("PipePipeDebug-v"
                    + org.schabi.newpipe.BuildConfig.VERSION_NAME + "-");
            return true;
        });
    }

    private void launchImportPicker() {
        NoFileManagerSafeGuard.launchSafe(
                requestImportPathLauncher,
                StoredFileHelper.getPicker(requireContext(), ZIP_MIME_TYPE,
                        getImportExportDataUri()),
                TAG,
                getContext()
        );
    }

    private void launchExportPicker(final String filenamePrefix) {
        NoFileManagerSafeGuard.launchSafe(
                requestExportPathLauncher,
                StoredFileHelper.getNewPicker(requireContext(),
                        DebugBundleHelper.createExportFilename(filenamePrefix),
                        ZIP_MIME_TYPE, getImportExportDataUri()),
                TAG,
                getContext()
        );
    }

    private void requestExportPathResult(final ActivityResult result) {
        assureCorrectAppLanguage(getContext());
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            final Uri lastExportDataUri = result.getData().getData();
            final StoredFileHelper file = new StoredFileHelper(getContext(),
                    result.getData().getData(), ZIP_MIME_TYPE);

            if (pendingExportType == BackupType.DEBUG_BUNDLE) {
                exportDebugBundle(file, lastExportDataUri);
            } else if (pendingExportType == BackupType.SETTINGS_ONLY) {
                exportSettings(file, lastExportDataUri);
            } else {
                exportDatabase(file, lastExportDataUri);
            }
        }
    }

    private void requestImportPathResult(final ActivityResult result) {
        assureCorrectAppLanguage(getContext());
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            final Uri lastImportDataUri = result.getData().getData();
            final StoredFileHelper file = new StoredFileHelper(getContext(),
                    result.getData().getData(), ZIP_MIME_TYPE);
            pendingImportArchiveSummary = manager.readArchiveSummary(requireContext(), file);

            new AlertDialog.Builder(requireActivity())
                    .setMessage(buildImportConfirmationMessage())
                    .setPositiveButton(R.string.ok, (d, id) -> {
                        if (pendingImportType == BackupType.SETTINGS_ONLY) {
                            importSettings(file, lastImportDataUri);
                        } else {
                            importDatabase(file, lastImportDataUri);
                        }
                    })
                    .setNegativeButton(R.string.cancel, (d, id) -> d.cancel())
                    .create()
                    .show();
        }
    }

    private void exportDatabase(final StoredFileHelper file, final Uri exportDataUri) {
        try {
            NewPipeDatabase.checkpoint();

            final SharedPreferences preferences = PreferenceManager
                    .getDefaultSharedPreferences(requireContext());
            manager.exportDatabase(requireContext(), preferences, file);

            saveLastImportExportDataUri(exportDataUri);
            Toast.makeText(getContext(), R.string.export_complete_toast, Toast.LENGTH_SHORT).show();
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Exporting database", e);
        } finally {
            manager.deleteSettingsFile();
        }
    }

    private void exportSettings(final StoredFileHelper file, final Uri exportDataUri) {
        try {
            final SharedPreferences preferences = PreferenceManager
                    .getDefaultSharedPreferences(requireContext());
            manager.exportSettings(requireContext(), preferences, file);

            saveLastImportExportDataUri(exportDataUri);
            Toast.makeText(getContext(), R.string.export_settings_complete_toast,
                    Toast.LENGTH_SHORT).show();
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Exporting settings", e);
        } finally {
            manager.deleteSettingsFile();
        }
    }

    private void exportDebugBundle(final StoredFileHelper file, final Uri exportDataUri) {
        try {
            DebugBundleHelper.exportDebugBundle(requireContext(), file, null, null);
            saveLastImportExportDataUri(exportDataUri);
            Toast.makeText(getContext(), R.string.export_debug_bundle_complete_toast,
                    Toast.LENGTH_SHORT).show();
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Exporting debug bundle", e);
        }
    }

    private void importDatabase(final StoredFileHelper file, final Uri importDataUri) {
        if (!ZipHelper.isValidZipFile(file)) {
            Toast.makeText(getContext(), R.string.no_valid_zip_file, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (!manager.ensureDbDirectoryExists()) {
                throw new IOException("Could not create databases dir");
            }

            if (!manager.extractDb(file)) {
                Toast.makeText(getContext(), R.string.could_not_import_all_files,
                        Toast.LENGTH_LONG).show();
            }

            if (manager.extractSettings(file)) {
                final AlertDialog.Builder alert = new AlertDialog.Builder(requireContext());
                alert.setTitle(R.string.import_settings);
                alert.setMessage(buildSettingsImportMessage());

                alert.setNegativeButton(R.string.cancel, (dialog, which) -> {
                    dialog.dismiss();
                    finishImport(importDataUri);
                });
                alert.setPositiveButton(R.string.ok, (dialog, which) -> {
                    dialog.dismiss();
                    importExtractedSettings();
                    finishImport(importDataUri);
                });
                alert.show();
            } else {
                finishImport(importDataUri);
            }
        } catch (final Exception e) {
            manager.deleteSettingsFile();
            ErrorUtil.showUiErrorSnackbar(this, "Importing database", e);
        }
    }

    private void importSettings(final StoredFileHelper file, final Uri importDataUri) {
        if (!ZipHelper.isValidZipFile(file)) {
            Toast.makeText(getContext(), R.string.no_valid_zip_file, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (!manager.extractSettings(file)) {
                Toast.makeText(getContext(), R.string.no_settings_found_in_backup,
                        Toast.LENGTH_LONG).show();
                return;
            }

            if (!importExtractedSettings()) {
                return;
            }

            finishImport(importDataUri);
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Importing settings", e);
        } finally {
            manager.deleteSettingsFile();
        }
    }

    private boolean importExtractedSettings() {
        final SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        if (!manager.loadSharedPreferences(sharedPreferences)) {
            Toast.makeText(getContext(), R.string.could_not_import_settings,
                    Toast.LENGTH_LONG).show();
            return false;
        }

        final Set<String> enabledTabs = sharedPreferences.getStringSet(
                requireContext().getString(R.string.show_channel_tabs_key), new HashSet<>());
        final Set<String> newSet = new HashSet<>(enabledTabs);
        if (newSet.contains("show_channel_tabs_livestreams")) {
            newSet.remove("show_channel_tabs_livestreams");
            newSet.add("show_channel_tabs_live");
            sharedPreferences.edit()
                    .putStringSet(requireContext().getString(R.string.show_channel_tabs_key),
                            newSet)
                    .apply();
        }

        return true;
    }

    private void finishImport(final Uri importDataUri) {
        saveLastImportExportDataUri(importDataUri);
        manager.deleteSettingsFile();
        NavigationHelper.restartApp(requireActivity());
    }

    private Uri getImportExportDataUri() {
        final String path = defaultPreferences.getString(importExportDataPathKey, null);
        return isBlank(path) ? null : Uri.parse(path);
    }

    private void saveLastImportExportDataUri(final Uri importExportDataUri) {
        defaultPreferences.edit()
                .putString(importExportDataPathKey, importExportDataUri.toString())
                .apply();
    }

    private String buildImportConfirmationMessage() {
        final String baseMessage = getString(pendingImportType == BackupType.SETTINGS_ONLY
                ? R.string.override_current_settings
                : R.string.override_current_data);
        if (TextUtils.isEmpty(pendingImportArchiveSummary)) {
            return baseMessage;
        }
        return baseMessage + "\n\n" + getString(R.string.backup_details_heading)
                + "\n" + pendingImportArchiveSummary;
    }

    private String buildSettingsImportMessage() {
        if (TextUtils.isEmpty(pendingImportArchiveSummary)) {
            return getString(R.string.import_settings);
        }
        return getString(R.string.import_settings) + "\n\n"
                + getString(R.string.backup_details_heading)
                + "\n" + pendingImportArchiveSummary;
    }
}
