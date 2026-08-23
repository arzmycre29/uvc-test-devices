package com.uvctester.app.uvc;

import android.Manifest;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.homesoft.usb.fs.UsbFs;

@CapacitorPlugin(
    name = "UvcTester",
    permissions = {
        @Permission(
            strings = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO },
            alias = "camera"
        )
    }
)
public class UvcTesterPlugin extends Plugin implements SurfaceHolder.Callback {
    private static final String TAG = "UvcTesterPlugin";

    private UvcController controller;
    private SurfaceView previewSurfaceView;
    private FrameLayout surfaceContainer;
    private SurfaceHolder surfaceHolder;

    private int targetW = 1280;
    private int targetH = 720;
    private String preferredFormat = "MJPG";
    private boolean mirror = false;
    private PluginCall activeStartCall;

    private double cssBoundsX = 0;
    private double cssBoundsY = 0;
    private double cssBoundsW = 0;
    private double cssBoundsH = 0;

    private UvcController getController() {
        if (controller == null) {
            controller = UvcController.getInstance(getContext());
        }
        return controller;
    }

    private void emitLog(String msg, String type) {
        JSObject data = new JSObject();
        data.put("message", msg);
        data.put("type", type);
        notifyListeners("uvcLog", data);
    }

    @PluginMethod
    public void testNative(PluginCall call) {
        try {
            boolean loaded = UsbFs.loadNative();
            JSObject ret = new JSObject();
            ret.put("success", loaded);
            ret.put("error", UsbFs.nativeLoadError != null ? UsbFs.nativeLoadError : "");
            ret.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
            call.resolve(ret);
        } catch (Throwable t) {
            call.reject("Native test exception: " + t.toString());
        }
    }

    @PluginMethod
    public void checkDevice(PluginCall call) {
        try {
            UsbDevice dev = getController().findUvcDevice();
            JSObject ret = new JSObject();
            boolean hasRuntimePerms = getPermissionState("camera") == PermissionState.GRANTED;
            ret.put("runtimePermissions", hasRuntimePerms);

            if (dev != null) {
                ret.put("connected", true);
                ret.put("permission", getController().hasPermission(dev) && hasRuntimePerms);
                ret.put("deviceName", dev.getProductName() != null ? dev.getProductName() : dev.getDeviceName());
                ret.put("vendorId", dev.getVendorId());
                ret.put("productId", dev.getProductId());
            } else {
                ret.put("connected", false);
                ret.put("permission", false);
                ret.put("deviceName", "");
                ret.put("vendorId", 0);
                ret.put("productId", 0);
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermsCallback");
        } else {
            requestUsbPermission(call);
        }
    }

    @PermissionCallback
    private void cameraPermsCallback(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            requestUsbPermission(call);
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", false);
            ret.put("message", "Android Camera & Microphone runtime permission denied");
            call.resolve(ret);
        }
    }

    private void requestUsbPermission(PluginCall call) {
        try {
            UsbDevice dev = getController().findUvcDevice();
            if (dev == null) {
                JSObject ret = new JSObject();
                ret.put("granted", false);
                ret.put("message", "Android Camera permission granted, but no USB UVC device detected in OTG");
                call.resolve(ret);
                return;
            }

            getController().requestPermission(dev, getActivity(), granted -> {
                JSObject ret = new JSObject();
                ret.put("granted", granted);
                ret.put("deviceName", dev.getProductName() != null ? dev.getProductName() : dev.getDeviceName());
                call.resolve(ret);
            });
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void startPreview(PluginCall call) {
        this.activeStartCall = call;
        this.targetW = call.getInt("targetWidth", 1280);
        this.targetH = call.getInt("targetHeight", 720);
        this.preferredFormat = call.getString("format", "MJPG");
        this.mirror = call.getBoolean("mirror", false);

        JSObject b = call.getObject("bounds");
        if (b != null) {
            this.cssBoundsX = b.optDouble("x", 0.0);
            this.cssBoundsY = b.optDouble("y", 0.0);
            this.cssBoundsW = b.optDouble("width", 0.0);
            this.cssBoundsH = b.optDouble("height", 0.0);
        }

        getActivity().runOnUiThread(() -> {
            try {
                UsbDevice dev = getController().findUvcDevice();
                if (dev == null) {
                    call.reject("No USB Camera connected");
                    return;
                }

                if (!getController().hasPermission(dev)) {
                    getController().requestPermission(dev, getActivity(), granted -> {
                        if (granted) {
                            getActivity().runOnUiThread(() -> setupSurfaceAndStart(dev));
                        } else {
                            call.reject("Permission denied");
                        }
                    });
                    return;
                }

                setupSurfaceAndStart(dev);
            } catch (Exception e) {
                call.reject("Start error: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void updateBounds(PluginCall call) {
        JSObject b = call.getObject("bounds");
        if (b != null) {
            this.cssBoundsX = b.optDouble("x", 0.0);
            this.cssBoundsY = b.optDouble("y", 0.0);
            this.cssBoundsW = b.optDouble("width", 0.0);
            this.cssBoundsH = b.optDouble("height", 0.0);
            getActivity().runOnUiThread(this::applySurfaceBounds);
        }
        call.resolve();
    }

    private void applySurfaceBounds() {
        if (previewSurfaceView == null || getActivity() == null) return;
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int pxW = (int) Math.round(cssBoundsW * dm.density);
        int pxH = (int) Math.round(cssBoundsH * dm.density);
        int pxX = (int) Math.round(cssBoundsX * dm.density);
        int pxY = (int) Math.round(cssBoundsY * dm.density);

        ViewGroup rootView = (ViewGroup) getActivity().getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null && getBridge().getWebView() != null) {
            int[] webViewLoc = new int[2];
            getBridge().getWebView().getLocationOnScreen(webViewLoc);
            int[] rootLoc = new int[2];
            rootView.getLocationOnScreen(rootLoc);
            pxX += (webViewLoc[0] - rootLoc[0]);
            pxY += (webViewLoc[1] - rootLoc[1]);
        }

        if (pxW > 0 && pxH > 0) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pxW, pxH);
            lp.leftMargin = pxX;
            lp.topMargin = pxY;
            previewSurfaceView.setLayoutParams(lp);
        }
    }

    private void setupSurfaceAndStart(UsbDevice dev) {
        if (surfaceContainer == null) {
            surfaceContainer = new FrameLayout(getActivity());
            surfaceContainer.setBackgroundColor(Color.TRANSPARENT);

            previewSurfaceView = new SurfaceView(getActivity());
            previewSurfaceView.setZOrderMediaOverlay(false);
            previewSurfaceView.setZOrderOnTop(false);

            surfaceHolder = previewSurfaceView.getHolder();
            surfaceHolder.addCallback(this);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);
            surfaceHolder.setFixedSize(targetW, targetH);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            );
            surfaceContainer.addView(previewSurfaceView, lp);

            ViewGroup rootView = (ViewGroup) getActivity().getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.addView(surfaceContainer, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ));
                try {
                    getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
                } catch (Exception ignored) {}
            }
        } else {
            surfaceContainer.setVisibility(View.VISIBLE);
        }

        applySurfaceBounds();

        if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
            boolean ok = getController().startStream(dev, surfaceHolder.getSurface(), targetW, targetH, preferredFormat, mirror, (msg, type) -> {
                getActivity().runOnUiThread(() -> emitLog(msg, type));
            });

            if (activeStartCall != null) {
                JSObject ret = new JSObject();
                ret.put("success", ok);
                ret.put("handleId", getController().getHandleId());
                ret.put("error", getController().getLastError());
                activeStartCall.resolve(ret);
                activeStartCall = null;
            }
        }
    }

    @PluginMethod
    public void stopPreview(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                if (controller != null) {
                    controller.stopStream();
                }
                if (surfaceContainer != null) {
                    surfaceContainer.setVisibility(View.GONE);
                }
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void takePhoto(PluginCall call) {
        boolean m = call.getBoolean("mirror", this.mirror);
        getController().capturePhoto(m, new UvcController.CaptureCallback() {
            @Override
            public void onSuccess(String dataUrl, int width, int height) {
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("dataUrl", dataUrl);
                ret.put("width", width);
                ret.put("height", height);
                call.resolve(ret);
            }

            @Override
            public void onError(String message) {
                call.reject(message);
            }
        });
    }

    @PluginMethod
    public void getStats(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("streaming", controller != null && controller.isStreaming());
        ret.put("fps", controller != null ? controller.calculateFps() : 0.0f);
        ret.put("urbs", controller != null ? controller.getUrbsCount() : 0);
        call.resolve(ret);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        this.surfaceHolder = holder;
        holder.setFixedSize(targetW, targetH);
        UsbDevice dev = getController().findUvcDevice();
        if (dev != null && getController().hasPermission(dev)) {
            boolean ok = getController().startStream(dev, holder.getSurface(), targetW, targetH, preferredFormat, mirror, (msg, type) -> {
                getActivity().runOnUiThread(() -> emitLog(msg, type));
            });

            if (activeStartCall != null) {
                JSObject ret = new JSObject();
                ret.put("success", ok);
                ret.put("handleId", getController().getHandleId());
                ret.put("error", getController().getLastError());
                activeStartCall.resolve(ret);
                activeStartCall = null;
            }
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        this.surfaceHolder = holder;
        if (controller != null) {
            controller.setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (controller != null) {
            controller.setSurface(null);
        }
        this.surfaceHolder = null;
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (controller != null) controller.stopStream();
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        if (controller != null) controller.stopStream();
    }
}