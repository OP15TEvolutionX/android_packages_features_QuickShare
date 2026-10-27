/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.wifi.WifiAvailableChannel;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

import java.util.Arrays;

/** Device opt-in adaptation of the original, unmodified Mosey AIDL v2 service. */
public final class QuickShareChannelService extends SystemService {
    private static final String TAG = "QuickShareChannel";
    private static final String DESCRIPTOR = "com.google.android.moseyservice.IMoseyService";
    private static final String SERVICE = DESCRIPTOR + "/default";
    private static final String PACKAGE = "com.google.android.mosey";
    private static final int VERSION_TRANSACTION = 0x00ffffff;
    private static final int[] SOCIAL_CHANNELS = {149, 44};
    // StartMoseyConfig field 5 (channel_hopping), varint wire type.
    private static final byte CHANNEL_HOPPING_TAG = (5 << 3);
    private final ChannelBinder mProxy = new ChannelBinder();
    private volatile IBinder mBackend;
    private Handler mHandler;
    private long mPolicyChecked = -2000;
    private int mEligibleUid = -1;

    public QuickShareChannelService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        // A registration notification also delivers an already registered service.
        // This handles native service restarts without polling or restarting Google apps.
        try {
            ServiceManager.registerForNotifications(SERVICE, new IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder service) {
                    if (service != mProxy) mHandler.post(() -> attachBackend(service));
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Cannot observe native service; leaving it unchanged", e);
        }
    }

    private void attachBackend(IBinder backend) {
        if (backend == null || backend == mProxy || backend == mBackend) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            if (!DESCRIPTOR.equals(backend.getInterfaceDescriptor())) return;
            data.writeInterfaceToken(DESCRIPTOR);
            if (!backend.transact(VERSION_TRANSACTION, data, reply, 0)) return;
            reply.readException();
            if (reply.readInt() != 2) {
                Slog.w(TAG, "Untested native API; leaving service unchanged");
                return;
            }
            mBackend = backend;
            ServiceManager.addService(SERVICE, mProxy, false,
                    android.os.IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
            Slog.i(TAG, "Attached to original Mosey AIDL v2 service");
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot attach to native service", e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private synchronized boolean canUse149(int uid) {
        long now = SystemClock.elapsedRealtime();
        if (now - mPolicyChecked < 2000) return uid == mEligibleUid;
        mEligibleUid = -1;
        long identity = Binder.clearCallingIdentity();
        try {
            PackageInfo info = getContext().getPackageManager().getPackageInfo(PACKAGE, 0);
            WifiManager wifi = getContext().getSystemService(WifiManager.class);
            // No app version check: the request layout is pinned by the native AIDL v2
            // check in attachBackend() and validated by channelOffset()/configOffset().
            if (info.applicationInfo != null
                    && wifi != null && "RU".equals(wifi.getCountryCode())) {
                for (WifiAvailableChannel channel : wifi.getAllowedChannels(
                        WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS,
                        WifiAvailableChannel.OP_MODE_WIFI_AWARE)) {
                    if (channel.getFrequencyMhz() == 5745) {
                        mEligibleUid = info.applicationInfo.uid;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Channel policy unavailable; leaving request unchanged");
        } finally {
            Binder.restoreCallingIdentity(identity);
            mPolicyChecked = now;
        }
        return uid == mEligibleUid;
    }

    // Stable AIDL StartArgs: presence, parcelable size, int[] channels. Never
    // deserialize/rebuild the remaining payload, which includes Binder callbacks.
    static int channelOffset(Parcel parcel) {
        int saved = parcel.dataPosition();
        try {
            parcel.setDataPosition(0);
            parcel.enforceInterface(DESCRIPTOR);
            if (parcel.readInt() != 1) return -1;
            int start = parcel.dataPosition();
            int size = parcel.readInt();
            if (size < 16 || size > parcel.dataSize() - start) return -1;
            if (parcel.readInt() != 1) return -1;
            int offset = parcel.dataPosition();
            if (offset + 4 > start + size || parcel.readInt() != 44) return -1;
            return offset;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            parcel.setDataPosition(saved);
        }
    }

    // Finds the serialized StartMoseyConfig byte[] inside StartArgs: a length-prefixed
    // blob that parses exactly as protobuf and starts with field 1 (is_dbs_supported).
    static int configOffset(Parcel parcel, int from, int end) {
        int saved = parcel.dataPosition();
        try {
            for (int pos = from; pos + 5 <= end; pos += 4) {
                parcel.setDataPosition(pos);
                int len = parcel.readInt();
                if (len < 2 || len > 1024 || pos + 4 + len > end) continue;
                parcel.setDataPosition(pos);
                byte[] blob = parcel.createByteArray();
                if (blob != null && blob[0] == 0x08 && isProto(blob)) return pos;
            }
        } catch (RuntimeException e) {
            // Fall through: keep the original config.
        } finally {
            parcel.setDataPosition(saved);
        }
        return -1;
    }

    private static boolean isProto(byte[] b) {
        int i = 0;
        while (i < b.length) {
            int tag = b[i++] & 0xff;
            if (tag >> 3 == 0 || tag >= 0x80) return false;
            switch (tag & 7) {
                case 0:
                    while (i < b.length && (b[i] & 0x80) != 0) i++;
                    i++;
                    break;
                case 2:
                    if (i >= b.length || b[i] < 0) return false;
                    i += 1 + b[i];
                    break;
                default:
                    return false;
            }
        }
        return i == b.length;
    }

    private final class ChannelBinder extends Binder {
        ChannelBinder() {
            attachInterface(null, DESCRIPTOR);
            markVintfStability();
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            IBinder backend = mBackend;
            if (backend == null) throw new RemoteException("Native Mosey service unavailable");
            Parcel copy = Parcel.obtain();
            try {
                int offset = code == 1 ? channelOffset(data) : -1;
                if (offset >= 0 && canUse149(Binder.getCallingUid())) {
                    // Mosey follows the AWDL master's channel sequence, but only on
                    // channels it was started with and only with hopping enabled.
                    // Apple peers pick 44 or 149 depending on their master, so offer
                    // both. Layout: parcelable size, int[] length (1), channel 44, rest.
                    int start = offset - 8;
                    data.setDataPosition(start);
                    int size = data.readInt();
                    int end = start + size;
                    int config = configOffset(data, offset + 4, end);
                    byte[] cfg = null;
                    int configEnd = -1;
                    if (config >= 0) {
                        data.setDataPosition(config);
                        byte[] old = data.createByteArray();
                        configEnd = data.dataPosition();
                        cfg = Arrays.copyOf(old, old.length + 2);
                        cfg[old.length] = CHANNEL_HOPPING_TAG;
                        cfg[old.length + 1] = 1;
                    }
                    copy.appendFrom(data, 0, start);
                    int sizePos = copy.dataPosition();
                    copy.writeInt(0);
                    copy.writeInt(SOCIAL_CHANNELS.length);
                    for (int channel : SOCIAL_CHANNELS) copy.writeInt(channel);
                    if (cfg != null) {
                        copy.appendFrom(data, offset + 4, config - offset - 4);
                        copy.writeByteArray(cfg);
                        copy.appendFrom(data, configEnd, end - configEnd);
                    } else {
                        copy.appendFrom(data, offset + 4, end - offset - 4);
                    }
                    int newEnd = copy.dataPosition();
                    copy.appendFrom(data, end, data.dataSize() - end);
                    copy.setDataPosition(sizePos);
                    copy.writeInt(newEnd - sizePos);
                    Slog.i(TAG, "START channels=[44]->[149,44] hopping=" + (cfg != null)
                            + "; original country preserved");
                } else {
                    copy.appendFrom(data, 0, data.dataSize());
                }
                copy.setDataPosition(0);
                return backend.transact(code, copy, reply, flags);
            } finally {
                copy.recycle();
            }
        }
    }
}
