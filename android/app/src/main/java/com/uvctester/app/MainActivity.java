package com.uvctester.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.uvctester.app.uvc.UvcTesterPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UvcTesterPlugin.class);
        super.onCreate(savedInstanceState);
    }
}