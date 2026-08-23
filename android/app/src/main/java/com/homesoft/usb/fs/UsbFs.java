package com.homesoft.usb.fs;

import android.content.Context;
import android.view.Surface;
import com.homesoft.usb.fs.uvc.IYuv420Recorder;
import com.homesoft.usb.fs.uvc.MediaCodecUrbHandler;
import com.homesoft.usb.fs.uvc.MjpegStreamUrbHandler;
import com.homesoft.usb.fs.uvc.PcmAudioUrbHandler;
import com.homesoft.usb.fs.uvc.YuvStreamUrbHandler;

public abstract class UsbFs {
    static {
        System.loadLibrary("usbfs");
    }

    public static native int claimInterface(int i, int i2);
    public static native int cleanupHandler(int i);
    public static native int[] getStats(int i, byte[] bArr);
    public static native Object init(Context context);
    public static native int mediaCodecH264StreamHandler(int i, int i2, int i3, int i4, int i5, int i6, MediaCodecUrbHandler mediaCodecUrbHandler);
    public static native int mediaCodecH265StreamHandler(int i, int i2, int i3, int i4, int i5, int i6, MediaCodecUrbHandler mediaCodecUrbHandler);
    public static native int mpegStreamHandler(int i, int i2, int i3, int i4, int i5, int i6, int i7, MjpegStreamUrbHandler mjpegStreamUrbHandler);
    public static native int pcmStreamHandler(int i, int i2, int i3, PcmAudioUrbHandler pcmAudioUrbHandler, int i4);
    public static native int releaseInterface(int i, int i2);
    public static native int sendMessage(int i, int i2);
    public static native int setInterface(int i, int i2, int i3);
    public static native void setRecorder(int i, IYuv420Recorder iYuv420Recorder);
    public static native int setSurface(int i, Surface surface, int i2, int i3);
    public static native int setTransform(int i, int i2);
    public static native int statusHandler(int i, int i2, IUrbCallback iUrbCallback);
    public static native void swapUVPlane(byte[] bArr, int i, int i2);
    public static native int urbRouterListen(int i, int i2, int[] iArr);
    public static native int urbRouterShutdown(int i);
    public static native int yuvStreamHandler(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, YuvStreamUrbHandler yuvStreamUrbHandler);
}