package org.offline.screenmemory;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Bundle;
import org.offline.binddiagnostic.BindingSession;

/** One process-wide operation. No replay/resume; vendor registration exists only during work. */
public final class ControlService extends Service implements Operation.Port {
    private static final String STOP = "org.offline.screenmemory.STOP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MemoryApp app;
    private BindingSession binding;
    private ServiceConnection connection;
    private Messenger endpoint;
    private Messenger reply;
    private boolean alive;
    private boolean journalBroken;
    private final Runnable deadline = () -> { if (app != null) { app.runtime.operation.stop("Software deadline; not a hard real-time limit"); } };
    @Override public void onCreate() {
        super.onCreate(); app = (MemoryApp) getApplication(); alive = true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("control", "Screen Memory control", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification());
        // Reuse the same policy between graceful operations while this process is alive.
        if (app.runtime.operation == null) { app.runtime.operation = new Operation(this); }
        // The service remains alive after graceful work to keep this port and known tracking valid.
    }
    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ControlService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).setAction("org.offline.screenmemory.OPEN")
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "control").setContentTitle("Screen Memory")
                .setContentText(app.runtime.displayStatus())
                .setStyle(new Notification.BigTextStyle().bigText(app.runtime.displayStatus()))
                .setContentIntent(open).setSmallIcon(org.offline.screenmemory.R.drawable.ic_screen_memory).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, org.offline.screenmemory.R.drawable.ic_screen_memory), "Open", open).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop).build()).build();
    }
    private void publishStatus() {
        // Notification delivery is best effort and must never affect control or UI state.
        app.runtime.publish(() -> getSystemService(NotificationManager.class).notify(1, notification()));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || flags != 0) {
            app.runtime.pending = null; app.runtime.launching = false; app.runtime.reference = false;
            app.runtime.status = "Stopped; tracking UNKNOWN";
            app.runtime.operation.invalidate("Stop/null/replayed start"); publishStatus(); stopSelf(); return START_NOT_STICKY;
        }
        if (app.runtime.reference) {
            app.runtime.reference = false;
            if (!app.runtime.operation.confirmNative()) {
                app.runtime.pending = null; app.runtime.status = "Native confirmation failed; tracking UNKNOWN";
            }
        }
        Memory target = app.runtime.pending;
        app.runtime.pending = null; app.runtime.launching = false;
        if (target == null || app.error != null) { publishStatus(); return START_NOT_STICKY; }
        try {
            app.runtime.status = "Submitting memory; hardware completion unmeasured";
            boolean accepted = app.runtime.preview ? app.runtime.operation.preview(target) : app.runtime.operation.start(target);
            if (!accepted) { app.runtime.status = "Control blocked: busy or Native reference unknown"; publishStatus(); }
        } catch (RuntimeException ex) { app.runtime.status = ex.getMessage(); publishStatus(); }
        publishStatus();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void event(String record) { app.repository.event(record); }
    @Override public void bind(long token) {
        journalBroken = false;
        if (!handler.postDelayed(deadline, Operation.DEADLINE_MS)) { throw new IllegalStateException("Deadline scheduling failed"); }
        final ServiceConnection owned = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (connection != this || !alive || binding == null || binding.state() == BindingSession.State.CONNECTED) { return; }
                binding.connected(binder != null);
                if (journalBroken || binder == null || binding.state() != BindingSession.State.CONNECTED) { app.runtime.operation.stop("Missing vendor endpoint"); return; }
                endpoint = new Messenger(binder);
                reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                    if (connection == this) { app.runtime.operation.reply(message.what, message.arg1); }
                    return true;
                }));
                app.runtime.operation.connected(token);
            }
            @Override public void onServiceDisconnected(ComponentName name) { failed(this, "Vendor disconnected"); }
            @Override public void onBindingDied(ComponentName name) { failed(this, "Vendor binding died"); }
            @Override public void onNullBinding(ComponentName name) { failed(this, "Vendor null binding"); }
        };
        connection = owned;
        binding = new BindingSession(new BindingSession.Port() {
            @Override public boolean bind() {
                if (journalBroken) { throw new IllegalStateException("Registration journal failed"); }
                Intent intent = new Intent().setComponent(new ComponentName("com.vt.tv.laserprojectorservice", "com.vt.tv.laserprojectorservice.LaserProjectorService"));
                return bindService(intent, owned, 0);
            }
            @Override public void unbind() { unbindService(owned); }
            @Override public void log(String message) {
                // Logging failure must not interrupt BindingSession before its unbind path.
                try { event("registration " + message); } catch (RuntimeException ex) { journalBroken = true; }
            }
        });
        binding.visible(); binding.bindClicked();
        if (app.runtime.operation.busy() && (journalBroken || binding == null || binding.state() == BindingSession.State.IDLE || binding.state() == BindingSession.State.CLEANUP_FAILED)) {
            app.runtime.operation.stop("Vendor bind unavailable; no retry");
        }
    }
    private void failed(ServiceConnection owned, String reason) {
        if (connection == owned) { app.runtime.operation.stop(reason); }
    }
    @Override public void send(int what) {
        Messenger current = endpoint;
        if (current == null || !alive || journalBroken) { throw new IllegalStateException("No active endpoint"); }
        Message message = Message.obtain(null, what, 0, 0);
        Bundle payload = new Bundle(); payload.putBoolean("key_type", false); payload.putBoolean("is_first", false);
        message.setData(payload); message.replyTo = reply;
        try { current.send(message); }
        catch (android.os.RemoteException ex) { throw new IllegalStateException("Uncertain send", ex); }
    }
    @Override public void schedule(long token, long delayMillis) {
        if (!handler.postDelayed(() -> app.runtime.operation.tick(token), delayMillis)) { throw new IllegalStateException("Step scheduling failed"); }
    }
    @Override public void cleanup() {
        // Identity/endpoint/callback invalidation must precede framework unregistering.
        endpoint = null; reply = null; connection = null; handler.removeCallbacksAndMessages(null);
        BindingSession old = binding;
        if (old != null) {
            old.unbindClicked();
            if (old.state() == BindingSession.State.CLEANUP_FAILED) {
                app.error = "Vendor cleanup failed. Control blocked for this process; tracking UNKNOWN.";
                throw new IllegalStateException("Vendor unregister failed");
            }
            binding = null;
        }
        if (journalBroken) { throw new IllegalStateException("Registration journal failed"); }
    }
    @Override public void finished(boolean submitted, String reason) {
        app.runtime.status = reason; publishStatus();
        // Keep service/port for known tracking; no vendor registration remains after success.
    }
    @Override public void onDestroy() {
        alive = false;
        app.runtime.launching = false; app.runtime.pending = null; app.runtime.reference = false;
        app.runtime.status = "Own service stopped; tracking UNKNOWN";
        app.runtime.operation.invalidate("Own service destroyed");
        try { cleanup(); } catch (RuntimeException ex) { app.runtime.status = "Cleanup failed; tracking UNKNOWN"; }
        app.runtime.operation = null; publishStatus(); stopForeground(true); super.onDestroy();
    }
}
