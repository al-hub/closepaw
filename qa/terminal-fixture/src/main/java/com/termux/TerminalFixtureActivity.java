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
        terminal.postDelayed(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT), 300);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); terminal.password = intent.getBooleanExtra("password", false);
        terminal.reset(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).restartInput(terminal);
    }
}
