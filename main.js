import { registerPlugin } from "@capacitor/core";

const UvcTester = registerPlugin("UvcTester");

// DOM Elements
const btnRequestUsb = document.getElementById("btn-request-usb");
const btnStartStream = document.getElementById("btn-start-stream");
const btnTakePhoto = document.getElementById("btn-take-photo");
const btnStopStream = document.getElementById("btn-stop-stream");
const btnClearLog = document.getElementById("btn-clear-log");

const resolutionSelect = document.getElementById("resolution-select");
const formatSelect = document.getElementById("format-select");
const mirrorCheckbox = document.getElementById("mirror-checkbox");

const deviceBadge = document.getElementById("device-status-badge");
const deviceNameText = document.getElementById("device-name-text");
const liveIndicator = document.getElementById("live-indicator");
const fpsIndicator = document.getElementById("fps-indicator");
const consoleLogs = document.getElementById("console-logs");
const viewportPlaceholder = document.getElementById("viewport-placeholder");
const snapshotImg = document.getElementById("snapshot-img");
const snapshotBox = document.getElementById("snapshot-box");
const photoDimensions = document.getElementById("photo-dimensions");

let statsInterval = null;

function log(msg, type = "info") {
  const time = new Date().toTimeString().split(" ")[0];
  const div = document.createElement("div");
  div.className = `log-entry log-${type}`;
  div.textContent = `[${time}] ${msg}`;
  consoleLogs.appendChild(div);
  consoleLogs.scrollTop = consoleLogs.scrollHeight;
}

btnClearLog.addEventListener("click", () => {
  consoleLogs.innerHTML = "";
});

async function checkDeviceState() {
  try {
    const res = await UvcTester.checkDevice();
    log(`Check Device: connected=${res.connected}, permission=${res.permission}, name="${res.deviceName}"`, res.connected ? "info" : "warn");
    
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
  log("Requesting USB Permission from Android OS...", "info");
  try {
    const res = await UvcTester.requestPermission();
    if (res.granted) {
      log(`USB Permission GRANTED for "${res.deviceName}"!`, "success");
      btnStartStream.disabled = false;
      await checkDeviceState();
    } else {
      log(`USB Permission DENIED or No Device: ${res.message || ""}`, "error");
    }
  } catch (err) {
    log(`Request permission exception: ${err.message || err}`, "error");
  }
});

btnStartStream.addEventListener("click", async () => {
  const resVal = resolutionSelect.value.split("x");
  const targetWidth = parseInt(resVal[0], 10);
  const targetHeight = parseInt(resVal[1], 10);
  const format = formatSelect.value;
  const mirror = mirrorCheckbox.checked;

  log(`Starting preview: ${targetWidth}x${targetHeight} (${format}), mirror=${mirror}...`, "info");
  
  try {
    const res = await UvcTester.startPreview({
      targetWidth,
      targetHeight,
      format,
      mirror
    });

    if (res.success) {
      log(`Preview STREAM STARTED SUCCESSFULLY! Handle=${res.handleId}`, "success");
      viewportPlaceholder.style.display = "none";
      liveIndicator.style.display = "flex";
      fpsIndicator.style.display = "block";
      
      btnStartStream.disabled = true;
      btnTakePhoto.disabled = false;
      btnStopStream.disabled = false;

      // Start stats polling
      if (statsInterval) clearInterval(statsInterval);
      statsInterval = setInterval(async () => {
        try {
          const stats = await UvcTester.getStats();
          if (stats && stats.fps !== undefined) {
            fpsIndicator.textContent = `${stats.fps.toFixed(1)} FPS (${stats.urbs} URBs)`;
          }
        } catch (_) {}
      }, 1000);
    } else {
      log(`Failed to start preview: ${res.error || "Unknown"}`, "error");
    }
  } catch (err) {
    log(`Start stream exception: ${err.message || err}`, "error");
  }
});

btnTakePhoto.addEventListener("click", async () => {
  log("Triggering high-resolution hardware snapshot...", "info");
  try {
    const mirror = mirrorCheckbox.checked;
    const res = await UvcTester.takePhoto({ mirror });
    
    if (res.success && res.dataUrl) {
      log(`SNAPSHOT CAPTURED! Resolution: ${res.width}x${res.height}, Size: ${(res.dataUrl.length / 1024).toFixed(1)} KB`, "success");
      snapshotImg.src = res.dataUrl;
      snapshotImg.style.display = "block";
      const emptyText = snapshotBox.querySelector(".empty-text");
      if (emptyText) emptyText.style.display = "none";
      photoDimensions.textContent = `${res.width}x${res.height}`;
    } else {
      log(`Take photo failed: ${res.error || "Empty frame buffer"}`, "error");
    }
  } catch (err) {
    log(`Take photo exception: ${err.message || err}`, "error");
  }
});

btnStopStream.addEventListener("click", async () => {
  log("Stopping camera stream...", "info");
  try {
    if (statsInterval) clearInterval(statsInterval);
    await UvcTester.stopPreview();
    
    log("Stream stopped and hardware released.", "info");
    viewportPlaceholder.style.display = "flex";
    liveIndicator.style.display = "none";
    fpsIndicator.style.display = "none";
    
    btnStartStream.disabled = false;
    btnTakePhoto.disabled = true;
    btnStopStream.disabled = true;
  } catch (err) {
    log(`Stop stream exception: ${err.message || err}`, "error");
  }
});

// Auto check device on load
window.addEventListener("DOMContentLoaded", () => {
  log("UVC Kernel Tester loaded. Checking USB devices...", "info");
  checkDeviceState();
});
