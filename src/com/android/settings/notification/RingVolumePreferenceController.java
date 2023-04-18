/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.notification;

import android.app.ActivityThread;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import androidx.lifecycle.OnLifecycleEvent;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.settings.R;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.util.Set;

/**
 * This slider represents both ring and notification
 */
public class RingVolumePreferenceController extends
        RingerModeAffectedVolumePreferenceController {

    private static final String KEY_RING_VOLUME = "ring_volume";
    private static final String TAG = "RingVolumePreferenceController";

    private final RingReceiver mReceiver = new RingReceiver();
    private final H mHandler = new H();

    private int mMuteIcon;

    private final int mNormalIconId;
    @VisibleForTesting
    final int mVibrateIconId;
    @VisibleForTesting
    final int mSilentIconId;

    @VisibleForTesting
    final int mTitleId;

    private INotificationManager mNoMan;

    private final boolean mNotifAliasRing;

    public RingVolumePreferenceController(Context context) {
        this(context, KEY_RING_VOLUME);
    }

    public RingVolumePreferenceController(Context context, String key) {
        super(context, key);
        mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator != null && !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

        mNotifAliasRing = context.getResources().getBoolean(
                com.android.internal.R.bool.config_alias_ring_notif_stream_types);

        mTitleId = R.string.separate_ring_volume_option_title;

        mNormalIconId = R.drawable.ic_volume_ringer;
        mSilentIconId = R.drawable.ic_volume_ringer_mute;

        // todo: set a distinct vibrate icon for ring vs notification
        mVibrateIconId = R.drawable.ic_volume_ringer_vibrate;
        mSilentIconId = R.drawable.ic_notifications_off_24dp;

        mSeparateNotification = isSeparateNotificationConfigEnabled();
        updateRingerMode();
    }

    /*
     * Whether ring and notification streams are aliased together by AudioManager.
     * If they are, we'll present one volume control for both.
     * If not, we'll present separate volume controls.
     */
    @VisibleForTesting
    boolean isRingAliasNotification() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.VOLUME_LINK_NOTIFICATION, mNotifAliasRing ? 1 : 0) == 1;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    @Override
    public void onResume() {
        super.onResume();
        mReceiver.register(true);
        readSeparateNotificationVolumeConfig();
        Binder.withCleanCallingIdentity(()
                -> DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                ActivityThread.currentApplication().getMainExecutor(), this::onDeviceConfigChange));
        updateEffectsSuppressor();
        selectPreferenceIconState();

        if (mPreference != null) {
            mPreference.setVisible(getAvailabilityStatus() == AVAILABLE);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    @Override
    public void onPause() {
        super.onPause();
        mReceiver.register(false);
        Binder.withCleanCallingIdentity(() ->
                DeviceConfig.removeOnPropertiesChangedListener(this::onDeviceConfigChange));
    }

    @Override
    public String getPreferenceKey() {
        return KEY_RING_VOLUME;
    }

    @Override
    public int getAvailabilityStatus() {
        boolean separateNotification = isSeparateNotificationConfigEnabled();
        return !separateNotification && !mHelper.isSingleVolume()
                ? AVAILABLE : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public int getAudioStream() {
        return AudioManager.STREAM_RING;
    }

    @Override
    public int getMuteIcon() {
        return mMuteIcon;
    }

    @VisibleForTesting
    void updateRingerMode() {
        final int ringerMode = mHelper.getRingerModeInternal();
        if (mRingerMode == ringerMode) return;
        mRingerMode = ringerMode;
        updatePreferenceIcon();
    }

    private void updateEffectsSuppressor() {
        final ComponentName suppressor = NotificationManager.from(mContext).getEffectsSuppressor();
        if (Objects.equals(suppressor, mSuppressor)) return;

        if (mNoMan == null) {
            mNoMan = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        }

        final int hints;
        try {
            hints = mNoMan.getHintsFromListenerNoToken();
        } catch (android.os.RemoteException ex) {
            Log.w(TAG, "updateEffectsSuppressor: " + ex.getMessage());
            return;
        }

        if (hintsMatch(hints, isRingAliasNotification())) {
            mSuppressor = suppressor;
            if (mPreference != null) {
                final String text = SuppressorHelper.getSuppressionText(mContext, suppressor);
                mPreference.setSuppressionText(text);
            }
        }
    }

    @VisibleForTesting
    boolean hintsMatch(int hints, boolean ringNotificationAliased) {
        return (hints & NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS) != 0
                || (hints & NotificationListenerService.HINT_HOST_DISABLE_EFFECTS) != 0
                || ((hints & NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS)
                != 0 && !notificationSeparated);
    }


    private final class H extends Handler {
        private static final int UPDATE_EFFECTS_SUPPRESSOR = 1;
        private static final int UPDATE_RINGER_MODE = 2;

        private H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPDATE_EFFECTS_SUPPRESSOR:
                    updateEffectsSuppressor();
                    break;
                case UPDATE_RINGER_MODE:
                    updateRingerMode();
                    break;
            }
        }
    }

    private class RingReceiver extends BroadcastReceiver {
        private boolean mRegistered;

        public void register(boolean register) {
            if (mRegistered == register) return;
            if (register) {
                final IntentFilter filter = new IntentFilter();
                filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
                filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
                mContext.registerReceiver(this, filter);
            } else {
                mContext.unregisterReceiver(this);
            }
            mRegistered = register;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(H.UPDATE_EFFECTS_SUPPRESSOR);
            } else if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                mHandler.sendEmptyMessage(H.UPDATE_RINGER_MODE);
            }
        }
    }

}
