package org.offline.binddiagnostic;

/** Owns one explicit attempt and its framework registration, never a Binder endpoint. */
public final class BindingSession {
    public enum State { IDLE, REQUESTING, WAITING, CONNECTED, CLEANUP_FAILED }
    public interface Port {
        boolean bind();
        void unbind();
        void log(String message);
    }
    private final Port port;
    private State state = State.IDLE;
    private boolean registered;
    private boolean visible;

    public BindingSession(Port port) { this.port = port; }
    public State state() { return state; }
    public void visible() { visible = true; }
    public void stopped() { visible = false; release("Activity stopped"); }
    public void bindClicked() {
        if (!visible || registered || state != State.IDLE) {
            port.log("Bind ignored: not foreground or attempt already owned");
            return;
        }
        // Own registration before crossing the framework boundary, including failure paths.
        registered = true;
        change(State.REQUESTING, "Explicit bind requested; flags=0; lifecycle side effects possible");
        try {
            boolean accepted = port.bind();
            if (!accepted) { release("Bind rejected"); }
            else if (state == State.REQUESTING) { change(State.WAITING, "Accepted; awaiting callback"); }
        } catch (SecurityException ex) {
            release("Bind denied: SecurityException");
        } catch (RuntimeException ex) {
            release("Bind failed: " + ex.getClass().getSimpleName());
        }
    }
    public void unbindClicked() { release("Explicit unbind"); }
    public void connected(boolean binderPresent) {
        if (!registered || !visible || state == State.CLEANUP_FAILED) {
            port.log("Late connection ignored; endpoint not retained");
            return;
        }
        if (!binderPresent) { release("Connected callback had no Binder"); return; }
        change(State.CONNECTED, "Connection accepted; BindingSession retains no endpoint");
    }
    public void disconnected() { release("Disconnected; unregistering; no reconnect"); }
    public void bindingDied() { release("Binding died; unregistering; no retry"); }
    public void nullBinding() { release("Null binding; unregistering"); }
    private void release(String reason) {
        if (registered) {
            // Invalidate callbacks before unbind. Failed cleanup blocks new attempts.
            change(State.CLEANUP_FAILED, reason + "; unregistering");
            try {
                port.unbind();
                registered = false;
            } catch (IllegalArgumentException ex) {
                registered = false; // Framework confirms no registration exists.
                port.log("Framework reports connection not registered");
            } catch (RuntimeException ex) {
                port.log("Cleanup failed: " + ex.getClass().getSimpleName() + "; new binds blocked");
                return;
            }
        }
        change(State.IDLE, reason + "; idle; no automatic action");
    }
    private void change(State next, String reason) {
        state = next;
        port.log(state + ": " + reason);
    }
}
