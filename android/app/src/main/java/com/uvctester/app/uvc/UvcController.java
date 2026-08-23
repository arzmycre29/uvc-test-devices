package com.uvctester.app.uvc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import com.homesoft.usb.desc.video.FormatDesc;
import com.homesoft.usb.desc.video.FrameDesc;
import com.homesoft.usb.fs.UsbFs;
import com.homesoft.usb.fs.uvc.MjpegStreamUrbHandler;
import com.homesoft.usb.fs.uvc.VideoUrbHandler;
import com.homesoft.usb.fs.uvc.YuvStreamUrbHandler;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class UvcController {
    private static final String TAG = "UvcController";
    private static final String ACTION_USB_PERMISSION = "com.uvctester.app.USB_PERMISSION";
    private static final AtomicInteger ROUTER_COUNTER = new AtomicInteger(1);

    private static UvcController sInstance;
    private final Context context;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private UsbDevice connectedDevice;
    private UsbDeviceConnection deviceConnection;
    private int currentRouterId = -1;
    private VideoUrbHandler currentHandler;
    private Surface attachedSurface;
    private boolean isStreaming = false;
    private int[] routerStats = new int[2]; // [0] = urbs count, [1] = errors count
    private int lastUrbs = 0;
    private long lastStatsTime = 0;
    private float currentFps = 0.0f;

    public static class ParsedUvc {
        public int vcInterface = -1;
        public int vsInterface = -1;
        public int vsAltSetting = 0;
        public int endpointAddress = 0;
        public int maxPacketSize = 1024;
        public int packetsPerUrb = 4;
        public int maxPayloadTransferSize = 4096;
        public short bcdUVC = 0x0100;
        public final List<FormatDesc> formats = new ArrayList<>();

        public FormatDesc findFormat(String preferredFourCc) {
            for (FormatDesc f : formats) {
                if (f.fourCc.equalsIgnoreCase(preferredFourCc)) return f;
            }
            return formats.isEmpty() ? null : formats.get(0);
        }
    }

    private ParsedUvc currentParsedUvc;

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    public interface CaptureCallback {
        void onSuccess(String dataUrl, int width, int height);
        void onError(String message);
    }

    public static synchronized UvcController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UvcController(context.getApplicationContext());
        }
        return sInstance;
    }

    private UvcController(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        try {
            UsbFs.init(context);
            Log.d(TAG, "Native UsbFs initialized");
        } catch (Throwable t) {
            Log.e(TAG, "Native UsbFs init failed", t);
        }
    }

    public UsbDevice findUvcDevice() {
        if (usbManager == null) return null;
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        if (list == null || list.isEmpty()) return null;

        for (UsbDevice dev : list.values()) {
            if (dev.getDeviceClass() == 14) return dev;
            if (dev.getDeviceClass() == 239 && dev.getDeviceSubclass() == 2) return dev;
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface intf = dev.getInterface(i);
                if (intf.getInterfaceClass() == 14) return dev;
            }
        }
        return null;
    }

    public boolean hasPermission(UsbDevice dev) {
        return usbManager != null && dev != null && usbManager.hasPermission(dev);
    }

    public void requestPermission(final UsbDevice dev, Activity activity, final PermissionCallback callback) {
        if (dev == null) {
            callback.onResult(false);
            return;
        }
        if (hasPermission(dev)) {
            callback.onResult(true);
            return;
        }

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    synchronized (this) {
                        try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
                        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        callback.onResult(granted);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }

        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(dev, pi);
    }

    public ParsedUvc parseDescriptors(UsbDevice dev, UsbDeviceConnection conn) {
        ParsedUvc info = new ParsedUvc();
        byte[] raw = conn.getRawDescriptors();
        if (raw == null || raw.length == 0) return info;

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        FormatDesc curFmt = null;
        int curClass = -1;
        int curSubclass = -1;
        int curIface = -1;
        int curAlt = 0;

        while (buf.hasRemaining()) {
            int len = buf.get() & 255;
            if (len < 2 || buf.remaining() < len - 1) break;
            int type = buf.get() & 255;
            byte[] desc = new byte[len];
            desc[0] = (byte) len;
            desc[1] = (byte) type;
            buf.get(desc, 2, len - 2);
            ByteBuffer db = ByteBuffer.wrap(desc).order(ByteOrder.LITTLE_ENDIAN);

            if (type == 4) { // INTERFACE
                curIface = db.get(2) & 255;
                curAlt = db.get(3) & 255;
                curClass = db.get(5) & 255;
                curSubclass = db.get(6) & 255;

                if (curClass == 14) {
                    if (curSubclass == 1 && info.vcInterface == -1) {
                        info.vcInterface = curIface;
                    } else if (curSubclass == 2 && info.vsInterface == -1) {
                        info.vsInterface = curIface;
                    }
                }
            } else if (type == 5) { // ENDPOINT
                int epAddr = db.get(2) & 255;
                int attr = db.get(3) & 255;
                int maxPkt = db.getShort(4) & 65535;

                if (curClass == 14 && curSubclass == 2 && (epAddr & 128) == 128) {
                    int mult = ((maxPkt >> 11) & 3) + 1;
                    int pktSize = (maxPkt & 2047) * mult;
                    if (pktSize > 0) {
                        info.endpointAddress = epAddr;
                        info.maxPacketSize = pktSize;
                        info.vsAltSetting = curAlt;
                        info.packetsPerUrb = (attr & 3) == 2 ? 0 : Math.min(Math.max((pktSize + 1023) / 1024, 1), 8);
                        info.maxPayloadTransferSize = Math.max(pktSize * Math.max(info.packetsPerUrb, 1), 4096);
                    }
                }
            } else if (type == 36) { // CS_INTERFACE
                int subtype = db.get(2) & 255;
                if (curClass == 14 && curSubclass == 1 && subtype == 1) { // VC_HEADER
                    info.bcdUVC = db.getShort(3);
                } else if (curClass == 14 && curSubclass == 2) { // Video Streaming
                    if (subtype == 4) { // VS_FORMAT_UNCOMPRESSED (YUY2/NV12)
                        byte fmtIdx = db.get(3);
                        byte numFrames = db.get(4);
                        byte[] guid = new byte[16];
                        db.position(5);
                        db.get(guid);
                        String fourCc = new String(guid, 0, 4);
                        if (!fourCc.equalsIgnoreCase("NV12") && !fourCc.equalsIgnoreCase("YUYV")) fourCc = "YUY2";
                        curFmt = new FormatDesc(fmtIdx, numFrames, fourCc.toUpperCase(), db.get(21));
                        info.formats.add(curFmt);
                    } else if (subtype == 5) { // VS_FRAME_UNCOMPRESSED
                        if (curFmt != null && len >= 26) {
                            curFmt.addFrameDesc(new FrameDesc(db.get(3), db.getShort(5) & 65535, db.getShort(7) & 65535, db.getInt(21), new int[]{db.getInt(21)}));
                        }
                    } else if (subtype == 6) { // VS_FORMAT_MJPEG
                        curFmt = new FormatDesc(db.get(3), db.get(4), "MJPG", db.get(6));
                        info.formats.add(curFmt);
                    } else if (subtype == 7) { // VS_FRAME_MJPEG
                        if (curFmt != null && len >= 26) {
                            curFmt.addFrameDesc(new FrameDesc(db.get(3), db.getShort(5) & 65535, db.getShort(7) & 65535, db.getInt(21), new int[]{db.getInt(21)}));
                        }
                    }
                }
            }
        }

        // Fallback interface search
        if (info.endpointAddress == 0) {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface intf = dev.getInterface(i);
                if (intf.getInterfaceClass() == 14 && intf.getInterfaceSubclass() == 2) {
                    info.vsInterface = intf.getId();
                    for (int ep = 0; ep < intf.getEndpointCount(); ep++) {
                        UsbEndpoint endpoint = intf.getEndpoint(ep);
                        if (endpoint.getDirection() == 128) {
                            info.endpointAddress = endpoint.getAddress();
                            info.maxPacketSize = Math.max(endpoint.getMaxPacketSize(), 1024);
                            info.packetsPerUrb = 4;
                            info.maxPayloadTransferSize = info.maxPacketSize * 4;
                            break;
                        }
                    }
                }
            }
        }
        return info;
    }

    public synchronized boolean startStream(UsbDevice dev, Surface surface, int targetW, int targetH, String preferredFormat, boolean mirror) {
        if (dev == null) return false;
        stopStream();

        this.connectedDevice = dev;
        this.attachedSurface = surface;

        try {
            this.deviceConnection = usbManager.openDevice(dev);
            if (this.deviceConnection == null) {
                Log.e(TAG, "Cannot open UsbDeviceConnection");
                return false;
            }

            this.currentParsedUvc = parseDescriptors(dev, deviceConnection);
            FormatDesc format = currentParsedUvc.findFormat(preferredFormat);
            if (format == null) {
                Log.e(TAG, "Format not supported");
                return false;
            }

            FrameDesc frame = format.getClosestFrameDesc(targetW, targetH);
            if (frame == null) frame = format.getFrameDesc(format.defaultFrameIndex);
            if (frame == null && !format.frameList.isEmpty()) frame = format.frameList.get(0);
            if (frame == null) {
                Log.e(TAG, "No frame resolution available");
                return false;
            }

            Log.i(TAG, "Selected Format: " + format.fourCc + ", Frame: " + frame.getWidth() + "x" + frame.getHeight() + " @" + frame.getFps() + "fps");

            // Claim VS Interface
            int vsIface = currentParsedUvc.vsInterface >= 0 ? currentParsedUvc.vsInterface : 1;
            UsbInterface uIface = dev.getInterface(vsIface);
            deviceConnection.claimInterface(uIface, true);

            // Complete 4-Step UVC Probe & Commit Handshake
            int probeLen = currentParsedUvc.bcdUVC >= 0x0150 ? 48 : (currentParsedUvc.bcdUVC >= 0x0110 ? 34 : 26);
            byte[] probeBuf = new byte[probeLen];
            ByteBuffer pb = ByteBuffer.wrap(probeBuf).order(ByteOrder.LITTLE_ENDIAN);

            // 1. GET_CUR on VS_PROBE_CONTROL
            int rc = deviceConnection.controlTransfer(161, 129, 256, vsIface, probeBuf, probeLen, 1500);
            Log.d(TAG, "Probe GET_CUR rc=" + rc);

            // 2. Modify Probe Parameters
            pb.putShort(0, (short) 1); // bmHint: FrameInterval preferred
            pb.put(2, format.formatIndex); // bFormatIndex
            pb.put(3, frame.frameIndex); // bFrameIndex
            pb.putInt(4, frame.defaultFrameInterval); // dwFrameInterval

            // 3. SET_CUR on VS_PROBE_CONTROL
            rc = deviceConnection.controlTransfer(33, 1, 256, vsIface, probeBuf, probeLen, 1500);
            Log.d(TAG, "Probe SET_CUR rc=" + rc);

            // 4. GET_CUR on VS_PROBE_CONTROL (Read negotiated max payload)
            rc = deviceConnection.controlTransfer(161, 129, 256, vsIface, probeBuf, probeLen, 1500);
            int negotiatedMaxPayload = pb.getInt(22);
            Log.d(TAG, "Probe Negotiated payload=" + negotiatedMaxPayload);
            if (negotiatedMaxPayload > 0) {
                currentParsedUvc.maxPayloadTransferSize = negotiatedMaxPayload;
            }

            // 5. SET_CUR on VS_COMMIT_CONTROL
            rc = deviceConnection.controlTransfer(33, 1, 512, vsIface, probeBuf, probeLen, 1500);
            Log.d(TAG, "Commit SET_CUR rc=" + rc);

            // 6. Switch Interface to active AltSetting for Isochronous streaming
            if (currentParsedUvc.vsAltSetting > 0) {
                try {
                    deviceConnection.setInterface(uIface);
                } catch (Exception ignored) {}
            }

            // Start URB Router Listener Thread
            final int routerId = ROUTER_COUNTER.getAndIncrement();
            this.currentRouterId = routerId;
            final int fd = deviceConnection.getFileDescriptor();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "URB Router listen started for ID=" + routerId);
                    int res = UsbFs.urbRouterListen(fd, routerId, routerStats);
                    Log.d(TAG, "URB Router listen ended: " + res);
                }
            }, "UrbRouter-" + routerId).start();

            // Create Video Handler
            if ("MJPG".equalsIgnoreCase(format.fourCc)) {
                this.currentHandler = new MjpegStreamUrbHandler(
                    format, frame,
                    currentParsedUvc.endpointAddress,
                    currentParsedUvc.maxPacketSize,
                    currentParsedUvc.packetsPerUrb,
                    frame.defaultFrameInterval,
                    currentParsedUvc.maxPayloadTransferSize
                );
            } else {
                int imgFormat = "NV12".equalsIgnoreCase(format.fourCc) ? 33 : 20;
                this.currentHandler = new YuvStreamUrbHandler(
                    format, frame,
                    currentParsedUvc.endpointAddress,
                    currentParsedUvc.maxPacketSize,
                    currentParsedUvc.packetsPerUrb,
                    frame.defaultFrameInterval,
                    currentParsedUvc.maxPayloadTransferSize,
                    imgFormat
                );
            }

            if (this.attachedSurface != null) {
                this.currentHandler.setSurface(this.attachedSurface);
            }

            this.isStreaming = this.currentHandler.start();
            this.lastStatsTime = System.currentTimeMillis();
            this.lastUrbs = 0;
            Log.i(TAG, "Stream started successfully: " + this.isStreaming + " handle=" + this.currentHandler.r);
            return this.isStreaming;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start stream", t);
            stopStream();
            return false;
        }
    }

    public synchronized void setSurface(Surface surface) {
        this.attachedSurface = surface;
        if (currentHandler != null) {
            currentHandler.setSurface(surface);
        }
    }

    public synchronized void capturePhoto(boolean mirror, final CaptureCallback callback) {
        if (currentHandler == null || !isStreaming) {
            callback.onError("Camera not streaming");
            return;
        }

        final boolean[] done = new boolean[]{false};
        final Runnable timeout = () -> {
            synchronized (UvcController.this) {
                if (!done[0]) {
                    done[0] = true;
                    if (currentHandler != null) currentHandler.setFrameListener(null);
                    callback.onError("Capture timed out (3500ms)");
                }
            }
        };
        mainHandler.postDelayed(timeout, 3500);

        currentHandler.setFrameListener(new VideoUrbHandler.FrameListener() {
            @Override
            public void onFrameCaptured(final byte[] jpegBytes) {
                synchronized (UvcController.this) {
                    if (done[0]) return;
                    done[0] = true;
                    mainHandler.removeCallbacks(timeout);
                    if (currentHandler != null) currentHandler.setFrameListener(null);

                    if (jpegBytes == null || jpegBytes.length == 0) {
                        mainHandler.post(() -> callback.onError("Empty frame"));
                        return;
                    }

                    new Thread(() -> {
                        try {
                            byte[] finalBytes = jpegBytes;
                            Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                            if (bmp != null) {
                                if (mirror) {
                                    Matrix m = new Matrix();
                                    m.preScale(-1.0f, 1.0f);
                                    Bitmap mb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    mb.compress(Bitmap.CompressFormat.JPEG, 95, baos);
                                    finalBytes = baos.toByteArray();
                                    bmp.recycle();
                                    mb.recycle();
                                }
                            }
                            int w = bmp != null ? bmp.getWidth() : currentHandler.getWidth();
                            int h = bmp != null ? bmp.getHeight() : currentHandler.getHeight();
                            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(finalBytes, Base64.NO_WRAP);
                            mainHandler.post(() -> callback.onSuccess(dataUrl, w, h));
                        } catch (Exception e) {
                            mainHandler.post(() -> callback.onError("Encode error: " + e.getMessage()));
                        }
                    }).start();
                }
            }
        });

        currentHandler.triggerSnapshot();
    }

    public synchronized void stopStream() {
        this.isStreaming = false;
        if (currentHandler != null) {
            try { currentHandler.stop(); } catch (Exception ignored) {}
            currentHandler = null;
        }
        if (currentRouterId >= 0) {
            try { UsbFs.urbRouterShutdown(currentRouterId); } catch (Exception ignored) {}
            currentRouterId = -1;
        }
        if (deviceConnection != null) {
            try { deviceConnection.close(); } catch (Exception ignored) {}
            deviceConnection = null;
        }
        connectedDevice = null;
        Log.d(TAG, "Stream stopped");
    }

    public boolean isStreaming() { return isStreaming; }

    public int getHandleId() { return currentHandler != null ? currentHandler.r : -1; }

    public float calculateFps() {
        long now = System.currentTimeMillis();
        long diffTime = now - lastStatsTime;
        if (diffTime >= 1000) {
            int urbs = routerStats[0];
            int diffUrbs = urbs - lastUrbs;
            currentFps = (diffUrbs * 1000.0f) / (diffTime * Math.max(currentParsedUvc != null ? currentParsedUvc.packetsPerUrb : 4, 1));
            lastUrbs = urbs;
            lastStatsTime = now;
        }
        return currentFps;
    }

    public int getUrbsCount() { return routerStats[0]; }
}