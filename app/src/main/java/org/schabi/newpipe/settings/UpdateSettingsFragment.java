package org.schabi.newpipe.settings;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.NewVersionWorker;
import org.schabi.newpipe.R;
import org.schabi.newpipe.util.external_communication.ShareUtils;

public class UpdateSettingsFragment extends BasePreferenceFragment {
    private final Preference.OnPreferenceChangeListener updatePreferenceChange
            = (preference, checkForUpdates) -> {
        defaultPreferences.edit()
                .putBoolean(getString(R.string.update_app_key), (boolean) checkForUpdates).apply();

        if ((boolean) checkForUpdates) {
            NewVersionWorker.enqueueNewVersionCheckingWork(requireContext(), true);
        }
        return true;
    };

    private final Preference.OnPreferenceClickListener manualUpdateClick
            = preference -> {
        Toast.makeText(getContext(),
                getString(R.string.checking_updates_toast_channel, BuildConfig.UPDATE_CHANNEL_NAME),
                Toast.LENGTH_SHORT).show();
        NewVersionWorker.enqueueNewVersionCheckingWork(requireContext(), true);
        return true;
    };

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final Preference updatePreference = findPreference(getString(R.string.update_app_key));
        updatePreference.setSummary(
                getString(R.string.updates_setting_description_channel,
                        BuildConfig.UPDATE_CHANNEL_NAME));
        updatePreference.setOnPreferenceChangeListener(updatePreferenceChange);

        final Preference channelInfoPreference =
                findPreference(getString(R.string.update_channel_info_key));
        channelInfoPreference.setSummary(getString(R.string.update_channel_summary,
                BuildConfig.UPDATE_CHANNEL_NAME, BuildConfig.UPDATE_SOURCE_LABEL));
        channelInfoPreference.setSelectable(false);

        final Preference openUpdateChannelPreference =
                findPreference(getString(R.string.open_update_channel_key));
        openUpdateChannelPreference.setSummary(getString(R.string.open_update_channel_summary,
                BuildConfig.UPDATE_SOURCE_LABEL));
        openUpdateChannelPreference.setOnPreferenceClickListener(preference -> {
            ShareUtils.openUrlInBrowser(requireContext(), BuildConfig.UPDATE_RELEASES_URL, false);
            return true;
        });

        final Preference showPreReleasePreference =
                findPreference(getString(R.string.show_prerelease_key));
        showPreReleasePreference.setVisible(!BuildConfig.UPDATE_ROLLING_RELEASE);

        final Preference manualUpdatePreference =
                findPreference(getString(R.string.manual_update_key));
        manualUpdatePreference.setSummary(getString(R.string.manual_update_description_channel,
                BuildConfig.UPDATE_CHANNEL_NAME));
        manualUpdatePreference.setOnPreferenceClickListener(manualUpdateClick);
    }
}
