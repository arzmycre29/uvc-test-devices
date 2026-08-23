package com.homesoft.usb.fs.uvc;

import android.util.Log;
import android.view.Surface;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;

public abstract class VideoUrbHandler {
    private static final String TAG = "VideoUrbHandler";

    public int r = -1; // Native handle ID
    public final FormatDesc formatDesc;
    public final FrameDesc frameDesc;
    public final int endpointAddress;
    public final int maxPacketSize;
    public final int packetsPerUrb;
    public final int interval;
    public final int maxPayloadTransferSize;
    public int transform = 0;
    public Surface surface;

    public interface FrameListener {
        void onFrameCaptured(byte[] jpegBytes);
    }

    public FrameListener frameListener;

    public VideoUrbHandler(FormatDesc formatDesc, FrameDesc frameDesc, int endpointAddress, int maxPacketSize, int packetsPerUrb, int interval, int maxPayloadTransferSize) {
        this.formatDesc = formatDesc;
        this.frameDesc = frameDesc;
        this.endpointAddress = endpointAddress;
        this.maxPacketSize = maxPacketSize;
        this.packetsPerUrb = packetsPerUrb;
        this.interval = interval;
        this.maxPayloadTransferSize = maxPayloadTransferSize;
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    public int getWidth() { return frameDesc.getWidth(); }
    public int getHeight() { return frameDesc.getHeight(); }
    public int o() { return getWidth(); }
    public int n() { return getHeight(); }

    public boolean isStarted() { return this.r >= 0; }

    public void triggerSnapshot() {
        if (this.r >= 0) {
            UsbFs.sendMessage(this.r, 1);
        }
    }

    public synchronized boolean setSurface(Surface surface) {
        this.surface = surface;
        if (this.r >= 0) {
            try {
                int res = UsbFs.setSurface(this.r, surface, 842094169, this.transform);
                Log.d(TAG, "UsbFs.setSurface() result: " + res);
                return res == 0;
            } catch (Exception e) {
                Log.e(TAG, "Error in setSurface", e);
                return false;
            }
        }
        return true;
    }

    public synchronized boolean setTransform(int transform) {
        this.transform = transform;
        if (this.r >= 0) {
            try {
                return UsbFs.setTransform(this.r, transform) == 0;
            } catch (Exception e) {
                Log.e(TAG, "Error in setTransform", e);
            }
        }
        return false;
    }

    public void onMsg(int msgNo, int frames, String msg) {
        Log.d(TAG, "onMsg: handle=" + this.r + " msgNo=" + msgNo + " frames=" + frames + " msg=" + msg);
    }

    public synchronized void stop() {
        if (this.r >= 0) {
            Log.d(TAG, "Stopping native handler: " + this.r);
            UsbFs.cleanupHandler(this.r);
            this.r = -1;
        }
    }

    public abstract boolean start() throws Throwable;
}