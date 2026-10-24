package org.offline.screenmemory;

/** Process-local request handoff; Operation remains the sole offset authority. */
final class RuntimeState {
    Operation operation;
    Memory pending;
    boolean reference, launching, preview;
    Runnable listener;
    String status = "Tracking UNKNOWN. Establish Native before applying memories.";
    void changed() { if (listener != null) { listener.run(); } }
    void acceptReference() {
        // Manual alignment already happened: old offsets are invalid even if launch fails.
        if (operation != null) { operation.invalidate("Explicit manual Native alignment"); }
        reference = true;
        status = "Native confirmation pending; tracking UNKNOWN";
    }
    void launchFailed() {
        launching = false; pending = null; reference = false;
        if (operation != null) { operation.invalidate("Own service launch failed"); }
        status = "Could not start own foreground service; tracking UNKNOWN";
    }
    void publish(Runnable notificationUpdate) {
        try { notificationUpdate.run(); }
        catch (RuntimeException ex) { /* Notification failure cannot suppress the status UI. */ }
        changed();
    }
    String displayStatus() {
        return status + " · " + (launching || (operation != null && operation.busy())
                ? "BUSY" : operation != null && operation.known() ? "Position estimated" : "Tracking UNKNOWN");
    }
}
