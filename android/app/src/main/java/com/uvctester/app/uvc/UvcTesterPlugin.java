package com.uvctester.app.uvc;

import android.Manifest;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbDevice;
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

    @Override
    public void load() {
        super.load();
        this.controller = UvcController.getInstance(getContext());
    }

    @PluginMethod
    public void checkDevice(PluginCall call) {
        try {
            UsbDevice dev = controller.findUvcDevice();
            JSObject ret = new JSObject();
            boolean hasRuntimePerms = getPermissionState("camera") == PermissionState.GRANTED;
            ret.put("runtimePermissions", hasRuntimePerms);

            if (dev != null) {
                ret.put("connected", true);
                ret.put("permission", controller.hasPermission(dev) && hasRuntimePerms);
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
            UsbDevice dev = controller.findUvcDevice();
            if (dev == null) {
                JSObject ret = new JSObject();
                ret.put("granted", false);
                ret.put("message", "Android Camera permission granted, but no USB UVC device detected in OTG");
                call.resolve(ret);
                return;
            }

            controller.requestPermission(dev, getActivity(), granted -> {
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

        getActivity().runOnUiThread(() -> {
            try {
                UsbDevice dev = controller.findUvcDevice();
                if (dev == null) {
                    call.reject("No USB Camera connected");
                    return;
                }

                if (!controller.hasPermission(dev)) {
                    controller.requestPermission(dev, getActivity(), granted -> {
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

    private void setupSurfaceAndStart(UsbDevice dev) {
        if (surfaceContainer == null) {
            surfaceContainer = new FrameLayout(getActivity());
            surfaceContainer.setBackgroundColor(Color.BLACK);

            previewSurfaceView = new SurfaceView(getActivity());
            surfaceHolder = previewSurfaceView.getHolder();
            surfaceHolder.addCallback(this);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);

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

        if (surfaceHolder != null && surfaceHolder.getSurface().isValid()) {
            boolean ok = controller.startStream(dev, surfaceHolder.getSurface(), targetW, targetH, preferredFormat, mirror);
            if (activeStartCall != null) {
                JSObject ret = new JSObject();
                ret.put("success", ok);
                ret.put("handleId", controller.getHandleId());
                activeStartCall.resolve(ret);
                activeStartCall = null;
            }
        }
    }

    @PluginMethod
    public void stopPreview(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                controller.stopStream();
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
        controller.capturePhoto(m, new UvcController.CaptureCallback() {
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
        ret.put("streaming", controller.isStreaming());
        ret.put("fps", controller.calculateFps());
        ret.put("urbs", controller.getUrbsCount());
        call.resolve(ret);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        this.surfaceHolder = holder;
        UsbDevice dev = controller.findUvcDevice();
        if (dev != null && controller.hasPermission(dev)) {
            boolean ok = controller.startStream(dev, holder.getSurface(), targetW, targetH, preferredFormat, mirror);
            if (activeStartCall != null) {
                JSObject ret = new JSObject();
                ret.put("success", ok);
                ret.put("handleId", controller.getHandleId());
                activeStartCall.resolve(ret);
                activeStartCall = null;
            }
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        this.surfaceHolder = holder;
        controller.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        controller.setSurface(null);
        this.surfaceHolder = null;
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        controller.stopStream();
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        controller.stopStream();
    }
}