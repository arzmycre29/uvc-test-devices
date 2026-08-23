package com.uvctester.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import com.getcapacitor.BridgeActivity;
import com.uvctester.app.uvc.UvcTesterPlugin;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends BridgeActivity {
    private static final String TAG = "UvcTesterMain";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    throwable.printStackTrace(pw);
                    String stackTrace = sw.toString();
                    Log.e(TAG, "FATAL UNCAUGHT CRASH:\n" + stackTrace);

                    SharedPreferences prefs = getSharedPreferences("UvcCrashLogs", Context.MODE_PRIVATE);
                    prefs.edit().putString("last_crash", stackTrace).apply();
                } catch (Exception ignored) {}

                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            }
        });

        try {
            registerPlugin(UvcTesterPlugin.class);
        } catch (Throwable t) {
            Log.e(TAG, "Error registering UvcTesterPlugin", t);
        }

        super.onCreate(savedInstanceState);
    }
}