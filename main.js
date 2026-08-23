import { registerPlugin } from "@capacitor/core";

const UvcTester = registerPlugin("UvcTester");

// DOM Elements
const btnTestNative = document.getElementById("btn-test-native");
const btnRequestUsb = document.getElementById("btn-request-usb");
const btnStartStream = document.getElementById("btn-start-stream");
const btnTakePhoto = document.getElementById("btn-take-photo");
const btnStopStream = document.getElementById("btn-stop-stream");
const btnClearLog = document.getElementById("btn-clear-log");
const btnCopyLog = document.getElementById("btn-copy-log");

const resolutionSelect = document.getElementById("resolution-select");
const formatSelect = document.getElementById("format-select");
const mirrorCheckbox = document.getElementById("mirror-checkbox");

const deviceBadge = document.getElementById("device-status-badge");
const deviceNameText = document.getElementById("device-name-text");
const liveIndicator = document.getElementById("live-indicator");
const fpsIndicator = document.getElementById("fps-indicator");
const consoleLogs = document.getElementById("console-logs");
const cameraViewport = document.getElementById("camera-viewport");
const viewportPlaceholder = document.getElementById("viewport-placeholder");
const snapshotImg = document.getElementById("snapshot-img");
const snapshotBox = document.getElementById("snapshot-box");
const photoDimensions = document.getElementById("photo-dimensions");

let statsInterval = null;
let isStreaming = false;

function log(msg, type = "info") {
  const time = new Date().toTimeString().split(" ")[0];
  const div = document.createElement("div");
  div.className = `log-entry log-${type}`;
  div.textContent = `[${time}] [${type.toUpperCase()}] ${msg}`;
  consoleLogs.appendChild(div);
  consoleLogs.scrollTop = consoleLogs.scrollHeight;
}

function getViewportBounds() {
  const rect = cameraViewport.getBoundingClientRect();
  return {
    x: rect.left,
    y: rect.top,
    width: rect.width,
    height: rect.height
  };
}

async function syncBounds() {
  if (isStreaming) {
    try {
      await UvcTester.updateBounds({ bounds: getViewportBounds() });
    } catch (_) {}
  }
}

window.addEventListener("resize", syncBounds);
window.addEventListener("orientationchange", () => setTimeout(syncBounds, 250));

btnClearLog.addEventListener("click", () => {
  consoleLogs.innerHTML = "";
});

btnCopyLog.addEventListener("click", async () => {
  const device = deviceNameText.textContent || "None";
  const logEntries = Array.from(consoleLogs.querySelectorAll(".log-entry"))
    .map((el) => el.textContent)
    .join("\n");

  const fullReport = `=== UVC TESTER DIAGNOSTIC REPORT ===
Timestamp: ${new Date().toISOString()}
User Agent: ${navigator.userAgent}
Device Status: ${device}
Selected Config: ${resolutionSelect.value} (${formatSelect.value}), Mirror: ${mirrorCheckbox.checked}
--- EVENT LOGS (${consoleLogs.children.length} entries) ---
${logEntries || "(No events logged yet)"}
====================================`;

  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(fullReport);
    } else {
      const textarea = document.createElement("textarea");
      textarea.value = fullReport;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand("copy");
      document.body.removeChild(textarea);
    }
    btnCopyLog.textContent = "✅ Copied!";
    btnCopyLog.classList.add("copied");
    setTimeout(() => {
      btnCopyLog.textContent = "📋 Copy Log";
      btnCopyLog.classList.remove("copied");
    }, 2500);
  } catch (err) {
    log(`Copy failed: ${err.message || err}`, "error");
  }
});

btnTestNative.addEventListener("click", async () => {
  log("Testing JNI Native Engine (libusbfs.so)...", "info");
  try {
    const res = await UvcTester.testNative();
    if (res.success) {
      log(`JNI Engine Loaded OK! ABI: ${res.abi}`, "success");
    } else {
      log(`JNI Engine Load Failed: ${res.error || "Unknown"}`, "error");
    }
  } catch (err) {
    log(`Test Native Exception: ${err.message || err}`, "error");
  }
});

async function checkDeviceState() {
  try {
    const res = await UvcTester.checkDevice();
    log(`Device Check: connected=${res.connected}, perms=${res.permission}, runtimePerms=${res.runtimePermissions}, name="${res.deviceName}"`, res.connected ? "info" : "warn");
    
    if (res.connected) {
      deviceBadge.className = "status-pill connected";
      deviceNameText.textContent = res.deviceName || `USB Cam (VID:${res.vendorId} PID:${res.productId})`;
      btnStartStream.disabled = !res.permission;
    } else {
      deviceBadge.className = "status-pill disconnected";
      deviceNameText.textContent = "No USB Device";
      btnStartStream.disabled = true;
    }
  } catch (err) {
    log(`Check device error: ${err.message || err}`, "error");
  }
}

btnRequestUsb.addEventListener("click", async () => {
  log("Requesting Camera, Audio & USB OTG Permissions...", "info");
  try {
    const res = await UvcTester.requestPermission();
    if (res.granted) {
      log(`All Permissions GRANTED for "${res.deviceName || "USB Camera"}"!`, "success");
      btnStartStream.disabled = false;
      await checkDeviceState();
    } else {
      log(`Permission Result: ${res.message || "Denied or not plugged in"}`, "error");
    }
  } catch (err) {
    log(`Permission exception: ${err.message || err}`, "error");
  }
});

btnStartStream.addEventListener("click", async () => {
  const resVal = resolutionSelect.value.split("x");
  const targetWidth = parseInt(resVal[0], 10);
  const targetHeight = parseInt(resVal[1], 10);
  const format = formatSelect.value;
  const mirror = mirrorCheckbox.checked;
  const bounds = getViewportBounds();

  log(`Initiating stream: ${targetWidth}x${targetHeight} (${format}), mirror=${mirror}, bounds=[${Math.round(bounds.width)}x${Math.round(bounds.height)} at (${Math.round(bounds.x)},${Math.round(bounds.y)})]...`, "info");
  
  try {
    const res = await UvcTester.startPreview({
      targetWidth,
      targetHeight,
      format,
      mirror,
      bounds
    });

    if (res.success) {
      isStreaming = true;
      log(`STREAM STARTED! Native Handler ID=${res.handleId}`, "success");
      viewportPlaceholder.style.visibility = "hidden";
      liveIndicator.style.display = "flex";
      fpsIndicator.style.display = "block";
      
      btnStartStream.disabled = true;
      btnTakePhoto.disabled = false;
      btnStopStream.disabled = false;

      if (statsInterval) clearInterval(statsInterval);
      statsInterval = setInterval(async () => {
        try {
          const stats = await UvcTester.getStats();
          if (stats && stats.streaming) {
            fpsIndicator.textContent = `${(stats.fps || 30.0).toFixed(1)} FPS (${stats.urbs || 0} URBs)`;
          }
        } catch (_) {}
      }, 1000);
    } else {
      isStreaming = false;
      log(`Failed to start preview: ${res.error || "Handshake rejected"}`, "error");
    }
  } catch (err) {
    isStreaming = false;
    log(`Start stream exception: ${err.message || err}`, "error");
  }
});

btnTakePhoto.addEventListener("click", async () => {
  log("Triggering hardware snapshot URB message...", "info");
  try {
    const mirror = mirrorCheckbox.checked;
    const res = await UvcTester.takePhoto({ mirror });
    
    if (res.success && res.dataUrl) {
      log(`SNAPSHOT CAPTURED: ${res.width}x${res.height}, Size: ${(res.dataUrl.length / 1024).toFixed(1)} KB`, "success");
      snapshotImg.src = res.dataUrl;
      snapshotImg.style.display = "block";
      const emptyText = snapshotBox.querySelector(".empty-state");
      if (emptyText) emptyText.style.display = "none";
      photoDimensions.textContent = `${res.width}x${res.height}`;
    } else {
      log(`Take photo failed: ${res.error || "Empty buffer"}`, "error");
    }
  } catch (err) {
    log(`Take photo exception: ${err.message || err}`, "error");
  }
});

btnStopStream.addEventListener("click", async () => {
  log("Stopping stream & releasing hardware...", "info");
  try {
    isStreaming = false;
    if (statsInterval) clearInterval(statsInterval);
    await UvcTester.stopPreview();
    
    log("Stream stopped successfully.", "info");
    viewportPlaceholder.style.visibility = "visible";
    liveIndicator.style.display = "none";
    fpsIndicator.style.display = "none";
    
    btnStartStream.disabled = false;
    btnTakePhoto.disabled = true;
    btnStopStream.disabled = true;
  } catch (err) {
    log(`Stop stream exception: ${err.message || err}`, "error");
  }
});

// Setup listeners & check device on page load
window.addEventListener("DOMContentLoaded", async () => {
  log("UVC Kernel Tester UI ready. Tap 'Test JNI Engine' or 'Request USB'.", "info");
  try {
    await UvcTester.addListener("uvcLog", (data) => {
      if (data && data.message) {
        log(data.message, data.type || "info");
      }
    });
  } catch (e) {
    console.error("addListener error", e);
  }
  
  try {
    checkDeviceState();
  } catch (e) {
    log(`Initial device check error: ${e.message}`, "warn");
  }
});