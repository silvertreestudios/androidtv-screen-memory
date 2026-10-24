package org.offline.binddiagnostic;

public final class BindingSessionTest {
    private static int count;
    private static final class Fake implements BindingSession.Port {
        boolean accept = true;
        boolean denied;
        boolean unknownRegistration;
        boolean cleanupFailure;
        int binds;
        int unbinds;
        public boolean bind() { binds++; if (denied) { throw new SecurityException(); } return accept; }
        public void unbind() {
            unbinds++;
            if (unknownRegistration) { throw new IllegalArgumentException(); }
            if (cleanupFailure) { throw new SecurityException(); }
        }
        public void log(String message) { }
    }
    private static void check(boolean condition, String label) {
        if (!condition) { throw new AssertionError(label); }
        count++;
    }
    public static void main(String[] args) {
        Fake f = new Fake(); BindingSession s = new BindingSession(f);
        s.bindClicked(); check(f.binds == 0, "no background bind");
        s.visible(); check(f.binds == 0, "no launch/resume bind");
        s.bindClicked(); check(s.state() == BindingSession.State.WAITING, "accepted waiting");
        s.bindClicked(); check(f.binds == 1, "duplicate blocked");
        s.connected(true); check(s.state() == BindingSession.State.CONNECTED, "presence recorded");
        s.unbindClicked(); check(f.unbinds == 1 && s.state() == BindingSession.State.IDLE, "explicit unregister");
        s.unbindClicked(); check(f.unbinds == 1, "idempotent unbind");
        s.connected(true); check(s.state() == BindingSession.State.IDLE, "late callback ignored");
        f.accept = false; s.bindClicked(); check(f.unbinds == 2 && s.state() == BindingSession.State.IDLE, "rejection cleanup");
        f.denied = true; f.unknownRegistration = true; s.bindClicked();
        check(s.state() == BindingSession.State.IDLE, "denied/unregistered safe");
        f.denied = false; f.unknownRegistration = false; f.accept = true;
        s.bindClicked(); s.nullBinding(); check(s.state() == BindingSession.State.IDLE, "null binding cleanup");
        s.bindClicked(); s.connected(false); check(s.state() == BindingSession.State.IDLE, "null endpoint cleanup");
        s.bindClicked(); s.disconnected(); check(s.state() == BindingSession.State.IDLE, "disconnect unregister");
        int before = f.binds; s.visible(); check(f.binds == before, "no reconnect");
        s.bindClicked(); s.bindingDied(); check(s.state() == BindingSession.State.IDLE, "death unregister");
        s.bindClicked(); s.stopped(); check(s.state() == BindingSession.State.IDLE, "stop unregister");
        before = f.binds; s.bindClicked(); check(f.binds == before, "stopped cannot bind");
        s.visible(); s.bindClicked(); f.cleanupFailure = true; s.unbindClicked();
        check(s.state() == BindingSession.State.CLEANUP_FAILED, "cleanup failure explicit");
        before = f.binds; s.bindClicked(); check(f.binds == before, "failure blocks new attempt");
        s.connected(true); check(s.state() == BindingSession.State.CLEANUP_FAILED, "failure ignores callback");
        f.cleanupFailure = false; s.unbindClicked(); check(s.state() == BindingSession.State.IDLE, "manual cleanup retry only");
        System.out.println("PASS " + count + " assertions; real controller; no Android/device runtime");
    }
}
