/*
 * Copyright (C) 2016 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.util.Consumer;
import androidx.lifecycle.ViewModelProvider;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.IdentityKey;
import org.thoughtcrime.securesms.components.TooltipPopup;
import org.thoughtcrime.securesms.components.sensors.DeviceOrientationMonitor;
import org.thoughtcrime.securesms.components.webrtc.CallParticipantsListUpdatePopupWindow;
import org.thoughtcrime.securesms.components.webrtc.CallParticipantsState;
import org.thoughtcrime.securesms.components.webrtc.GroupCallSafetyNumberChangeNotificationUtil;
import org.thoughtcrime.securesms.components.webrtc.PigeonWebRtcCallView;
import org.thoughtcrime.securesms.components.webrtc.WebRtcAudioOutput;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallView;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallViewModel;
import org.thoughtcrime.securesms.components.webrtc.WebRtcControls;
import org.thoughtcrime.securesms.components.webrtc.participantslist.CallParticipantsListDialog;
import org.thoughtcrime.securesms.conversation.ui.error.SafetyNumberChangeDialog;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.messagerequests.CalleeMustAcceptMessageRequestActivity;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.EllapsedTimeFormatter;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.FullscreenHelper;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.ThrottledDebouncer;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil;
import org.thoughtcrime.securesms.webrtc.CallParticipantsViewState;
import org.thoughtcrime.securesms.webrtc.audio.SignalAudioManager;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.thoughtcrime.securesms.components.sensors.Orientation.PORTRAIT_BOTTOM_EDGE;

public class WebRtcCallActivity extends BaseActivity implements SafetyNumberChangeDialog.Callback {

  private static final String TAG = Log.tag(WebRtcCallActivity.class);

  private static final int STANDARD_DELAY_FINISH = 1000;
  private static final int VIBRATE_DURATION      = 50;

  public static final String ANSWER_ACTION   = WebRtcCallActivity.class.getCanonicalName() + ".ANSWER_ACTION";
  public static final String DENY_ACTION     = WebRtcCallActivity.class.getCanonicalName() + ".DENY_ACTION";
  public static final String END_CALL_ACTION = WebRtcCallActivity.class.getCanonicalName() + ".END_CALL_ACTION";

  public static final String EXTRA_ENABLE_VIDEO_IF_AVAILABLE = WebRtcCallActivity.class.getCanonicalName() + ".ENABLE_VIDEO_IF_AVAILABLE";
  public static final String EXTRA_STARTED_FROM_FULLSCREEN   = WebRtcCallActivity.class.getCanonicalName() + ".STARTED_FROM_FULLSCREEN";

  private CallParticipantsListUpdatePopupWindow participantUpdateWindow;
  private DeviceOrientationMonitor              deviceOrientationMonitor;

  private FullscreenHelper                      fullscreenHelper;
  private PigeonWebRtcCallView                  callScreen;
  private TooltipPopup                          videoTooltip;
  private WebRtcCallViewModel                   viewModel;
  public MyReceiver                             myReceiver;
  private boolean                               enableVideoIfAvailable;
  private WindowInfoTrackerCallbackAdapter      windowInfoTrackerCallbackAdapter;
  private WindowLayoutInfoConsumer              windowLayoutInfoConsumer;
  private ThrottledDebouncer                    requestNewSizesThrottle;

  @Override
  protected void attachBaseContext(@NonNull Context newBase) {
    getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    super.attachBaseContext(newBase);
  }

  @SuppressLint("SourceLockedOrientationActivity")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.i(TAG, "onCreate(" + getIntent().getBooleanExtra(EXTRA_STARTED_FROM_FULLSCREEN, false) + ")");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onCreate(savedInstanceState);

    boolean isLandscapeEnabled = getResources().getConfiguration().smallestScreenWidthDp >= 480;

    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setContentView(R.layout.webrtc_call_activity);
    IntentFilter filter = new IntentFilter();
    filter.addAction("Signal_End_Call");
    myReceiver = new MyReceiver();
    registerReceiver(myReceiver,  new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));


    fullscreenHelper = new FullscreenHelper(this);

    setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

    initializeResources();
    initializeViewModel(isLandscapeEnabled);

    processIntent(getIntent());

    enableVideoIfAvailable = getIntent().getBooleanExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE, false);
    getIntent().removeExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE);

    windowLayoutInfoConsumer = new WindowLayoutInfoConsumer();

    windowInfoTrackerCallbackAdapter = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this));
    windowInfoTrackerCallbackAdapter.addWindowLayoutInfoListener(this, SignalExecutors.BOUNDED, windowLayoutInfoConsumer);

    requestNewSizesThrottle = new ThrottledDebouncer(TimeUnit.SECONDS.toMillis(1));
  }

  public class MyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      handleEndCall();
    }
  }


  @SuppressLint("SourceLockedOrientationActivity")
  @Override
  public void onResume() {
    Log.i(TAG, "onResume()");
    super.onResume();
    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    initializeScreenshotSecurity();

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    Log.i(TAG, "onNewIntent(" + intent.getBooleanExtra(EXTRA_STARTED_FROM_FULLSCREEN, false) + ")");
    super.onNewIntent(intent);
    processIntent(intent);
  }

  @Override
  public void onPause() {
    Log.i(TAG, "onPause");
    super.onPause();

    if (!isInPipMode() || isFinishing()) {
      EventBus.getDefault().unregister(this);
    }

    if (!viewModel.isCallStarting()) {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null && state.getCallState().isPreJoinOrNetworkUnavailable()) {
        finish();
      }
    }
  }

  @Override
  protected void onStop() {
    Log.i(TAG, "onStop");
    super.onStop();

    if (!isInPipMode() || isFinishing()) {
      EventBus.getDefault().unregister(this);
      requestNewSizesThrottle.clear();
    }

    if (!viewModel.isCallStarting()) {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null && state.getCallState().isPreJoinOrNetworkUnavailable()) {
        ApplicationDependencies.getSignalCallManager().cancelPreJoin();
      }
    }
  }
  private boolean isInPipMode() {
    return isSystemPipEnabledAndAvailable() && isInPictureInPictureMode();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    windowInfoTrackerCallbackAdapter.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
    EventBus.getDefault().unregister(this);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  protected void onUserLeaveHint() {
    enterPipModeIfPossible();
  }

  @Override
  public void onBackPressed() {
    if (!enterPipModeIfPossible()) {
      super.onBackPressed();
    }
    handleEndCall();
    finish();
  }

  @RequiresApi(api = Build.VERSION_CODES.O) @Override
  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    viewModel.setIsInPipMode(isInPictureInPictureMode);
    participantUpdateWindow.setEnabled(!isInPictureInPictureMode);
  }

  private boolean enterPipModeIfPossible() {
    if (viewModel.canEnterPipMode() && isSystemPipEnabledAndAvailable()) {
      PictureInPictureParams params = new PictureInPictureParams.Builder()
          .setAspectRatio(new Rational(9, 16))
          .build();
      enterPictureInPictureMode(params);
      CallParticipantsListDialog.dismiss(getSupportFragmentManager());

      return true;
    }
    return false;
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
    WebRtcViewModel event = EventBus.getDefault().getStickyEvent(WebRtcViewModel.class);
    switch (keyCode) {
      case KeyEvent.KEYCODE_BACK:
        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which)
          {
            if (which == AlertDialog.BUTTON_POSITIVE) {
              finish();
            }
          }
        };
//        AlertDialog.Builder dialog = new AlertDialog.Builder(this, R.style.Mp02_Signal_PreferenceActivity);
//        AlertDialog.Builder dialog = new AlertDialog.Builder(this, R.style.NoAnimation_Theme_AppCompat_Light_DarkActionBar);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setMessage(getString(R.string.webrtccallactivity_status));
        builder.setNegativeButton(getString(R.string.webrtccallactivity_back), listener);
        builder.setPositiveButton(android.R.string.ok, listener);
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getWindow().setLayout(278, 170);
        return true;
      case KeyEvent.KEYCODE_F4:
        WebRtcCallActivity.this.handleDenyCall();
        if (event.getState() == WebRtcViewModel.State.CALL_CONNECTED ){
          WebRtcCallActivity.this.handleDenyCall();
          return true;
        }
      case KeyEvent.KEYCODE_ENDCALL:
        WebRtcCallActivity.this.handleDenyCall();
        WebRtcCallActivity.this.handleEndCall();
        if (event.getState() == WebRtcViewModel.State.CALL_CONNECTED ){
          WebRtcCallActivity.this.handleDenyCall();
          return true;
        }
      case KeyEvent.KEYCODE_CALL:
        if (event != null && event.getState() == WebRtcViewModel.State.CALL_INCOMING ){
          WebRtcCallActivity.this.handleAnswerWithAudio();
          return true;
        }
      default:
        Log.d(TAG, "onKeyDown: do nothing.");
        break;
    }
    return super.onKeyDown(keyCode, keyEvent);
  }

  private void processIntent(@NonNull Intent intent) {
    if (ANSWER_ACTION.equals(intent.getAction())) {
      handleAnswerWithAudio();
    } else if (DENY_ACTION.equals(intent.getAction())) {
      handleDenyCall();
    } else if (END_CALL_ACTION.equals(intent.getAction())) {
      handleEndCall();
    }
  }

  private void initializeScreenshotSecurity() {
    if (TextSecurePreferences.isScreenSecurityEnabled(this)) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  private void initializeResources() {
    callScreen = findViewById(R.id.callScreen);
    callScreen.setControlsListener(new PigeonControlsListener());

    participantUpdateWindow = new CallParticipantsListUpdatePopupWindow(callScreen);
  }

  private void initializeViewModel(boolean isLandscapeEnabled) {
    deviceOrientationMonitor = new DeviceOrientationMonitor(this);
    getLifecycle().addObserver(deviceOrientationMonitor);

    WebRtcCallViewModel.Factory factory = new WebRtcCallViewModel.Factory(deviceOrientationMonitor);

    viewModel = new ViewModelProvider(this, factory).get(WebRtcCallViewModel.class);
    viewModel.setIsLandscapeEnabled(isLandscapeEnabled);
    viewModel.getMicrophoneEnabled().observe(this, callScreen::setMicEnabled);
    viewModel.getWebRtcControls().observe(this, callScreen::setWebRtcControls);
    viewModel.getEvents().observe(this, this::handleViewModelEvent);
    viewModel.getCallTime().observe(this, this::handleCallTime);
    LiveDataUtil.combineLatest(viewModel.getCallParticipantsState(),
                               viewModel.getOrientationAndLandscapeEnabled(),
                               (s, o) -> new CallParticipantsViewState(s, o.first == PORTRAIT_BOTTOM_EDGE, o.second))
                .observe(this, p -> callScreen.updateCallParticipants(p));
    viewModel.getCallParticipantListUpdate().observe(this, participantUpdateWindow::addCallParticipantListUpdate);
    viewModel.getSafetyNumberChangeEvent().observe(this, this::handleSafetyNumberChangeEvent);
    viewModel.getGroupMembersChanged().observe(this, unused -> updateGroupMembersForGroupCall());
    viewModel.getGroupMemberCount().observe(this, this::handleGroupMemberCountChange);
    viewModel.shouldShowSpeakerHint().observe(this, this::updateSpeakerHint);

    callScreen.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null) {
        if (state.needsNewRequestSizes()) {
          requestNewSizesThrottle.publish(() -> ApplicationDependencies.getSignalCallManager().updateRenderedResolutions());
        }
      }
    });

    viewModel.getOrientationAndLandscapeEnabled().observe(this, pair -> ApplicationDependencies.getSignalCallManager().orientationChanged(pair.second, pair.first.getDegrees()));
//    viewModel.getControlsRotation().observe(this, callScreen::rotateControls);
  }

  private void handleViewModelEvent(@NonNull WebRtcCallViewModel.Event event) {
    if (event instanceof WebRtcCallViewModel.Event.StartCall) {
      startCall(((WebRtcCallViewModel.Event.StartCall) event).isVideoCall());
      return;
    } else if (event instanceof WebRtcCallViewModel.Event.ShowGroupCallSafetyNumberChange) {
      SafetyNumberChangeDialog.showForGroupCall(getSupportFragmentManager(), ((WebRtcCallViewModel.Event.ShowGroupCallSafetyNumberChange) event).getIdentityRecords());
      return;
    }

    if (event instanceof WebRtcCallViewModel.Event.ShowVideoTooltip) {
      if (videoTooltip == null) {
//        videoTooltip = TooltipPopup.forTarget(callScreen.getVideoTooltipTarget())
//                                   .setBackgroundTint(ContextCompat.getColor(this, R.color.core_ultramarine))
//                                   .setTextColor(ContextCompat.getColor(this, R.color.core_white))
//                                   .setText(R.string.WebRtcCallActivity__tap_here_to_turn_on_your_video)
//                                   .setOnDismissListener(() -> viewModel.onDismissedVideoTooltip())
//                                   .show(TooltipPopup.POSITION_ABOVE);
        return;
      }
    } else if (event instanceof WebRtcCallViewModel.Event.DismissVideoTooltip) {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
        videoTooltip = null;
      }
    } else {
      throw new IllegalArgumentException("Unknown event: " + event);
    }
  }

  private void handleCallTime(long callTime) {
    EllapsedTimeFormatter ellapsedTimeFormatter = EllapsedTimeFormatter.fromDurationMillis(callTime);

    if (ellapsedTimeFormatter == null) {
      return;
    }

//    callScreen.setStatus(getString(R.string.WebRtcCallActivity__signal_s, ellapsedTimeFormatter.toString()));
    callScreen.setElapsedTime(ellapsedTimeFormatter.toString());
  }

  private void handleSetAudioHandset() {
    ApplicationDependencies.getSignalCallManager().selectAudioDevice(SignalAudioManager.AudioDevice.EARPIECE);
  }

  private void handleSetAudioSpeaker() {
    ApplicationDependencies.getSignalCallManager().selectAudioDevice(SignalAudioManager.AudioDevice.SPEAKER_PHONE);
  }

  private void handleSetAudioBluetooth() {
    ApplicationDependencies.getSignalCallManager().selectAudioDevice(SignalAudioManager.AudioDevice.BLUETOOTH);
  }

  private void handleSetMuteAudio(boolean enabled) {
    ApplicationDependencies.getSignalCallManager().setMuteAudio(enabled);
  }

  private void handleSetMuteVideo(boolean muted) {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      String recipientDisplayName = recipient.getDisplayName(this);

      Permissions.with(this)
                 .request(Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName), R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName))
                 .onAllGranted(() -> ApplicationDependencies.getSignalCallManager().setMuteVideo(!muted))
                 .execute();
    }
  }

  private void handleFlipCamera() {
    ApplicationDependencies.getSignalCallManager().flipCamera();
  }

  private void handleAnswerWithAudio() {
    Permissions.with(this)
               .request(Manifest.permission.RECORD_AUDIO)
               .ifNecessary()
               .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_give_signal_access_to_your_microphone),
                                    R.drawable.ic_mic_solid_24)
               .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
               .onAllGranted(() -> {
                 callScreen.setStatus(getString(R.string.RedPhone_answering));

                 ApplicationDependencies.getSignalCallManager().acceptCall(false);
               })
               .onAnyDenied(this::handleDenyCall)
               .execute();
  }

  private void handleAnswerWithVideo() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Permissions.with(this)
                 .request(Manifest.permission.RECORD_AUDIO)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_from_s_give_signal_access_to_your_microphone, recipient.getDisplayName(this)),
                                      R.drawable.ic_mic_solid_24, R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
                 .onAllGranted(() -> {
                   callScreen.setRecipient(recipient);
                   callScreen.setStatus(getString(R.string.RedPhone_answering));

                   ApplicationDependencies.getSignalCallManager().acceptCall(true);

                   handleSetMuteVideo(false);
                 })
                 .onAnyDenied(this::handleDenyCall)
                 .execute();
    }
  }

  private void handleDenyCall() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      ApplicationDependencies.getSignalCallManager().denyCall();

      callScreen.setRecipient(recipient);
      callScreen.setStatus(getString(R.string.RedPhone_ending_call));
      delayedFinish();
    }
  }

  private void handleVolumePressed() {
    startActivity(new Intent(this, WebRtcCallVolumeActivity.class));
  }

  private void handleEndCall() {
    Log.i(TAG, "Hangup pressed, handling termination now...");
    ApplicationDependencies.getSignalCallManager().localHangup();
  }

  private void handleOutgoingCall(@NonNull WebRtcViewModel event) {
    if (event.getGroupState().isNotIdle()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
    } else {
      callScreen.setStatus(getString(R.string.WebRtcCallActivity__calling));
    }
  }

  private void handleTerminate(@NonNull Recipient recipient, @NonNull HangupMessage.Type hangupType) {
    Log.i(TAG, "handleTerminate called: " + hangupType.name());

    callScreen.setStatusFromHangupType(hangupType);

    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);

    if (hangupType == HangupMessage.Type.NEED_PERMISSION) {
      startActivity(CalleeMustAcceptMessageRequestActivity.createIntent(this, recipient.getId()));
    }
    delayedFinish();
  }

  private void handleGlare(@NonNull Recipient recipient) {
    Log.i(TAG, "handleGlare: " + recipient.getId());

    callScreen.setStatus("");
  }

  private void handleCallRinging() {
    callScreen.setStatus(getString(R.string.RedPhone_ringing));
  }

  private void handleCallBusy() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_busy));
    delayedFinish(SignalCallManager.BUSY_TONE_LENGTH);
  }

  private void handleCallConnected(@NonNull WebRtcViewModel event) {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);
    if (event.getGroupState().isNotIdleOrConnected()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
    } else {
      callScreen.setStatus(getString(R.string.RedPhone_connected));
      callScreen.enableElapsedTime(true);
    }
  }

  private void handleRecipientUnavailable() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_recipient_unavailable));
    delayedFinish();
  }

  private void handleServerFailure() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_network_failed));
  }

  private void handleNoSuchUser(final @NonNull WebRtcViewModel event) {
    if (isFinishing()) return; // XXX Stuart added this check above, not sure why, so I'm repeating in ignorance. - moxie
    new AlertDialog.Builder(this)
        .setTitle(R.string.RedPhone_number_not_registered)
        .setIcon(R.drawable.ic_warning)
        .setMessage(R.string.RedPhone_the_number_you_dialed_does_not_support_secure_voice)
        .setCancelable(true)
        .setPositiveButton(R.string.RedPhone_got_it, (d, w) -> handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL))
        .setOnCancelListener(d -> handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL))
        .show();
  }

  private void handleUntrustedIdentity(@NonNull WebRtcViewModel event) {
    final IdentityKey theirKey  = event.getRemoteParticipants().get(0).getIdentityKey();
    final Recipient   recipient = event.getRemoteParticipants().get(0).getRecipient();

    if (theirKey == null) {
      Log.w(TAG, "Untrusted identity without an identity key, terminating call.");
      handleTerminate(recipient, HangupMessage.Type.NORMAL);
    }

    SafetyNumberChangeDialog.showForCall(getSupportFragmentManager(), recipient.getId());
  }

  public void handleSafetyNumberChangeEvent(@NonNull WebRtcCallViewModel.SafetyNumberChangeEvent safetyNumberChangeEvent) {
    if (Util.hasItems(safetyNumberChangeEvent.getRecipientIds())) {
      if (safetyNumberChangeEvent.isInPipMode()) {
        GroupCallSafetyNumberChangeNotificationUtil.showNotification(this, viewModel.getRecipient().get());
      } else {
        GroupCallSafetyNumberChangeNotificationUtil.cancelNotification(this, viewModel.getRecipient().get());
        SafetyNumberChangeDialog.showForDuringGroupCall(getSupportFragmentManager(), safetyNumberChangeEvent.getRecipientIds());
      }
    }
  }

  private void updateGroupMembersForGroupCall() {
    ApplicationDependencies.getSignalCallManager().requestUpdateGroupMembers();
  }

  public void handleGroupMemberCountChange(int count) {
    boolean canRing = count <= FeatureFlags.maxGroupCallRingSize();
    callScreen.enableRingGroup(canRing);
    ApplicationDependencies.getSignalCallManager().setRingGroup(canRing);
  }

  private void updateSpeakerHint(boolean showSpeakerHint) {
    if (showSpeakerHint) {
      callScreen.showSpeakerViewHint();
    } else {
      callScreen.hideSpeakerViewHint();
    }
  }

  @Override
  public void onSendAnywayAfterSafetyNumberChange(@NonNull List<RecipientId> changedRecipients) {
    CallParticipantsState state = viewModel.getCallParticipantsState().getValue();

    if (state == null) {
      return;
    }

    if (state.getGroupCallState().isConnected()) {
      ApplicationDependencies.getSignalCallManager().groupApproveSafetyChange(changedRecipients);
    } else {
      viewModel.startCall(state.getLocalParticipant().isVideoEnabled());
    }
  }

  @Override
  public void onMessageResentAfterSafetyNumberChange() { }

  @Override
  public void onCanceled() {
    CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
    if (state != null && state.getGroupCallState().isNotIdle()) {
      if (state.getCallState().isPreJoinOrNetworkUnavailable()) {
        ApplicationDependencies.getSignalCallManager().cancelPreJoin();
        finish();
      } else {
        handleEndCall();
      }
    } else {
      handleTerminate(viewModel.getRecipient().get(), HangupMessage.Type.NORMAL);
    }
  }

  private boolean isSystemPipEnabledAndAvailable() {
    return Build.VERSION.SDK_INT >= 26 &&
           getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
  }

  private void delayedFinish() {
    delayedFinish(STANDARD_DELAY_FINISH);
  }

  private void delayedFinish(int delayMillis) {
    callScreen.postDelayed(WebRtcCallActivity.this::finish, delayMillis);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEventMainThread(@NonNull WebRtcViewModel event) {
    Log.i(TAG, "Got message from service: " + event);

    viewModel.setRecipient(event.getRecipient());
    callScreen.setRecipient(event.getRecipient());

    switch (event.getState()) {
      case CALL_PRE_JOIN:
        handleCallPreJoin(event); break;
      case CALL_CONNECTED:
        handleCallConnected(event); break;
      case NETWORK_FAILURE:
        handleServerFailure(); break;
      case CALL_RINGING:
        handleCallRinging(); break;
      case CALL_DISCONNECTED:
        handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL); break;
      case CALL_DISCONNECTED_GLARE:
        handleGlare(event.getRecipient()); break;
      case CALL_ACCEPTED_ELSEWHERE:
        handleTerminate(event.getRecipient(), HangupMessage.Type.ACCEPTED); break;
      case CALL_DECLINED_ELSEWHERE:
        handleTerminate(event.getRecipient(), HangupMessage.Type.DECLINED); break;
      case CALL_ONGOING_ELSEWHERE:
        handleTerminate(event.getRecipient(), HangupMessage.Type.BUSY); break;
      case CALL_NEEDS_PERMISSION:
        handleTerminate(event.getRecipient(), HangupMessage.Type.NEED_PERMISSION); break;
      case NO_SUCH_USER:
        handleNoSuchUser(event); break;
      case RECIPIENT_UNAVAILABLE:
        handleRecipientUnavailable(); break;
      case CALL_OUTGOING:
        handleOutgoingCall(event); break;
      case CALL_BUSY:
        handleCallBusy(); break;
      case UNTRUSTED_IDENTITY:
        handleUntrustedIdentity(event); break;
    }

    boolean enableVideo = event.getLocalParticipant().getCameraState().getCameraCount() > 0 && enableVideoIfAvailable;

    viewModel.updateFromWebRtcViewModel(event, enableVideo);

    if (enableVideo) {
      enableVideoIfAvailable = false;
      handleSetMuteVideo(false);
    }
  }

  private void handleCallPreJoin(@NonNull WebRtcViewModel event) {
    if (event.getGroupState().isNotIdle()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
      callScreen.setRingGroup(event.shouldRingGroup());

      if (event.shouldRingGroup() && event.areRemoteDevicesInCall()) {
        ApplicationDependencies.getSignalCallManager().setRingGroup(false);
      }
    }
  }

  private void startCall(boolean isVideoCall) {
    enableVideoIfAvailable = isVideoCall;

    if (isVideoCall) {
      ApplicationDependencies.getSignalCallManager().startOutgoingVideoCall(viewModel.getRecipient().get());
    } else {
      ApplicationDependencies.getSignalCallManager().startOutgoingAudioCall(viewModel.getRecipient().get());
    }

    MessageSender.onMessageSent();
  }

  private final class ControlsListener implements WebRtcCallView.ControlsListener {

    @Override
    public void onStartCall(boolean isVideoCall) {
      viewModel.startCall(isVideoCall);
    }

    @Override
    public void onCancelStartCall() {
      finish();
    }

    @Override
    public void onControlsFadeOut() {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
      }
    }

    @Override
    public void showSystemUI() {
      fullscreenHelper.showSystemUI();
    }

    @Override
    public void hideSystemUI() {
      fullscreenHelper.hideSystemUI();
    }

    @Override
    public void onAudioOutputChanged(@NonNull WebRtcAudioOutput audioOutput) {
      switch (audioOutput) {
        case HANDSET:
          handleSetAudioHandset();
          break;
        case HEADSET:
          handleSetAudioBluetooth();
          break;
        case SPEAKER:
          handleSetAudioSpeaker();
          break;
        default:
          throw new IllegalStateException("Unknown output: " + audioOutput);
      }
    }

    @Override
    public void onVideoChanged(boolean isVideoEnabled) {
      handleSetMuteVideo(!isVideoEnabled);
    }

    @Override
    public void onMicChanged(boolean isMicEnabled) {
      handleSetMuteAudio(!isMicEnabled);
    }

    @Override
    public void onCameraDirectionChanged() {
      handleFlipCamera();
    }

    @Override
    public void onEndCallPressed() {
      handleEndCall();
    }

    @Override
    public void onDenyCallPressed() {
      handleDenyCall();
    }

    @Override
    public void onAcceptCallWithVoiceOnlyPressed() {
      handleAnswerWithAudio();
    }

    @Override
    public void onAcceptCallPressed() {
      if (viewModel.isAnswerWithVideoAvailable()) {
        handleAnswerWithVideo();
      } else {
        handleAnswerWithAudio();
      }
    }

    @Override
    public void onShowParticipantsList() {
      CallParticipantsListDialog.show(getSupportFragmentManager());
    }

    @Override
    public void onPageChanged(@NonNull CallParticipantsState.SelectedPage page) {
      viewModel.setIsViewingFocusedParticipant(page);
    }

    @Override
    public void onLocalPictureInPictureClicked() {
      viewModel.onLocalPictureInPictureClicked();
    }

    @Override public void onRingGroupChanged(boolean ringGroup, boolean ringingAllowed) {

    }
  }

  private final class PigeonControlsListener implements PigeonWebRtcCallView.ControlsListener {

    @Override
    public void onStartCall(boolean isVideoCall) {
      viewModel.startCall(isVideoCall);
    }

    @Override
    public void onCancelStartCall() {
      delayedFinish();
    }

    @Override
    public void onControlsFadeOut() {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
      }
    }

    @Override
    public void showSystemUI() {
      fullscreenHelper.showSystemUI();
    }

    @Override
    public void hideSystemUI() {
      fullscreenHelper.hideSystemUI();
    }

    @Override
    public void onAudioOutputChanged(@NonNull WebRtcAudioOutput audioOutput) {
      switch (audioOutput) {
        case HANDSET:
          handleSetAudioHandset();
          break;
        case HEADSET:
          handleSetAudioBluetooth();
          break;
        case SPEAKER:
          handleSetAudioSpeaker();
          break;
        default:
          throw new IllegalStateException("Unknown output: " + audioOutput);
      }
    }

    @Override
    public void onVideoChanged(boolean isVideoEnabled) {
      handleSetMuteVideo(!isVideoEnabled);
    }

    @Override
    public void onVolumePressed() {
      handleVolumePressed();
    }

    @Override
    public void onMicChanged(boolean isMicEnabled) {
      handleSetMuteAudio(!isMicEnabled);
    }

    @Override
    public void onCameraDirectionChanged() {
      handleFlipCamera();
    }

    @Override
    public void onEndCallPressed() {
      handleEndCall();
    }

    @Override
    public void onDenyCallPressed() {
      handleDenyCall();
    }

    @Override
    public void onAcceptCallWithVoiceOnlyPressed() {
      handleAnswerWithAudio();
    }

    @Override
    public void onAcceptCallPressed() {
      if (viewModel.isAnswerWithVideoAvailable()) {
        handleAnswerWithVideo();
      } else {
        handleAnswerWithAudio();
      }
    }

    @Override
    public void onShowParticipantsList() {
      CallParticipantsListDialog.show(getSupportFragmentManager());
    }

    @Override
    public void onPageChanged(@NonNull CallParticipantsState.SelectedPage page) {
      viewModel.setIsViewingFocusedParticipant(page);
    }

    @Override
    public void onLocalPictureInPictureClicked() {
      viewModel.onLocalPictureInPictureClicked();
    }

    @Override
    public void onRingGroupChanged(boolean ringGroup, boolean ringingAllowed) {
      if (ringingAllowed) {
        ApplicationDependencies.getSignalCallManager().setRingGroup(ringGroup);
      } else {
        ApplicationDependencies.getSignalCallManager().setRingGroup(false);
        Toast.makeText(WebRtcCallActivity.this, R.string.WebRtcCallActivity__group_is_too_large_to_ring_the_participants, Toast.LENGTH_SHORT).show();
      }
    }
  }

  private class WindowLayoutInfoConsumer implements Consumer<WindowLayoutInfo> {

    @Override
    public void accept(WindowLayoutInfo windowLayoutInfo) {
      Log.d(TAG, "On WindowLayoutInfo accepted: " + windowLayoutInfo.toString());

      Optional<DisplayFeature> feature = windowLayoutInfo.getDisplayFeatures().stream().filter(f -> f instanceof FoldingFeature).findFirst();
      viewModel.setIsLandscapeEnabled(feature.isPresent());
      setRequestedOrientation(feature.isPresent() ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      if (feature.isPresent()) {
        FoldingFeature foldingFeature = (FoldingFeature) feature.get();
        Rect           bounds         = foldingFeature.getBounds();
        if (foldingFeature.getState() == FoldingFeature.State.HALF_OPENED && bounds.top == bounds.bottom) {
          Log.d(TAG, "OnWindowLayoutInfo accepted: ensure call view is in table-top display mode");
          viewModel.setFoldableState(WebRtcControls.FoldableState.folded(bounds.top));
        } else {
          Log.d(TAG, "OnWindowLayoutInfo accepted: ensure call view is in flat display mode");
          viewModel.setFoldableState(WebRtcControls.FoldableState.flat());
        }
      }
    }
  }
}
