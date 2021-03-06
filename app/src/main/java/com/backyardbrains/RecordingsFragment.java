package com.backyardbrains;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import com.backyardbrains.analysis.AnalysisType;
import com.backyardbrains.audio.WavAudioFile;
import com.backyardbrains.data.persistance.AnalysisDataSource;
import com.backyardbrains.events.AnalyzeAudioFileEvent;
import com.backyardbrains.events.FindSpikesEvent;
import com.backyardbrains.events.PlayAudioFileEvent;
import com.backyardbrains.utils.ApacheCommonsLang3Utils;
import com.backyardbrains.utils.BYBUtils;
import com.backyardbrains.utils.DateUtils;
import com.backyardbrains.utils.FabricUtils;
import com.backyardbrains.utils.RecordingUtils;
import com.backyardbrains.utils.ViewUtils;
import com.backyardbrains.utils.WavUtils;
import com.backyardbrains.view.EmptyRecyclerView;
import com.backyardbrains.view.EmptyView;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import org.greenrobot.eventbus.EventBus;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

import static com.backyardbrains.utils.LogUtils.LOGD;
import static com.backyardbrains.utils.LogUtils.makeLogTag;

/**
 * @author Tihomir Leka <tihomir at backyardbrains.com>
 */
public class RecordingsFragment extends BaseFragment implements EasyPermissions.PermissionCallbacks {

    public static final String TAG = makeLogTag(MainActivity.class);

    private static final int BYB_SETTINGS_SCREEN = 126;
    private static final int BYB_READ_EXTERNAL_STORAGE_PERM = 127;

    @BindView(R.id.rv_files) EmptyRecyclerView rvFiles;
    @BindView(R.id.empty_view) EmptyView emptyView;
    @BindView(R.id.btn_privacy_policy) Button btnPrivacyPolicy;

    private Unbinder unbinder;

    private FilesAdapter adapter;

    /**
     * Factory for creating a new instance of the fragment.
     *
     * @return A new instance of fragment {@link RecordingsFragment}.
     */
    public static RecordingsFragment newInstance() {
        return new RecordingsFragment();
    }

    //==============================================
    //  LIFECYCLE IMPLEMENTATIONS
    //==============================================

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        registerReceivers();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_recordings, container, false);
        unbinder = ButterKnife.bind(this, view);

        setupUI();

        return view;
    }

    @Override public void onStart() {
        super.onStart();

        // scan files to populate recordings list
        scanFiles();
        // update empty view to show loaded files or "empty" tagline
        updateEmptyView(false);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        unbinder.unbind();
    }

    @Override public void onDestroy() {
        unregisterReceivers();
        super.onDestroy();
    }

    //////////////////////////////////////////////////////////////////////////////
    //                      Permission Request >= API 23
    //////////////////////////////////////////////////////////////////////////////

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
        @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        LOGD(TAG, "onPermissionsGranted:" + requestCode + ":" + perms.size());
    }

    @Override public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        LOGD(TAG, "onPermissionsDenied:" + requestCode + ":" + perms.size());
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            new AppSettingsDialog.Builder(this).setRationale(R.string.rationale_ask_again)
                .setTitle(R.string.title_settings_dialog)
                .setPositiveButton(R.string.action_setting)
                .setNegativeButton(R.string.action_cancel)
                .setRequestCode(BYB_SETTINGS_SCREEN)
                .build()
                .show();
        }
    }

    @AfterPermissionGranted(BYB_READ_EXTERNAL_STORAGE_PERM) private void scanFiles() {
        if (getContext() != null && EasyPermissions.hasPermissions(getContext(),
            Manifest.permission.READ_EXTERNAL_STORAGE)) {
            rescanFiles();
        } else {
            // Request one permission
            EasyPermissions.requestPermissions(this, getString(R.string.rationale_read_external_storage),
                BYB_READ_EXTERNAL_STORAGE_PERM, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    //                           List recordings
    //////////////////////////////////////////////////////////////////////////////

    // Rescans BYB directory and updates the files list.
    void rescanFiles() {
        LOGD(TAG, "RESCAN FILES!!!!!");

        final File[] files = RecordingUtils.BYB_DIRECTORY.listFiles(new FileFilter() {
            @Override public boolean accept(File file) {
                return !RecordingUtils.isEventsFile(file);
            }
        });
        if (files != null) {
            if (files.length > 0) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override public int compare(File file1, File file2) {
                        //noinspection UseCompareMethod
                        return Long.valueOf(file2.lastModified()).compareTo(file1.lastModified());
                    }
                });
            }

            adapter.setFiles(files);
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    //                         Utility methods
    //////////////////////////////////////////////////////////////////////////////

    // Specified callback is invoked after check that spike analysis for the recording at specified filePath exists or not
    private void spikesAnalysisExists(@NonNull String filePath,
        @Nullable AnalysisDataSource.SpikeAnalysisCheckCallback callback) {
        if (getAnalysisManager() != null) getAnalysisManager().spikesAnalysisExists(filePath, callback);
    }

    // Opens dialog with recording details
    void fileDetails(@NonNull File f) {
        WavAudioFile waf = null;
        try {
            waf = new WavAudioFile(f);
        } catch (IOException ignored) {
        }
        String details = "File name: " + f.getName() + "\n";
        details += "Full path: \n" + f.getAbsolutePath() + "\n";
        details += "Duration: " + (waf != null ? WavUtils.formatWavLength(f.length(), waf.sampleRate()) : "UNKNOWN");
        BYBUtils.showAlert(getActivity(), "File details", details);
    }

    // Starts playing specified audio file
    void playAudioFile(@NonNull File f) {
        EventBus.getDefault().post(new PlayAudioFileEvent(f.getAbsolutePath()));
    }

    // Start process of finding spikes for the specified audio file
    void findSpikes(@NonNull File f) {
        if (f.exists()) EventBus.getDefault().post(new FindSpikesEvent(f.getAbsolutePath()));
    }

    // Start process of autocorrelation analysis for specified audio file
    void autocorrelation(@NonNull File f) {
        startAnalysis(f, AnalysisType.AUTOCORRELATION);
    }

    // Start process of inter spike interval analysis for specified audio file
    void ISI(@NonNull File f) {
        startAnalysis(f, AnalysisType.ISI);
    }

    // Start process of cross-correlation analysis for specified audio file
    void crossCorrelation(@NonNull File f) {
        startAnalysis(f, AnalysisType.CROSS_CORRELATION);
    }

    // Start process of average spike analysis for specified audio file
    void averageSpike(@NonNull File f) {
        startAnalysis(f, AnalysisType.AVERAGE_SPIKE);
    }

    // Starts analysis process for specified type and specified audio file
    private void startAnalysis(@NonNull final File file, @AnalysisType final int type) {
        spikesAnalysisExists(file.getAbsolutePath(), new AnalysisDataSource.SpikeAnalysisCheckCallback() {
            @Override public void onSpikeAnalysisExistsResult(boolean exists, int trainCount) {
                if (exists) {
                    if (file.exists()) {
                        EventBus.getDefault().post(new AnalyzeAudioFileEvent(file.getAbsolutePath(), type));
                    }
                } else {
                    BYBUtils.showAlert(getActivity(), getString(R.string.find_spikes_not_done_title),
                        getString(R.string.find_spikes_not_done_message));
                }
            }
        });
    }

    // Initiates sending of the selected recording via email
    void emailFile(@NonNull File f) {
        // first check if accompanying events file exists because it needs to be shared as well
        final ArrayList<Uri> uris = new ArrayList<>();
        uris.add(FileProvider.getUriForFile(getContext(), BuildConfig.APPLICATION_ID + ".provider", f));
        final File eventsFile = RecordingUtils.getEventFile(f);
        if (eventsFile != null) {
            uris.add(FileProvider.getUriForFile(getContext(), BuildConfig.APPLICATION_ID + ".provider", eventsFile));
        }
        Intent sendIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "My BackyardBrains Recording");
        sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        sendIntent.setType("message/rfc822");
        startActivity(Intent.createChooser(sendIntent, "Share file"));
    }

    // Triggers renaming of the selected file
    void renameFile(@NonNull final File f) {
        final EditText e = new EditText(this.getActivity());
        e.setText(f.getName().replace(".wav", "")); // remove file extension when renaming
        e.setSelection(e.getText().length());
        new AlertDialog.Builder(this.getActivity()).setTitle("Rename File")
            .setMessage("Please enter the new name for your file.")
            .setView(e)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    if (f.exists()) {
                        final String filename = e.getText().toString().trim();
                        // validate the new file name
                        if (ApacheCommonsLang3Utils.isBlank(filename)) {
                            if (getContext() != null) {
                                ViewUtils.toast(getContext(), getString(R.string.error_message_validation_file_name));
                            }
                            return;
                        }
                        final File newFile = new File(f.getParent(), filename + ".wav");
                        // validate if file with specified name already exists
                        if (!newFile.exists()) {
                            // get events file before renaming
                            final File ef = RecordingUtils.getEventFile(f);
                            // rename the file
                            if (f.renameTo(newFile)) {
                                // we need to rename events file as well, if it exists
                                if (ef != null) {
                                    final File newEventsFile = RecordingUtils.createEventsFile(newFile);
                                    if (!newEventsFile.exists()) {
                                        // let's rename events file
                                        if (!ef.renameTo(newEventsFile)) {
                                            BYBUtils.showAlert(getActivity(), "Error",
                                                getString(R.string.error_message_files_events_rename));
                                            FabricUtils.logCustom(
                                                "Renaming events file for the given recording " + f.getPath()
                                                    + " failed", null);
                                        }
                                    }
                                }
                            } else {
                                if (getContext() != null) {
                                    ViewUtils.toast(getContext(), getString(R.string.error_message_files_rename));
                                }
                                FabricUtils.logCustom("Renaming file " + f.getPath() + " failed", null);
                            }
                        } else {
                            if (getContext() != null) {
                                ViewUtils.toast(getContext(), getString(R.string.error_message_files_exists));
                            }
                        }
                    } else {
                        if (getContext() != null) {
                            ViewUtils.toast(getContext(), getString(R.string.error_message_files_no_file));
                        }
                        FabricUtils.logCustom("File " + f.getPath() + " doesn't exist", null);
                    }
                    // rescan the files
                    rescanFiles();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show();
    }

    // Triggers deletion of the selected file
    void deleteFile(@NonNull final File f) {
        new AlertDialog.Builder(this.getActivity()).setTitle("Delete File")
            .setMessage("Are you sure you want to delete " + f.getName() + "?")
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    // validate if the specified file already exists
                    if (f.exists()) {
                        // get events file before renaming
                        final File ef = RecordingUtils.getEventFile(f);
                        // delete the file
                        if (f.delete()) {
                            // we need to delete events file as well, if it exists
                            if (ef != null && ef.exists()) {
                                // let's delete events file
                                if (!ef.delete()) {
                                    BYBUtils.showAlert(getActivity(), "Error",
                                        getString(R.string.error_message_files_events_delete));
                                    FabricUtils.logCustom(
                                        "Deleting events file for the given recording " + f.getPath() + " failed",
                                        null);
                                }
                            }
                        } else {
                            if (getContext() != null) {
                                ViewUtils.toast(getContext(), getString(R.string.error_message_files_delete));
                            }
                            FabricUtils.logCustom("Deleting file " + f.getPath() + " failed", null);
                        }
                    } else {
                        if (getContext() != null) {
                            ViewUtils.toast(getContext(), getString(R.string.error_message_files_no_file));
                        }
                        FabricUtils.logCustom("File " + f.getPath() + " doesn't exist", null);
                    }
                    rescanFiles();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show();
    }

    // Initializes user interface
    private void setupUI() {
        // update empty view to show loader
        updateEmptyView(true);

        ((MainActivity) getActivity()).showButtons(true);

        adapter = new FilesAdapter(getContext(), null, new FilesAdapter.Callback() {
            @Override public void onClick(@NonNull File file) {
                showRecordingOptions(file);
            }
        });
        rvFiles.setAdapter(adapter);
        rvFiles.setEmptyView(emptyView);
        rvFiles.setHasFixedSize(true);
        rvFiles.setLayoutManager(new LinearLayoutManager(getContext()));
        rvFiles.addItemDecoration(new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL));

        btnPrivacyPolicy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent browserIntent =
                    new Intent(Intent.ACTION_VIEW, Uri.parse("http://backyardbrains.com/about/privacy"));
                getActivity().startActivity(browserIntent);
            }
        });
    }

    // Update empty view to loading or empty state
    void updateEmptyView(boolean showLoader) {
        if (showLoader) {
            emptyView.setLoading();
        } else {
            emptyView.setTagline(getString(R.string.no_files_found));
            emptyView.setEmpty();
        }
    }

    // Opens available options for a selected recording. Options are different depending on whether spikes have already
    // been found or not.
    void showRecordingOptions(@NonNull final File file) {
        spikesAnalysisExists(file.getAbsolutePath(), new AnalysisDataSource.SpikeAnalysisCheckCallback() {
            @Override public void onSpikeAnalysisExistsResult(boolean exists, int trainCount) {
                showDialog(file, exists, trainCount > 1);
            }
        });
    }

    // Creates and opens recording options dialog
    void showDialog(@NonNull final File file, final boolean canAnalyze, final boolean showCrossCorrelation) {
        new AlertDialog.Builder(this.getActivity()).setTitle("Choose an action")
            .setCancelable(true)
            .setItems(canAnalyze ? (showCrossCorrelation ? R.array.options_recording
                    : R.array.options_recording_no_cross_correlation) : R.array.options_recording_no_spikes,
                new DialogInterface.OnClickListener() {

                    @Override public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                fileDetails(file);
                                break;
                            case 1:
                                playAudioFile(file);
                                break;
                            case 2:
                                findSpikes(file);
                                break;
                            case 3:
                                if (canAnalyze) {
                                    autocorrelation(file);
                                } else {
                                    emailFile(file);
                                }
                                break;
                            case 4:
                                if (canAnalyze) {
                                    ISI(file);
                                } else {
                                    renameFile(file);
                                }
                                break;
                            case 5:
                                if (canAnalyze) {
                                    if (showCrossCorrelation) {
                                        crossCorrelation(file);
                                    } else {
                                        averageSpike(file);
                                    }
                                } else {
                                    deleteFile(file);
                                }
                                break;
                            case 6:
                                if (showCrossCorrelation) {
                                    averageSpike(file);
                                } else {
                                    emailFile(file);
                                }
                                break;
                            case 7:
                                if (showCrossCorrelation) {
                                    emailFile(file);
                                } else {
                                    renameFile(file);
                                }
                                break;
                            case 8:
                                if (showCrossCorrelation) {
                                    renameFile(file);
                                } else {
                                    deleteFile(file);
                                }
                                break;
                            case 9:
                                deleteFile(file);
                                break;
                        }
                    }
                })
            .create()
            .show();
    }

    /**
     * Adapter for listing all the previously recorded files.
     */
    static class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.FileViewHolder> {

        private final LayoutInflater inflater;
        private final Callback callback;

        private List<File> files;

        interface Callback {
            void onClick(@NonNull File file);
        }

        FilesAdapter(@NonNull Context context, @Nullable File[] files, @Nullable Callback callback) {
            super();

            this.inflater = LayoutInflater.from(context);
            this.callback = callback;

            if (files != null) this.files = Arrays.asList(files);
        }

        void setFiles(@NonNull File[] files) {
            this.files = Arrays.asList(files);
            notifyDataSetChanged();
        }

        @NonNull @Override public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new FileViewHolder(inflater.inflate(R.layout.item_recording, parent, false), callback);
        }

        @Override public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            holder.setFile(files.get(position));
        }

        @Override public int getItemCount() {
            return files != null ? files.size() : 0;
        }

        static class FileViewHolder extends RecyclerView.ViewHolder {
            @BindView(R.id.tv_file_name) TextView tvFileName;
            @BindView(R.id.tv_file_size) TextView tvFileSize;
            @BindView(R.id.tv_file_last_modified) TextView tvFileLasModified;

            File file;
            Date date = new Date();

            FileViewHolder(View view, final Callback callback) {
                super(view);
                ButterKnife.bind(this, view);

                view.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (callback != null) callback.onClick(file);
                    }
                });
            }

            void setFile(@NonNull File file) {
                this.file = file;

                tvFileName.setText(file.getName());
                WavAudioFile waf = null;
                try {
                    waf = new WavAudioFile(file);
                } catch (IOException ignored) {
                }
                tvFileSize.setText(waf != null ? WavUtils.formatWavLength(file.length(), waf.sampleRate()) : "UNKNOWN");
                date.setTime(file.lastModified());
                tvFileLasModified.setText(DateUtils.format_MMM_d_yyyy_HH_mm_a(date));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- BROADCAST RECEIVERS INSTANCES
    // ---------------------------------------------------------------------------------------------
    private ToggleRecordingListener toggleRecorder;
    private FileReadReceiver readReceiver;
    private SuccessfulSaveListener successfulSaveListener;
    private RescanFilesListener rescanFilesListener;

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- BROADCAST RECEIVERS CLASS
    // ---------------------------------------------------------------------------------------------
    private class ToggleRecordingListener extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            rescanFiles();
        }
    }

    private class FileReadReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
        }
    }

    private class SuccessfulSaveListener extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            rescanFiles();
        }
    }

    private class RescanFilesListener extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            rescanFiles();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- BROADCAST RECEIVERS TOGGLES
    // ---------------------------------------------------------------------------------------------
    private void registerSuccessfulSaveReceiver(boolean reg) {
        if (getContext() != null) {
            if (reg) {
                IntentFilter intentFilter = new IntentFilter("BYBRecordingSaverSuccessfulSave");
                successfulSaveListener = new SuccessfulSaveListener();
                getContext().registerReceiver(successfulSaveListener, intentFilter);
            } else {
                getContext().unregisterReceiver(successfulSaveListener);
            }
        }
    }

    private void registerRecordingToggleReceiver(boolean reg) {
        if (getContext() != null) {
            if (reg) {
                IntentFilter intentFilter = new IntentFilter("BYBToggleRecording");
                toggleRecorder = new ToggleRecordingListener();
                getContext().registerReceiver(toggleRecorder, intentFilter);
            } else {
                getContext().unregisterReceiver(toggleRecorder);
            }
        }
    }

    private void registerFileReadReceiver(boolean reg) {
        if (getContext() != null) {
            if (reg) {
                IntentFilter fileReadIntent = new IntentFilter("BYBFileReadIntent");
                readReceiver = new FileReadReceiver();
                getContext().registerReceiver(readReceiver, fileReadIntent);
            } else {
                getContext().unregisterReceiver(readReceiver);
            }
        }
    }

    private void registerRescanFilesReceiver(boolean reg) {
        if (getContext() != null) {
            if (reg) {
                IntentFilter intent = new IntentFilter("BYBRescanFiles");
                rescanFilesListener = new RescanFilesListener();
                getContext().registerReceiver(rescanFilesListener, intent);
            } else {
                getContext().unregisterReceiver(rescanFilesListener);
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // ----------------------------------------- REGISTER RECEIVERS
    // ---------------------------------------------------------------------------------------------
    public void registerReceivers() {
        LOGD(TAG, "registerReceivers");

        registerRecordingToggleReceiver(true);
        registerFileReadReceiver(true);
        registerSuccessfulSaveReceiver(true);
        registerRescanFilesReceiver(true);
    }

    public void unregisterReceivers() {
        LOGD(TAG, "unregisterReceivers");

        registerRecordingToggleReceiver(false);
        registerFileReadReceiver(false);
        registerSuccessfulSaveReceiver(false);
        registerRescanFilesReceiver(false);
    }
}
