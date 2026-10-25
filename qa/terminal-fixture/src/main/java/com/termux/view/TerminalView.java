package com.termux.view;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
/** Models documented Termux TYPE_NULL/InputConnection+contentDescription echo.
 * It does not run a shell and is not evidence of the real Termux application's behavior. */
public final class TerminalView extends View {
    public boolean password = false;
    private String echo = "~ $";
    private final Paint paint = new Paint();
    public TerminalView(Context context) {
        super(context); setFocusable(true); setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); paint.setTextSize(32); reset();
    }
    public void reset() { echo = password ? "Password:" : "~ $"; setContentDescription(echo); invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); }
    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_NULL;
        info.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN; info.fieldId = getId();
        return new BaseInputConnection(this, true) {
            @Override public boolean commitText(CharSequence text, int cursor) {
                super.commitText(text, cursor); Editable content = getEditable();
                echo += (echo.endsWith("$") ? " " : "") + content.toString(); content.clear();
                setContentDescription(echo); invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); return true;
            }
        };
    }
    @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); canvas.drawRGB(245,245,245); canvas.drawText(echo, 16, 80, paint); }
}
