package com.uvctester.app.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MediaSaver {
    private static final String TAG = "MediaSaver";

    public static class SavedMediaResult {
        public boolean success;
        public Uri uri;
        public String filePath;
        public String fileName;
        public String error;
    }

    public static String generateFileName(String prefix, String extension) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return prefix + "_" + timestamp + "." + extension;
    }

    public static SavedMediaResult savePhotoToDcim(Context context, byte[] jpegData) {
        SavedMediaResult res = new SavedMediaResult();
        String fileName = generateFileName("IMG", "jpg");
        res.fileName = fileName;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                ContentResolver resolver = context.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);

                if (uri != null) {
                    try (OutputStream out = resolver.openOutputStream(uri)) {
                        if (out != null) {
                            out.write(jpegData);
                            out.flush();
                        }
                    }

                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);

                    res.success = true;
                    res.uri = uri;
                    res.filePath = Environment.DIRECTORY_DCIM + "/Camera/" + fileName;
                    Log.d(TAG, "Photo saved to MediaStore DCIM/Camera: " + uri);
                } else {
                    res.error = "MediaStore insert returned null URI";
                }
            } else {
                File dcimDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
                if (!dcimDir.exists()) dcimDir.mkdirs();
                File photoFile = new File(dcimDir, fileName);

                try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                    fos.write(jpegData);
                    fos.flush();
                }

                MediaScannerConnection.scanFile(context, new String[]{photoFile.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
                res.success = true;
                res.filePath = photoFile.getAbsolutePath();
                res.uri = Uri.fromFile(photoFile);
                Log.d(TAG, "Photo saved to legacy DCIM/Camera: " + photoFile.getAbsolutePath());
            }
        } catch (Exception e) {
            res.success = false;
            res.error = "Failed to save photo: " + e.getMessage();
            Log.e(TAG, "Error saving photo", e);
        }

        return res;
    }

    public static class VideoTarget {
        public Uri uri;
        public ParcelFileDescriptor pfd;
        public File legacyFile;
        public String fileName;
        public String relativePath;
    }

    public static VideoTarget createVideoTarget(Context context) throws Exception {
        VideoTarget target = new VideoTarget();
        String fileName = generateFileName("VID", "mp4");
        target.fileName = fileName;
        target.relativePath = Environment.DIRECTORY_DCIM + "/Camera/" + fileName;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new Exception("Failed to insert video into MediaStore");

            target.uri = uri;
            target.pfd = resolver.openFileDescriptor(uri, "rw");
        } else {
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
            if (!dcimDir.exists()) dcimDir.mkdirs();
            File videoFile = new File(dcimDir, fileName);
            target.legacyFile = videoFile;
            target.uri = Uri.fromFile(videoFile);
            target.pfd = ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE);
        }

        return target;
    }

    public static void finishVideoTarget(Context context, VideoTarget target) {
        if (target == null) return;
        try {
            if (target.pfd != null) {
                target.pfd.close();
                target.pfd = null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(target.uri, values, null, null);
                Log.d(TAG, "Video marked finished in MediaStore: " + target.uri);
            } else if (target.legacyFile != null) {
                MediaScannerConnection.scanFile(context, new String[]{target.legacyFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                Log.d(TAG, "Legacy video scanned into MediaStore: " + target.legacyFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finishing video target", e);
        }
    }
}