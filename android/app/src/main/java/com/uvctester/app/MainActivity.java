package com.uvctester.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.uvctester.app.uvc.UvcTesterPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(UvcTesterPlugin.class);
    }
}