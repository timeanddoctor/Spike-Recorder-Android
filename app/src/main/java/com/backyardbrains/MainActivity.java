package com.backyardbrains;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.view.MenuItem;
import android.view.View;
import butterknife.BindView;
import butterknife.ButterKnife;
import com.backyardbrains.analysis.AnalysisManager;
import com.backyardbrains.analysis.AnalysisType;
import com.backyardbrains.audio.AudioService;
import com.backyardbrains.events.AnalyzeAudioFileEvent;
import com.backyardbrains.events.AudioServiceConnectionEvent;
import com.backyardbrains.events.FindSpikesEvent;
import com.backyardbrains.events.OpenRecordingsEvent;
import com.backyardbrains.events.PlayAudioFileEvent;
import com.backyardbrains.events.ShowToastEvent;
import com.backyardbrains.utils.PrefUtils;
import com.backyardbrains.utils.ViewUtils;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.ContentViewEvent;
import java.util.List;
import me.pqpo.librarylog4a.Log4a;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.NoSubscriberEvent;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

import static com.backyardbrains.utils.LogUtils.LOGD;
import static com.backyardbrains.utils.LogUtils.LOGI;
import static com.backyardbrains.utils.LogUtils.makeLogTag;

//import me.pqpo.librarylog4a.Log4a;

public class MainActivity extends AppCompatActivity
    implements BaseFragment.ResourceProvider, EasyPermissions.PermissionCallbacks {

    static final String TAG = makeLogTag(MainActivity.class);

    public static final int INVALID_VIEW = -1;
    public static final int OSCILLOSCOPE_VIEW = 0;
    public static final int THRESHOLD_VIEW = 1;
    public static final int RECORDINGS_VIEW = 2;
    public static final int ANALYSIS_VIEW = 3;
    public static final int FIND_SPIKES_VIEW = 4;
    public static final int PLAY_AUDIO_VIEW = 5;

    public static final String BYB_RECORDINGS_FRAGMENT = "RecordingsFragment";
    public static final String BYB_THRESHOLD_FRAGMENT = "ThresholdFragment";
    public static final String BYB_SPIKES_FRAGMENT = "FindSpikesFragment";
    public static final String BYB_ANALYSIS_FRAGMENT = "AnalysisFragment";
    public static final String BYB_OSCILLOSCOPE_FRAGMENT = "OscilloscopeFragment";
    public static final String BYB_PLAY_AUDIO_FRAGMENT = "BackyardBrainsPlayAudioFragment";

    private static final int BYB_RECORD_AUDIO_PERM = 123;
    private static final int BYB_SETTINGS_SCREEN = 125;

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @BindView(R.id.bottom_menu) BottomNavigationView bottomMenu;

    private boolean audioServiceRunning = false;
    AudioService audioService;
    private AnalysisManager analysisManager;

    //protected SlidingView sliding_drawer;
    private int currentFrag = -1;

    boolean showScalingInstructions = true;
    boolean showingScalingInstructions = false;

    private enum FragTransaction {
        ADD, REPLACE, REMOVE
    }

    // Bottom menu navigation listener
    private BottomNavigationView.OnNavigationItemSelectedListener bottomMenuListener =
        new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                loadFragment(item.getItemId());
                return true;
            }
        };

    //////////////////////////////////////////////////////////////////////////////
    //                       Lifecycle overrides
    //////////////////////////////////////////////////////////////////////////////

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LOGD(TAG, "onCreate()");

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        setupUI(savedInstanceState);
    }

    @Override protected void onStart() {
        LOGD(TAG, "onStart()");

        // start the audio service for reads mic data, recording and playing recorded files
        start();
        // load settings saved from last session
        loadSettings();
        // registers all broadcast receivers
        registerReceivers();

        super.onStart();

        // register activity with event bus
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this);
    }

    @Override protected void onStop() {
        LOGD(TAG, "onStop()");

        // unregister activity from event bus
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this);

        super.onStop();

        // unregisters all broadcast receivers
        unregisterReceivers();
        // saves settings set in this session
        saveSettings();
        // stop audio service
        stop();

        // flush log to file and release resources
        Log4a.flush();
        Log4a.release();
    }

    //////////////////////////////////////////////////////////////////////////////
    //                       OnKey methods
    //////////////////////////////////////////////////////////////////////////////

    @Override public void onBackPressed() {
        boolean bShouldPop = true;
        if (currentFrag == ANALYSIS_VIEW) {
            Fragment frag = getSupportFragmentManager().findFragmentByTag(BYB_ANALYSIS_FRAGMENT);
            if (frag != null && frag instanceof AnalysisFragment) {
                bShouldPop = false;
                ((AnalysisFragment) frag).onBackPressed();
            }
        }
        if (bShouldPop && !popFragment()) finish();
    }

    //////////////////////////////////////////////////////////////////////////////
    //                      Fragment management
    //////////////////////////////////////////////////////////////////////////////

    public void loadFragment(int fragType, Object... args) {
        if (fragType == R.id.action_scope) {
            fragType = OSCILLOSCOPE_VIEW;
        } else if (fragType == R.id.action_threshold) {
            fragType = THRESHOLD_VIEW;
        } else if (fragType == R.id.action_recordings) {
            fragType = RECORDINGS_VIEW;
        }
        LOGD(TAG, "loadFragment()  fragType: " + fragType + "  currentFrag: " + currentFrag);
        if (fragType != currentFrag) {
            currentFrag = fragType;
            Fragment frag;
            String fragName;
            switch (fragType) {
                //------------------------------
                case RECORDINGS_VIEW:
                    frag = RecordingsFragment.newInstance();
                    fragName = BYB_RECORDINGS_FRAGMENT;
                    break;
                //------------------------------
                case THRESHOLD_VIEW:
                    frag = ThresholdFragment.newInstance();
                    fragName = BYB_THRESHOLD_FRAGMENT;
                    break;
                //------------------------------
                case FIND_SPIKES_VIEW:
                    frag = FindSpikesFragment.newInstance(args.length > 0 ? String.valueOf(args[0]) : null);
                    fragName = BYB_SPIKES_FRAGMENT;
                    break;
                //------------------------------
                case ANALYSIS_VIEW:
                    frag = AnalysisFragment.newInstance(args.length > 0 ? String.valueOf(args[0]) : null,
                        args.length > 0 ? (int) args[1] : AnalysisType.NONE);
                    fragName = BYB_ANALYSIS_FRAGMENT;
                    break;
                //------------------------------
                case OSCILLOSCOPE_VIEW:
                default:
                    frag = OscilloscopeFragment.newInstance();
                    fragName = BYB_OSCILLOSCOPE_FRAGMENT;
                    break;
                //------------------------------
                case PLAY_AUDIO_VIEW:
                    frag = PlaybackScopeFragment.newInstance(args.length > 0 ? String.valueOf(args[0]) : null);
                    fragName = BYB_PLAY_AUDIO_FRAGMENT;
                    break;
            }
            // Log with Fabric Answers what view did the user opened
            Answers.getInstance()
                .logContentView(new ContentViewEvent().putContentName(fragName).putContentType("Screen View"));

            setSelectedButton(fragType);
            showFragment(frag, fragName, R.id.fragment_container, FragTransaction.REPLACE, false, R.anim.slide_in_right,
                R.anim.slide_out_left);
        }
    }

    public boolean popFragment(String fragName) {
        boolean bPopped = false;
        if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
            bPopped = getSupportFragmentManager().popBackStackImmediate(fragName, 0);
            LOGD(TAG, "popFragment name: " + fragName);
            int fragType = getFragmentTypeFromName(fragName);
            if (fragType != INVALID_VIEW) {
                LOGD(TAG, "popFragment type: " + fragType);
                setSelectedButton(fragType);
                currentFrag = fragType;
            }
        } else {
            LOGI(TAG, "popFragment noStack");
        }
        return bPopped;
    }

    public boolean popFragment() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
            int lastFragIndex = getSupportFragmentManager().getBackStackEntryCount() - 2;
            String lastFragName = getSupportFragmentManager().getBackStackEntryAt(lastFragIndex).getName();
            return popFragment(lastFragName);
        } else {
            LOGI(TAG, "popFragment noStack");
            return false;
        }
    }

    private void printBackStack() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            StringBuilder s = new StringBuilder("BackStack:\n");
            for (int i = 0; i < getSupportFragmentManager().getBackStackEntryCount(); i++) {
                s.append(getSupportFragmentManager().getBackStackEntryAt(i).getName()).append("\n");
            }
            LOGD(TAG, s.toString());
        } else {
            LOGI(TAG, "printBackStack noStack");
        }
    }

    public void showFragment(Fragment frag, String fragName, int fragContainer, FragTransaction fragTransaction,
        boolean bAnimate, int animIn, int animOut, boolean bAddToBackStack) {
        android.support.v4.app.FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!popFragment(fragName)) {
            if (bAnimate) {
                transaction.setCustomAnimations(animIn, animOut);
            }
            if (fragTransaction == FragTransaction.REPLACE) {
                transaction.replace(fragContainer, frag, fragName);
                if (bAddToBackStack) {
                    transaction.addToBackStack(fragName);
                }
            } else if (fragTransaction == FragTransaction.REMOVE) {
                transaction.remove(frag);
            } else if (fragTransaction == FragTransaction.ADD) {
                transaction.add(fragContainer, frag, fragName);
                if (bAddToBackStack) {
                    transaction.addToBackStack(fragName);
                }
            }
            transaction.commit();
        }
        printBackStack();
    }

    public void showFragment(Fragment frag, String fragName, int fragContainer, FragTransaction fragTransaction,
        boolean bAnimate, int animIn, int animOut) {
        showFragment(frag, fragName, fragContainer, fragTransaction, bAnimate, animIn, animOut, true);
    }

    //////////////////////////////////////////////////////////////////////////////
    //                      Public Methods
    //////////////////////////////////////////////////////////////////////////////

    public boolean isTouchSupported() {
        return getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    //=================================================
    //  EVENT BUS
    //=================================================

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayAudioFileEvent(PlayAudioFileEvent event) {
        loadFragment(PLAY_AUDIO_VIEW, event.getFilePath());
    }

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFindSpikesEvent(FindSpikesEvent event) {
        loadFragment(FIND_SPIKES_VIEW, event.getFilePath());
    }

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAnalyzeAudioFileEvent(AnalyzeAudioFileEvent event) {
        loadFragment(ANALYSIS_VIEW, event.getFilePath(), event.getType());
    }

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onOpenRecordingsEvent(OpenRecordingsEvent event) {
        loadFragment(RECORDINGS_VIEW);
    }

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onShowToastEvent(ShowToastEvent event) {
        ViewUtils.toast(this, event.getToast());
    }

    @SuppressWarnings("unused") @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNoSubscriberEvent(NoSubscriberEvent event) {
        // this is here to avoid EventBus exception
    }

    //=================================================
    // PRIVATE METHODS
    //=================================================

    // Initializes user interface
    private void setupUI(Bundle savedInstanceState) {
        // load initial fragment
        if (null == savedInstanceState) loadFragment(OSCILLOSCOPE_VIEW);

        // init bottom menu clicks
        bottomMenu.setOnNavigationItemSelectedListener(bottomMenuListener);
    }

    private int getFragmentTypeFromName(String fragName) {
        switch (fragName) {
            case BYB_RECORDINGS_FRAGMENT:
                return RECORDINGS_VIEW;
            case BYB_THRESHOLD_FRAGMENT:
                return THRESHOLD_VIEW;
            case BYB_SPIKES_FRAGMENT:
                return FIND_SPIKES_VIEW;
            case BYB_ANALYSIS_FRAGMENT:
                return ANALYSIS_VIEW;
            case BYB_OSCILLOSCOPE_FRAGMENT:
                return OSCILLOSCOPE_VIEW;
            case BYB_PLAY_AUDIO_FRAGMENT:
                return PLAY_AUDIO_VIEW;
            default:
                return INVALID_VIEW;
        }
    }

    private void setSelectedButton(int select) {
        Intent i = null;
        @IdRes int selectedButton = -1;
        LOGD(TAG, "setSelectedButton");
        switch (select) {
            case OSCILLOSCOPE_VIEW:
                selectedButton = R.id.action_scope;
                i = new Intent();
                i.putExtra("tab", OSCILLOSCOPE_VIEW);
                break;
            case THRESHOLD_VIEW:
                selectedButton = R.id.action_threshold;
                i = new Intent();
                i.putExtra("tab", THRESHOLD_VIEW);
                break;
            case RECORDINGS_VIEW:
                selectedButton = R.id.action_recordings;
                i = new Intent();
                i.putExtra("tab", RECORDINGS_VIEW);
                break;
            default:
                break;
        }
        if (i != null) {
            bottomMenu.setOnNavigationItemSelectedListener(null);
            bottomMenu.setSelectedItemId(selectedButton);
            bottomMenu.setOnNavigationItemSelectedListener(bottomMenuListener);
        }
    }

    protected void showButtons(boolean bShow) {
        if (bottomMenu != null) bottomMenu.setVisibility(bShow ? View.VISIBLE : View.GONE);
    }

    void showScalingInstructions() {
        if (showScalingInstructions && !showingScalingInstructions) {
            showingScalingInstructions = true;
            AlertDialog alertDialog = new AlertDialog.Builder(this).create();
            alertDialog.setTitle("Instructions");
            alertDialog.setMessage(getString(R.string.scaling_instructions));
            alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "NO", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    showScalingInstructions = false;
                    showingScalingInstructions = false;
                    saveSettings();
                    dialog.dismiss();
                }
            });
            alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "YES", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    showingScalingInstructions = false;
                    dialog.dismiss();
                }
            });
            alertDialog.show();
        }
    }

    //==============================================
    // RECORD_AUDIO PERMISSION
    //==============================================

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
        @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override public void onPermissionsGranted(int requestCode, @NonNull List<String> perms) {
        LOGD(TAG, "onPermissionsGranted:" + requestCode + ":" + perms.size());
    }

    @Override public void onPermissionsDenied(int requestCode, @NonNull List<String> perms) {
        if (perms.contains(Manifest.permission.RECORD_AUDIO) && requestCode == BYB_RECORD_AUDIO_PERM) {
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
    }

    /**
     * Requests {@link Manifest.permission#RECORD_AUDIO} permission if it's not already allowed and starts {@link
     * AudioService} and {@link AnalysisManager}.
     */
    @AfterPermissionGranted(BYB_RECORD_AUDIO_PERM) private void start() {
        if (EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO)) {
            startAnalysisManager();
            startAudioService();
        } else {
            EasyPermissions.requestPermissions(this, getString(R.string.rationale_record_audio), BYB_RECORD_AUDIO_PERM,
                Manifest.permission.RECORD_AUDIO);
        }
    }

    /**
     * Stops {@link AudioService} and {@link AnalysisManager}. Needs to be called in {@link #onStop()} to release
     * resources.
     */
    private void stop() {
        stopAnalysisManager();
        stopAudioService();
    }

    //==============================================
    //  AUDIO SERVICE
    //==============================================

    @Nullable @Override public AudioService audioService() {
        return audioService;
    }

    @Nullable @Override public AnalysisManager analysisManager() {
        return analysisManager;
    }

    // Starts AudioService
    private void startAudioService() {
        if (!audioServiceRunning) {
            LOGD(TAG, "Starting AudioService");

            startService(new Intent(this, AudioService.class));
            audioServiceRunning = true;
            bindAudioService(true);
        }
    }

    // Stops AudioService
    private void stopAudioService() {
        if (audioServiceRunning) {
            LOGD(TAG, "Stopping AudioService");

            bindAudioService(false);
            stopService(new Intent(this, AudioService.class));
            audioServiceRunning = false;
        }
    }

    protected void bindAudioService(boolean on) {
        if (on) {
            Intent intent = new Intent(this, AudioService.class);
            bindService(intent, audioServiceConnection, Context.BIND_AUTO_CREATE);
        } else {
            unbindService(audioServiceConnection);
        }
    }

    protected ServiceConnection audioServiceConnection = new ServiceConnection() {

        // Sets a reference in this activity to the {@link AudioService}, which
        // allows for {@link ByteBuffer}s full of audio information to be passed
        // from the {@link AudioService} down into the local
        // {@link OscilloscopeGLSurfaceView}
        //
        // @see
        // android.content.ServiceConnection#onServiceConnected(android.content.ComponentName,
        // android.os.IBinder)
        @Override public void onServiceConnected(ComponentName className, IBinder service) {
            LOGD(TAG, "AudioService connected!");
            // We've bound to LocalService, cast the IBinder and get
            // LocalService instance
            AudioService.ServiceBinder binder = (AudioService.ServiceBinder) service;
            audioService = binder.getService();

            // inform interested parties that audio service is successfully connected
            EventBus.getDefault().post(new AudioServiceConnectionEvent(true));
        }

        @Override public void onServiceDisconnected(ComponentName className) {
            LOGD(TAG, "AudioService disconnected!");

            audioService = null;

            // inform interested parties that audio service successfully disconnected
            EventBus.getDefault().post(new AudioServiceConnectionEvent(false));
        }
    };

    //==============================================
    // ANALYSIS MANAGER
    //==============================================

    // Starts AnalysisManager
    private void startAnalysisManager() {
        if (analysisManager == null) {
            LOGD(TAG, "Starting AnalysisManager");

            analysisManager = new AnalysisManager(getApplicationContext());
        }
    }

    // Stops AnalysisManager
    private void stopAnalysisManager() {
        if (analysisManager != null) {
            LOGD(TAG, "Stopping AnalysisManager");

            analysisManager = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- BROADCAST RECEIVERS CLASS
    // ---------------------------------------------------------------------------------------------
    private ChangePageListener changePageListener;

    private ShowScalingInstructionsListener showScalingInstructionsListener;

    private class ChangePageListener extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("page")) {
                if (intent.hasExtra("page")) {
                    loadFragment(intent.getIntExtra("page", 0));
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    private class ShowScalingInstructionsListener extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            showScalingInstructions();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- BROADCAST RECEIVERS TOGGLES
    // -------------------------------------------------------------------------------------------------
    // ----------------------------------------------------------------------------------------

    // ----------------------------------------------------------------------------------------
    private void registerChangePageReceiver(boolean reg) {
        if (reg) {
            IntentFilter intentFilter = new IntentFilter("BYBChangePage");
            changePageListener = new ChangePageListener();
            getApplicationContext().registerReceiver(changePageListener, intentFilter);
        } else {
            getApplicationContext().unregisterReceiver(changePageListener);
        }
    }

    // ----------------------------------------------------------------------------------------
    private void registerShowScalingInstructionsReceiver(boolean reg) {
        if (reg) {
            IntentFilter intentFilter = new IntentFilter("showScalingInstructions");
            showScalingInstructionsListener = new ShowScalingInstructionsListener();
            getApplicationContext().registerReceiver(showScalingInstructionsListener, intentFilter);
        } else {
            getApplicationContext().unregisterReceiver(showScalingInstructionsListener);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- REGISTER RECEIVERS
    // ---------------------------------------------------------------------------------------------
    public void registerReceivers() {
        registerChangePageReceiver(true);
        registerShowScalingInstructionsReceiver(true);
    }

    // ---------------------------------------------------------------------------------------------
    // ----------------------------------------- UNREGISTER RECEIVERS
    // ---------------------------------------------------------------------------------------------
    public void unregisterReceivers() {
        registerChangePageReceiver(false);
        registerShowScalingInstructionsReceiver(false);
    }

    //////////////////////////////////////////////////////////////////////////////
    //                                 Settings
    //////////////////////////////////////////////////////////////////////////////

    public void loadSettings() {
        showScalingInstructions = PrefUtils.isShowScalingInstructions(this, MainActivity.class);
    }

    // ----------------------------------------------------------------------------------------
    public void saveSettings() {
        PrefUtils.setShowScalingInstructions(this, MainActivity.class, showScalingInstructions);
    }
}
