package com.termux;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/** Plain disposable fixture for the OS secure-window check in emulator CI. */
public final class ObservationWindowFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean protectedWindow = getIntent().getBooleanExtra("protected_window", false);
        if (protectedWindow) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        TextView marker = new TextView(this);
        marker.setGravity(Gravity.CENTER);
        marker.setText(protectedWindow ? "Protected observation fixture" : "Public observation fixture");
        setContentView(marker);
    }
}
