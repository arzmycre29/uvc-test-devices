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
import android.hardware.usb.UsbConstants;
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
    private int[] routerStats = new int[2];
    private int lastUrbs = 0;
    private long lastStatsTime = 0;
    private float currentFps = 0.0f;
    private String lastError = "";

    public interface LogCallback {
        void onLog(String message, String type);
    }

    public static class ParsedUvc {
        public int vcInterface = -1;
        public int vsInterface = -1;
        public int vsAltSetting = 0;
        public int endpointAddress = 0;
        public int endpointAttributes = 2;
        public int maxPacketSize = 1024;
        public int packetsPerUrb = 0;
        public int maxPayloadTransferSize = 32768;
        public int maxVideoFrameSize = 4147200;
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
    }

    public String testNativeLoad() {
        try {
            boolean ok = UsbFs.loadNative();
            if (ok) {
                return "SUCCESS: libusbfs.so loaded without errors";
            } else {
                return "ERROR: " + UsbFs.nativeLoadError;
            }
        } catch (Throwable t) {
            return "EXCEPTION: " + t.toString();
        }
    }

    public UsbDevice findUvcDevice() {
        if (usbManager == null) return null;
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "Error listing USB devices", e);
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

    public ParsedUvc parseDescriptors(UsbDevice dev, UsbDeviceConnection conn, LogCallback logCb) {
        ParsedUvc info = new ParsedUvc();
        byte[] raw = conn.getRawDescriptors();
        if (raw == null || raw.length == 0) {
            if (logCb != null) logCb.onLog("Raw descriptors is empty, fallback to SDK", "warn");
            return fallbackParseFromSdk(dev, info, logCb);
        }

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

            if (type == 4) {
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
            } else if (type == 5) {
                int epAddr = db.get(2) & 255;
                int attr = db.get(3) & 255;
                int maxPkt = db.getShort(4) & 65535;

                if (curClass == 14 && curSubclass == 2 && (epAddr & 128) == 128) {
                    info.endpointAddress = epAddr;
                    info.endpointAttributes = attr & 3;
                    info.vsAltSetting = curAlt;

                    if ((attr & 3) == 2) {
                        info.maxPacketSize = maxPkt & 2047;
                        info.packetsPerUrb = 0;
                        info.maxPayloadTransferSize = 32768;
                    } else {
                        int mult = ((maxPkt >> 11) & 3) + 1;
                        int pktSize = (maxPkt & 2047) * mult;
                        info.maxPacketSize = pktSize;
                        info.packetsPerUrb = Math.min(Math.max((pktSize + 1023) / 1024, 1), 8);
                        info.maxPayloadTransferSize = Math.max(pktSize * Math.max(info.packetsPerUrb, 1), 4096);
                    }
                }
            } else if (type == 36) {
                int subtype = db.get(2) & 255;
                if (curClass == 14 && curSubclass == 1 && subtype == 1) {
                    info.bcdUVC = db.getShort(3);
                } else if (curClass == 14 && curSubclass == 2) {
                    if (subtype == 4) {
                        byte fmtIdx = db.get(3);
                        byte numFrames = db.get(4);
                        byte[] guid = new byte[16];
                        db.position(5);
                        db.get(guid);
                        String fourCc = new String(guid, 0, 4);
                        if (!fourCc.equalsIgnoreCase("NV12") && !fourCc.equalsIgnoreCase("YUYV")) fourCc = "YUY2";
                        curFmt = new FormatDesc(fmtIdx, numFrames, fourCc.toUpperCase(), db.get(21));
                        info.formats.add(curFmt);
                    } else if (subtype == 5) {
                        if (curFmt != null && len >= 26) {
                            curFmt.addFrameDesc(new FrameDesc(db.get(3), db.getShort(5) & 65535, db.getShort(7) & 65535, db.getInt(21), new int[]{db.getInt(21)}));
                        }
                    } else if (subtype == 6) {
                        curFmt = new FormatDesc(db.get(3), db.get(4), "MJPG", db.get(6));
                        info.formats.add(curFmt);
                    } else if (subtype == 7) {
                        if (curFmt != null && len >= 26) {
                            curFmt.addFrameDesc(new FrameDesc(db.get(3), db.getShort(5) & 65535, db.getShort(7) & 65535, db.getInt(21), new int[]{db.getInt(21)}));
                        }
                    }
                }
            }
        }

        if (info.formats.isEmpty() || info.endpointAddress == 0) {
            return fallbackParseFromSdk(dev, info, logCb);
        }

        return info;
    }

    private ParsedUvc fallbackParseFromSdk(UsbDevice dev, ParsedUvc info, LogCallback logCb) {
        if (logCb != null) logCb.onLog("Using SDK Interface Enumerator for UVC endpoints...", "info");
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface intf = dev.getInterface(i);
            if (intf.getInterfaceClass() == 14) {
                if (intf.getInterfaceSubclass() == 1 && info.vcInterface == -1) {
                    info.vcInterface = intf.getId();
                } else if (intf.getInterfaceSubclass() == 2) {
                    if (info.vsInterface == -1) info.vsInterface = intf.getId();
                    for (int ep = 0; ep < intf.getEndpointCount(); ep++) {
                        UsbEndpoint endpoint = intf.getEndpoint(ep);
                        if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                            info.endpointAddress = endpoint.getAddress();
                            info.endpointAttributes = endpoint.getType();
                            info.maxPacketSize = endpoint.getMaxPacketSize();
                            info.packetsPerUrb = (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) ? 0 : 4;
                            info.maxPayloadTransferSize = 32768;
                            break;
                        }
                    }
                }
            }
        }

        if (info.formats.isEmpty()) {
            FormatDesc mjpg = new FormatDesc((byte) 1, (byte) 3, "MJPG", (byte) 1);
            mjpg.addFrameDesc(new FrameDesc((byte) 1, 1920, 1080, 333333, new int[]{333333}));
            mjpg.addFrameDesc(new FrameDesc((byte) 2, 1280, 720, 333333, new int[]{333333}));
            mjpg.addFrameDesc(new FrameDesc((byte) 3, 640, 480, 333333, new int[]{333333}));
            info.formats.add(mjpg);

            FormatDesc yuy2 = new FormatDesc((byte) 2, (byte) 3, "YUY2", (byte) 1);
            yuy2.addFrameDesc(new FrameDesc((byte) 1, 1280, 720, 333333, new int[]{333333}));
            yuy2.addFrameDesc(new FrameDesc((byte) 2, 640, 480, 333333, new int[]{333333}));
            info.formats.add(yuy2);
        }

        return info;
    }

    public synchronized boolean startStream(UsbDevice dev, Surface surface, int targetW, int targetH, String preferredFormat, boolean mirror, LogCallback logCb) {
        if (dev == null) {
            lastError = "No UsbDevice";
            if (logCb != null) logCb.onLog(lastError, "error");
            return false;
        }
        stopStream();

        this.connectedDevice = dev;
        this.attachedSurface = surface;

        try {
            if (!UsbFs.loadNative()) {
                lastError = "Native library failed to load: " + UsbFs.nativeLoadError;
                if (logCb != null) logCb.onLog(lastError, "error");
                return false;
            }

            this.deviceConnection = usbManager.openDevice(dev);
            if (this.deviceConnection == null) {
                lastError = "usbManager.openDevice returned null (check OTG cable & permission)";
                if (logCb != null) logCb.onLog(lastError, "error");
                return false;
            }
            int fd = deviceConnection.getFileDescriptor();
            if (logCb != null) logCb.onLog("USB Connection open OK, fd=" + fd, "success");

            this.currentParsedUvc = parseDescriptors(dev, deviceConnection, logCb);
            if (logCb != null) {
                logCb.onLog("Parsed UVC: VC=" + currentParsedUvc.vcInterface + ", VS=" + currentParsedUvc.vsInterface + ", EP=0x" + Integer.toHexString(currentParsedUvc.endpointAddress) + ", Type=" + (currentParsedUvc.endpointAttributes == 2 ? "BULK" : "ISOC") + ", MaxPkt=" + currentParsedUvc.maxPacketSize + ", Pkts/Urb=" + currentParsedUvc.packetsPerUrb, "info");
            }

            FormatDesc format = currentParsedUvc.findFormat(preferredFormat);
            if (format == null) {
                lastError = "Format " + preferredFormat + " not supported on this device";
                if (logCb != null) logCb.onLog(lastError, "error");
                return false;
            }

            FrameDesc frame = format.getClosestFrameDesc(targetW, targetH);
            if (frame == null) frame = format.getFrameDesc(format.defaultFrameIndex);
            if (frame == null && !format.frameList.isEmpty()) frame = format.frameList.get(0);
            if (frame == null) {
                lastError = "No frame resolution found for " + targetW + "x" + targetH;
                if (logCb != null) logCb.onLog(lastError, "error");
                return false;
            }

            if (logCb != null) {
                logCb.onLog("Selected Target: " + format.fourCc + " (" + frame.getWidth() + "x" + frame.getHeight() + " @" + frame.getFps() + "fps)", "info");
            }

            int vsIface = currentParsedUvc.vsInterface >= 0 ? currentParsedUvc.vsInterface : 1;
            UsbInterface uIface = dev.getInterface(vsIface);
            boolean claimed = deviceConnection.claimInterface(uIface, true);
            if (logCb != null) logCb.onLog("Claim VS Interface #" + vsIface + ": " + (claimed ? "SUCCESS" : "FAILED"), claimed ? "info" : "warn");

            int probeLen = currentParsedUvc.bcdUVC >= 0x0150 ? 48 : (currentParsedUvc.bcdUVC >= 0x0110 ? 34 : 26);
            byte[] probeBuf = new byte[probeLen];
            ByteBuffer pb = ByteBuffer.wrap(probeBuf).order(ByteOrder.LITTLE_ENDIAN);

            int rc1 = deviceConnection.controlTransfer(161, 129, 256, vsIface, probeBuf, probeLen, 1500);
            if (logCb != null) logCb.onLog("1. Probe GET_CUR: rc=" + rc1 + " (len=" + probeLen + ")", rc1 >= 0 ? "info" : "warn");

            pb.putShort(0, (short) 1);
            pb.put(2, format.formatIndex);
            pb.put(3, frame.frameIndex);
            pb.putInt(4, frame.defaultFrameInterval > 0 ? frame.defaultFrameInterval : 333333);

            int rc2 = deviceConnection.controlTransfer(33, 1, 256, vsIface, probeBuf, probeLen, 1500);
            if (logCb != null) logCb.onLog("2. Probe SET_CUR: rc=" + rc2, rc2 >= 0 ? "info" : "warn");

            int rc3 = deviceConnection.controlTransfer(161, 129, 256, vsIface, probeBuf, probeLen, 1500);
            int negotiatedMaxPayload = pb.getInt(22);
            int negotiatedMaxVideoFrame = pb.getInt(18);
            if (logCb != null) logCb.onLog("3. Negotiated Payload Size: " + negotiatedMaxPayload + " bytes, MaxFrame: " + negotiatedMaxVideoFrame + " bytes", "info");
            
            if (negotiatedMaxPayload > 0) {
                currentParsedUvc.maxPayloadTransferSize = negotiatedMaxPayload;
            }
            if (negotiatedMaxVideoFrame > 0) {
                currentParsedUvc.maxVideoFrameSize = negotiatedMaxVideoFrame;
            } else {
                currentParsedUvc.maxVideoFrameSize = frame.getWidth() * frame.getHeight() * 2;
            }

            int rc4 = deviceConnection.controlTransfer(33, 1, 512, vsIface, probeBuf, probeLen, 1500);
            if (logCb != null) logCb.onLog("4. Commit SET_CUR: rc=" + rc4, rc4 >= 0 ? "info" : "warn");

            if (currentParsedUvc.vsAltSetting > 0) {
                try {
                    boolean altOk = deviceConnection.setInterface(uIface);
                    if (logCb != null) logCb.onLog("5. Set Interface AltSetting (" + currentParsedUvc.vsAltSetting + "): " + altOk, "info");
                } catch (Exception e) {
                    if (logCb != null) logCb.onLog("Set AltSetting warning: " + e.getMessage(), "warn");
                }
            }

            final int routerId = ROUTER_COUNTER.getAndIncrement();
            this.currentRouterId = routerId;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "URB Router listen started: id=" + routerId);
                    int res = UsbFs.urbRouterListen(fd, routerId, routerStats);
                    Log.d(TAG, "URB Router listen ended: " + res);
                }
            }, "UrbRouter-" + routerId).start();

            if (logCb != null) logCb.onLog("6. URB Router thread started (ID=" + routerId + ")", "info");

            if ("MJPG".equalsIgnoreCase(format.fourCc)) {
                this.currentHandler = new MjpegStreamUrbHandler(
                    format, frame,
                    currentParsedUvc.endpointAddress,
                    currentParsedUvc.maxPacketSize,
                    currentParsedUvc.packetsPerUrb,
                    frame.defaultFrameInterval,
                    currentParsedUvc.maxPayloadTransferSize,
                    currentParsedUvc.maxVideoFrameSize
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
                    currentParsedUvc.maxVideoFrameSize,
                    imgFormat
                );
            }

            if (this.attachedSurface != null) {
                this.currentHandler.setSurface(this.attachedSurface);
            }

            boolean started = this.currentHandler.start();
            if (logCb != null) {
                logCb.onLog("7. Native Handler Start: handle=" + currentHandler.r + " (started=" + started + ")", started ? "success" : "error");
            }

            this.isStreaming = started;
            this.lastStatsTime = System.currentTimeMillis();
            this.lastUrbs = 0;
            return this.isStreaming;
        } catch (Throwable t) {
            lastError = "startStream exception: " + t.toString();
            Log.e(TAG, lastError, t);
            if (logCb != null) logCb.onLog(lastError, "error");
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
    }

    public boolean isStreaming() { return isStreaming; }
    public int getHandleId() { return currentHandler != null ? currentHandler.r : -1; }
    public String getLastError() { return lastError; }

    public float calculateFps() {
        long now = System.currentTimeMillis();
        long diffTime = now - lastStatsTime;
        if (diffTime >= 1000) {
            int urbs = routerStats[0];
            int diffUrbs = urbs - lastUrbs;
            int packets = currentParsedUvc != null && currentParsedUvc.packetsPerUrb > 0 ? currentParsedUvc.packetsPerUrb : 1;
            currentFps = (diffUrbs * 1000.0f) / (diffTime * packets);
            lastUrbs = urbs;
            lastStatsTime = now;
        }
        return currentFps;
    }

    public int getUrbsCount() { return routerStats[0]; }
}