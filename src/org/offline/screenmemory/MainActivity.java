package org.offline.screenmemory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;

/** Translucent launcher; opaque alignment canvas only after an explicit edit action. */
public final class MainActivity extends Activity {
    private MemoryApp app;
    private Memory draft;
    private EditText name, ratioW, ratioH;
    private boolean readOnly, preparing, rendering, renderPending;
    private String rawName, rawW, rawH;
    private int nameCursor, widthCursor, heightCursor, nameEnd, widthEnd, heightEnd;
    private int rowHeight, panelText;
    private final UiPolicy.Focus focusPolicy = new UiPolicy.Focus();
    private void scheduleRender() {
        if (renderPending) { return; }
        renderPending = true;
        handler.post(() -> { renderPending = false; render(); });
    }
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed = () -> {
        if (draft != null && !readOnly && !busy() && known()) {
            Operation op = app.runtime.operation;
            draft = draft.offsets(op.zoom(), op.x(), op.y());
            preparing = false;
        }
        scheduleRender();
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app = (MemoryApp) getApplication();
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        if (saved != null && saved.containsKey("draft")) {
            try {
                draft = MemoryCodec.decode(saved.getByteArray("draft")).stream()
                        .filter(m -> !Memory.NATIVE_ID.equals(m.id)).findFirst().orElse(null);
                if (saved.getBoolean("native")) { draft = Memory.NATIVE; }
                readOnly = saved.getBoolean("native"); preparing = saved.getBoolean("preparing");
            } catch (java.io.IOException | RuntimeException ex) { draft = null; }
        }
        if (saved != null) { rawName = saved.getString("rawName"); rawW = saved.getString("rawW"); rawH = saved.getString("rawH"); }
        render();
    }
    @Override protected void onResume() { super.onResume(); app.runtime.listener = changed; changed.run(); }
    @Override protected void onPause() { capture(); app.runtime.listener = null; super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        capture();
        if (draft != null) {
            try { out.putByteArray("draft", MemoryCodec.encode(java.util.Collections.singletonList(draft))); }
            catch (java.io.IOException ex) { app.runtime.status = "Draft could not be retained"; }
            out.putBoolean("native", readOnly); out.putBoolean("preparing", preparing);
        }
        out.putString("rawName", rawName); out.putString("rawW", rawW); out.putString("rawH", rawH);
        super.onSaveInstanceState(out);
    }
    private boolean busy() { return app.runtime.launching || (app.runtime.operation != null && app.runtime.operation.busy()); }
    private boolean known() { return app.runtime.operation != null && app.runtime.operation.known(); }
    private void info(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show(); }
    private void reference(Runnable after) {
        if (busy()) { return; }
        new AlertDialog.Builder(this).setTitle("Establish Native reference")
                .setMessage("Manually align the image to intended Native first. Outside-remote changes invalidate tracking. This confirmation sends no commands and sets tracked offsets to zero. Step sizes, scale, coordinates and hardware completion are unmeasured.")
                .setNegativeButton("Cancel", null).setPositiveButton("Current image is Native", (dialog, which) -> {
                    if (busy()) { return; }
                    app.runtime.acceptReference(); after.run();
                }).show();
    }
    private void request(Memory target, boolean dismiss) { request(target, dismiss, false); }
    private void request(Memory target, boolean dismiss, boolean preview) {
        if (busy() || app.error != null) { return; }
        Runnable action = () -> {
            if (busy()) { return; }
            boolean establishingNative = app.runtime.reference && Memory.NATIVE_ID.equals(target.id);
            capture(); app.runtime.preview = preview; app.runtime.pending = target; app.runtime.launching = true;
            try {
                startForegroundService(new Intent(this, ControlService.class).setAction("org.offline.screenmemory.APPLY"));
                if (dismiss && !establishingNative) { finish(); } else { render(); }
            } catch (RuntimeException ex) {
                app.runtime.launchFailed(); render();
            }
        };
        if (!known() && !app.runtime.reference) { reference(action); } else { action.run(); }
    }
    private void edit(Memory memory) {
        if (busy()) { return; }
        rawName = null; rawW = null; rawH = null; focusPolicy.clear();
        if (memory == null) {
            draft = new Memory(UUID.randomUUID().toString(), "16:9", 16, 9,
                    known() ? app.runtime.operation.zoom() : 0, known() ? app.runtime.operation.x() : 0, known() ? app.runtime.operation.y() : 0);
            rawName = ""; readOnly = false; preparing = false; render(); return;
        }
        if (Memory.NATIVE_ID.equals(memory.id)) { draft = Memory.NATIVE; readOnly = true; render(); return; }
        Runnable open = () -> { draft = memory; readOnly = false; preparing = true; request(memory, false); };
        // Keep the overview until the explicit reference dialog is accepted.
        if (!known()) { reference(open); } else { open.run(); }
    }
    private void capture() {
        if (draft == null || readOnly || name == null) { return; }
        rawName = name.getText().toString(); rawW = ratioW.getText().toString(); rawH = ratioH.getText().toString();
        nameCursor = name.getSelectionStart(); widthCursor = ratioW.getSelectionStart(); heightCursor = ratioH.getSelectionStart();
        nameEnd = name.getSelectionEnd(); widthEnd = ratioW.getSelectionEnd(); heightEnd = ratioH.getSelectionEnd();
        try { draft = fields(); }
        catch (IllegalArgumentException ex) { /* Keep invalid raw text until explicit validation. */ }
    }
    private Memory fields() {
        double w = Double.parseDouble(ratioW.getText().toString()), h = Double.parseDouble(ratioH.getText().toString());
        return new Memory(draft.id, UiPolicy.resolveName(name.getText().toString(), w, h), w, h, draft.zoom, draft.x, draft.y);
    }
    private void render() {
        if (isFinishing()) { return; }
        rendering = true;
        capture(); name = null; ratioW = null; ratioH = null;
        if (app.error != null) { TextView error = new TextView(this); error.setText(app.error); error.setTextColor(Color.WHITE); error.setBackgroundColor(Color.BLACK); setContentView(error); rendering = false; return; }
        if (draft == null) { overview(); } else { config(); }
        String focus = focusPolicy.restore(busy());
        if (focus != null) { View restored = getWindow().getDecorView().findViewWithTag(focus); if (restored != null && restored.isEnabled()) { restored.requestFocus(); } }
        rendering = false;
    }
    private void overview() {
        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(3); grid.setPadding(24, 24, 24, 24);
        for (Memory m : app.catalog.all()) {
            Button tile = button(m.name, () -> request(m, true), () -> edit(m)); tile.setTag(m.id);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = 260; lp.height = 120; lp.setMargins(12, 12, 12, 12);
            tileStyle(tile); grid.addView(tile, lp); tile.setEnabled(!busy());
        }
        Button plus = button("+", () -> edit(null), null); plus.setEnabled(!busy()); plus.setTag("plus");
        tileStyle(plus); plus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, plus.getTextSize() * 4); plus.setPadding(0, 0, 0, 0); plus.setIncludeFontPadding(false);
        plus.setAutoSizeTextTypeUniformWithConfiguration(1, Math.max(1, (int) plus.getTextSize()), 1, android.util.TypedValue.COMPLEX_UNIT_PX);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = 260; lp.height = 120; lp.setMargins(12, 12, 12, 12); grid.addView(plus, lp);
        LinearLayout overview = new LinearLayout(this); overview.setOrientation(LinearLayout.VERTICAL);
        TextView latest = new TextView(this); latest.setTextColor(Color.WHITE);
        latest.setText(app.runtime.displayStatus()); latest.setBackgroundColor(0x80000000);
        overview.addView(latest); overview.addView(grid);
        scroll.addView(overview); setContentView(scroll);
    }
    private void config() {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        root.addView(new Pattern(), new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int[] dimensions = UiPolicy.panel(metrics.widthPixels, metrics.heightPixels, metrics.scaledDensity);
        rowHeight = dimensions[1]; panelText = dimensions[2];
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(0, 0, 0, 0); panel.setBackgroundColor(0xdd202020);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText);
        status.setText(draft.name + " · z=" + draft.zoom + " x=" + draft.x + " y=" + draft.y + " steps\n"
                + app.runtime.displayStatus() + "\nAspect is an alignment guide only. Software limit: 100 offset / 120 operation steps, not hardware bounds."); panel.addView(status, new LinearLayout.LayoutParams(-1, rowHeight * 2));
        if (!readOnly) {
            LinearLayout fields = new LinearLayout(this);
            name = input(rawName == null ? draft.name : rawName, "Name", false); ratioW = input(rawW == null ? Double.toString(draft.width) : rawW, "Aspect width", true); ratioH = input(rawH == null ? Double.toString(draft.height) : rawH, "Aspect height", true);
            restoreSelection(name, nameCursor, nameEnd); restoreSelection(ratioW, widthCursor, widthEnd); restoreSelection(ratioH, heightCursor, heightEnd);
            labeled(fields, name, "Name", 2); labeled(fields, ratioW, "W", 1); labeled(fields, ratioH, "H", 1); panel.addView(fields, new LinearLayout.LayoutParams(-1, rowHeight));
            LinearLayout row = new LinearLayout(this);
            add(row, "Guide", () -> { try { draft = fields(); render(); } catch (IllegalArgumentException ex) { info(ex.getMessage()); } });
            add(row, "Zoom +", () -> step(1, 0, 0)); add(row, "Zoom −", () -> step(-1, 0, 0));
            add(row, "Left", () -> step(0, -1, 0)); add(row, "Right", () -> step(0, 1, 0)); add(row, "Up", () -> step(0, 0, -1)); add(row, "Down", () -> step(0, 0, 1)); panel.addView(row);
            LinearLayout actions = new LinearLayout(this);
            add(actions, "Save", () -> {
                if (!known() || preparing) { info("Establish Native and use live controls before saving offsets."); return; }
                try { draft = fields(); app.catalog.save(draft); draft = null; render(); }
                catch (RuntimeException ex) { info("Save failed: " + ex.getMessage()); }
            });
            add(actions, "Reset", () -> { capture(); request(Memory.NATIVE, false); });
            add(actions, "Delete", () -> new AlertDialog.Builder(this).setMessage("Delete this memory? No projector movement.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> {
                        if (busy()) { return; }
                        try { app.catalog.delete(draft.id); draft = null; render(); } catch (RuntimeException ex) { info(ex.getMessage()); }
                    }).show()); panel.addView(actions);
        }
        LinearLayout navigation = new LinearLayout(this);
        add(navigation, "Current image is Native", () -> reference(() -> {
            if (!readOnly) { draft = draft.offsets(0, 0, 0); }
            request(Memory.NATIVE, false);
        }));
        Button back = button("Back", this::back, null); navigation.addView(back, new LinearLayout.LayoutParams(0, rowHeight, 1)); panel.addView(navigation);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dimensions[0], -2); lp.gravity = android.view.Gravity.CENTER; root.addView(panel, lp); setContentView(root);
    }
    private void restoreSelection(EditText field, int start, int end) {
        field.setSelection(Math.max(0, Math.min(start, field.length())), Math.max(0, Math.min(end, field.length())));
    }
    private void labeled(LinearLayout row, EditText field, String label, int weight) {
        LinearLayout group = new LinearLayout(this);
        TextView title = new TextView(this); title.setText(label); title.setTextColor(Color.WHITE);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText);
        group.addView(title); group.addView(field, new LinearLayout.LayoutParams(0, rowHeight, 1));
        row.addView(group, new LinearLayout.LayoutParams(0, rowHeight, weight));
    }
    private void tileStyle(Button button) {
        button.setBackgroundColor(0xbf000000);
        button.setOnFocusChangeListener((v, focused) -> {
            if (focused && !rendering) { focusPolicy.selected((String) v.getTag()); }
            v.setBackgroundColor(focused ? 0xbf707070 : 0xbf000000);
            v.setScaleX(focused ? 1.03f : 1); v.setScaleY(focused ? 1.03f : 1);
        });
    }
    private EditText input(String value, String label, boolean number) {
        EditText field = new EditText(this); field.setText(value); field.setTextColor(Color.WHITE); field.setHint(label); field.setContentDescription(label); field.setTag(label); field.setSingleLine();
        field.setInputType(number ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : android.text.InputType.TYPE_CLASS_TEXT);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText); field.setPadding(4, 0, 4, 0);
        field.setOnFocusChangeListener((v, focused) -> { if (focused && !rendering) { focusPolicy.selected(label); } });
        field.setEnabled(!busy()); return field;
    }
    private void add(LinearLayout row, String label, Runnable action) { Button b = button(label, () -> { if (!busy()) { action.run(); } }, null); b.setEnabled(!busy()); row.addView(b, new LinearLayout.LayoutParams(0, rowHeight, 1)); }
    private void step(int z, int x, int y) {
        if (!known()) { reference(() -> request(Memory.NATIVE, false)); return; }
        try {
            draft = fields(); Operation op = app.runtime.operation;
            if (z != 0 && !op.previewAllowed(z)) {
                app.runtime.status = "Digital zoom limit reached; Native reference retained (estimated). Use opposite zoom direction.";
                render(); return;
            }
            request(new Memory("preview", "Preview", draft.width, draft.height, op.zoom() + z, op.x() + x, op.y() + y), false, true);
        } catch (IllegalArgumentException ex) { info(ex.getMessage()); }
    }
    private Button button(String label, Runnable click, Runnable longer) {
        Button button = new RemoteButton(click, longer); button.setText(label); button.setTag(label); button.setTextColor(Color.WHITE); button.setAllCaps(false); button.setPadding(8, 8, 8, 8);
        button.setBackgroundColor(0xff303030);
        if (draft != null) { button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, panelText); button.setMinHeight(0); button.setMinimumHeight(0); }
        button.setOnFocusChangeListener((v, focused) -> { if (focused && !rendering) { focusPolicy.selected((String) v.getTag()); } v.setBackgroundColor(focused ? 0xff707070 : 0xff303030); v.setScaleX(focused ? 1.03f : 1); v.setScaleY(focused ? 1.03f : 1); });
        return button;
    }
    private final class RemoteButton extends Button {
        private final SelectGesture gesture = new SelectGesture();
        private final UiPolicy.Dispatch deferred = new UiPolicy.Dispatch();
        private final Runnable shortAction, longAction;
        private final Runnable hold = this::heldSelect;
        private boolean eligible(boolean keyboardInput) {
            return UiPolicy.canActivate(isEnabled(), isAttachedToWindow(), hasWindowFocus(), hasFocus(), keyboardInput);
        }
        private void cancelSelect() { deferred.cancel(); gesture.cancel(); handler.removeCallbacks(hold); }
        private void dispatch(Runnable action, boolean keyboardInput) {
            // Recheck the appropriate input path after dispatch; touch needs no keyboard focus.
            long ticket = deferred.ticket();
            handler.post(() -> { if (deferred.valid(ticket, eligible(keyboardInput))) { action.run(); } });
        }
        private void heldSelect() { if (longAction != null && gesture.hold(eligible(true))) { dispatch(longAction, true); } }
        RemoteButton(Runnable shortAction, Runnable longAction) {
            super(MainActivity.this); this.shortAction = shortAction; this.longAction = longAction;
            setOnClickListener(v -> { if (eligible(false)) { focusPolicy.selected((String) getTag()); dispatch(shortAction, false); } });
            if (longAction != null) { setOnLongClickListener(v -> { if (eligible(false)) { focusPolicy.selected((String) getTag()); dispatch(longAction, false); } return true; }); }
        }
        private boolean select(int code) { return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER; }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (!select(code)) { return super.onKeyDown(code, event); }
            if (gesture.press(eligible(true), event.getRepeatCount()) && longAction != null
                    && !handler.postDelayed(hold, 600)) { cancelSelect(); } return true;
        }
        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (!select(code)) { return super.onKeyUp(code, event); }
            handler.removeCallbacks(hold); boolean act = gesture.release(eligible(true), event.isCanceled());
            if (act) { focusPolicy.selected((String) getTag()); dispatch(shortAction, true); } return true;
        }
        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            if (!focused) { cancelSelect(); } super.onFocusChanged(focused, direction, previous);
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            if (!focused) { cancelSelect(); } super.onWindowFocusChanged(focused);
        }
        @Override protected void onDetachedFromWindow() { cancelSelect(); super.onDetachedFromWindow(); }
    }
    private final class Pattern extends View {
        private final Paint paint = new Paint();
        Pattern() { super(MainActivity.this); setFocusable(false); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawColor(Color.BLACK); paint.setColor(Color.WHITE);
            double[] nativeFit = Memory.fit(getWidth(), getHeight(), 16, 9);
            double[] r = Memory.fit(nativeFit[2], nativeFit[3], draft.width, draft.height);
            canvas.drawRect((float) (nativeFit[0] + r[0]), (float) (nativeFit[1] + r[1]),
                    (float) (nativeFit[0] + r[0] + r[2]), (float) (nativeFit[1] + r[1] + r[3]), paint);
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean navigation = event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN);
        boolean handled = super.dispatchKeyEvent(event);
        if (navigation && getCurrentFocus() != null) { focusPolicy.selected((String) getCurrentFocus().getTag()); }
        return handled;
    }
    private void back() { focusPolicy.clear(); rawName = null; rawW = null; rawH = null; draft = null; name = null; readOnly = false; preparing = false; render(); }
    @Override public void onBackPressed() { if (draft != null) { back(); } else { super.onBackPressed(); } }
}
