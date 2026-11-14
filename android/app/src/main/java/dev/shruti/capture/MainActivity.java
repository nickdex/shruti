package dev.shruti.capture;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShrutiSpeechRecognitionPlugin.class);
        registerPlugin(ShrutiCommandTransportPlugin.class);
        registerPlugin(ShrutiShortcutPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
