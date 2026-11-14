package dev.shruti.capture;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ShrutiShortcut")
public class ShrutiShortcutPlugin extends Plugin {
    static final String ACTION_RECORD = "dev.shruti.capture.RECORD";

    @Override
    protected void handleOnNewIntent(Intent intent) {
        if (ACTION_RECORD.equals(intent.getAction())) {
            notifyListeners("record", new JSObject(), true);
        }
    }
}
