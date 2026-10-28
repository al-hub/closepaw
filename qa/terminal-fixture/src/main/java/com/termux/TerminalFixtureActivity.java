package com.termux;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import com.termux.view.TerminalView;
/** Installed ONLY in the throwaway emulator, never a production dependency. */
public final class TerminalFixtureActivity extends Activity {
    private TerminalView terminal;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); terminal = new TerminalView(this); terminal.setId(R.id.terminal_view);
        setContentView(terminal); terminal.requestFocus();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && terminal != null) {
            // Android may grant the window later than an arbitrary postDelayed.
            // Bind only when actually served; never retry a text/submit action.
            terminal.requestFocus();
            // Let ViewRoot dispatch window focus to InputMethodManager first.
            // Calling inline can precede native served-view readiness.
            terminal.post(() -> {
                if (!terminal.hasWindowFocus() || !terminal.isFocused()) return;
                InputMethodManager manager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                manager.restartInput(terminal);
                manager.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); terminal.password = intent.getBooleanExtra("password", false);
        terminal.reset(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).restartInput(terminal);
    }
}
