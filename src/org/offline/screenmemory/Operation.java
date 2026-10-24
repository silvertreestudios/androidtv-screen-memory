package org.offline.screenmemory;

import java.util.ArrayList;
import java.util.List;

/** Main-thread policy; the port must serialize callbacks. Tokens reject obsolete callbacks.
 * Timed submissions never establish measured hardware completion or coordinates. */
public final class Operation {
    public interface Port {
        void event(String record); // durable private journal BEFORE every send; throws on failure
        void bind(long token);
        void send(int what);
        void schedule(long token, long delayMillis);
        void cleanup(); // clears endpoint/identity and callbacks BEFORE unregistering
        void finished(boolean submitted, String reason);
    }
    public static final int MAX_STEPS = 120;
    public static final long DEADLINE_MS = 130000;
    private final Port port;
    private boolean known, busy, connected, blocked, ending;
    private int zoom, x, y, index, beforeZoom, beforeX, beforeY;
    private boolean preview, upperLimit, lowerLimit;
    public boolean previewAllowed(int direction) {
        return direction > 0 ? !upperLimit : !lowerLimit;
    }
    private long generation;
    private List<Integer> steps = new ArrayList<>();
    public Operation(Port port) { this.port = port; } // Cold start always unknown.
    public boolean known() { return known; }
    public boolean busy() { return busy; }
    public int zoom() { return zoom; }
    public int x() { return x; }
    public int y() { return y; }
    public boolean confirmNative() {
        if (busy || blocked || ending) { return false; }
        ending = true; known = false; upperLimit = false; lowerLimit = false; long token = ++generation;
        try {
            port.event("reference user_confirmed Native; no command; measured_coordinates=unknown");
            if (token != generation) { return false; }
            zoom = 0; x = 0; y = 0; upperLimit = false; lowerLimit = false; known = true; return true;
        } catch (RuntimeException ex) { known = false; return false; }
        finally { ending = false; }
    }

    public static List<Integer> plan(int z, int x, int y, Memory target) {
        List<Integer> result = new ArrayList<>();
        add(result, (long) target.zoom - z, 428, 429);
        add(result, (long) target.x - x, 420, 421);
        add(result, (long) target.y - y, 422, 423);
        return result;
    }
    private static void add(List<Integer> list, long delta, int positive, int negative) {
        if (Math.abs(delta) + list.size() > MAX_STEPS) { throw new IllegalArgumentException("Software operation budget: 120 steps; not hardware bounds"); }
        for (long i = 0; i < Math.abs(delta); i++) { list.add(delta > 0 ? positive : negative); }
    }
    public boolean start(Memory target) { return start(target, false); }
    public boolean preview(Memory target) { return start(target, true); }
    private boolean start(Memory target, boolean livePreview) {
        if (busy || !known || blocked || ending) { return false; }
        List<Integer> planned = plan(zoom, x, y, target);
        if (livePreview && (planned.size() != 1 ||
                (planned.get(0) == 428 && upperLimit) || (planned.get(0) == 429 && lowerLimit))) { return false; }
        preview = livePreview; beforeZoom = zoom; beforeX = x; beforeY = y;
        busy = true; connected = false; steps = planned; index = 0; long token = ++generation;
        try {
            port.event("operation target=" + target.id + " offsets=" + target.zoom + "," + target.x + "," + target.y
                    + " steps=" + steps.size() + " dirty=true scale=unknown coordinates=unknown completion=unknown");
            if (!valid(token)) { return true; }
            if (steps.isEmpty()) { complete(); } else { port.bind(token); }
        } catch (RuntimeException ex) { abort("start/storage/bind failure"); }
        return true;
    }
    private boolean valid(long token) { return busy && token == generation; }
    public void connected(long token) {
        // A connection callback may only open the sequence once, before any step.
        // During send(), connected is temporarily false to block timer reentry; it
        // must not make a duplicate connection eligible to dispatch recursively.
        if (!valid(token) || connected || index != 0) { return; }
        connected = true; tick(token);
    }
    public void tick(long token) {
        if (!valid(token) || !connected) { return; }
        if (index == steps.size()) { complete(); return; }
        int what = steps.get(index);
        // Consume the index before any reentrant port boundary: never retry a planned step.
        int step = ++index;
        connected = false;
        try {
            port.event("step=" + step + " what=" + what + " arg1=0 arg2=0 key_type=false is_first=false dirty=true");
            if (!valid(token)) { return; }
            port.send(what);
            if (!valid(token)) { return; }
            if (what == 428) { zoom++; } else if (what == 429) { zoom--; }
            else if (what == 420) { x++; } else if (what == 421) { x--; }
            else if (what == 422) { y++; } else { y--; }
            port.event("submission step=" + step + " hardware_completion=unknown");
            if (!valid(token)) { return; }
            // Every tick has a new token, so duplicates cannot consume the next step early.
            connected = true;
            port.schedule(++generation, 1000);
        } catch (RuntimeException ex) { abort("send/journal/schedule failure; outcome uncertain"); }
    }
    /** Exact pairs are boundary reports, never positive acknowledgments. The service rejects
     * obsolete connection identities. One-second grace cannot resolve firmware's missing ordinal. */
    public void reply(int what, int command) {
        if (!busy) { return; }
        long token = generation;
        boolean supported = (what == 2002 && command == 428) || (what == 2001 && command == 429);
        boolean correlated = supported && index > 0 && steps.get(index - 1) == command;
        try {
            port.event("reply what=" + what + " arg1=" + command + " classification="
                    + (correlated ? "supported_zoom_limit" : "uncertain") + " step=" + index);
        } catch (RuntimeException ex) { abort("reply journal failure"); return; }
        if (!valid(token)) { return; }
        if (preview && steps.size() == 1 && index == 1 && connected && correlated) {
            // Explicit unverified protocol assumption: the sole request was rejected/clamped.
            zoom = beforeZoom; x = beforeX; y = beforeY;
            if (command == 428) { upperLimit = true; } else { lowerLimit = true; }
            finishLimit();
        } else { abort(correlated ? "Recall stopped at limit; re-establish reference; saved memories preserved"
                : "Uncertain vendor reply"); }
    }
    private void finishLimit() {
        connected = false; long token = ++generation;
        try {
            port.cleanup();
            if (!valid(token)) { return; }
            port.event("preview limit; rejected/clamped assumption; Native reference retained estimated");
        } catch (RuntimeException ex) { abort("limit cleanup/journal failure"); return; }
        if (!valid(token)) { return; }
        busy = false;
        port.finished(true, "Digital zoom limit reached; Native reference retained (estimated)");
    }
    public void failure(long token, String reason) { if (valid(token)) { abort(reason); } }
    public void stop(String reason) { if (busy) { abort(reason); } }
    public void invalidate(String reason) { if (busy) { abort(reason); } else { known = false; upperLimit = false; lowerLimit = false; ++generation; } }
    private void complete() {
        connected = false; long token = ++generation;
        try {
            port.cleanup();
            if (!valid(token)) { return; }
            port.event("cleanup success; timed submissions settled; dirty=false hardware_completion=unknown");
        } catch (RuntimeException ex) { abort("cleanup/journal failure"); return; }
        if (!valid(token)) { return; }
        busy = false;
        if (preview && steps.size() == 1) {
            if (steps.get(0) == 429) { upperLimit = false; }
            if (steps.get(0) == 428) { lowerLimit = false; }
        }
        port.finished(true, "Timed submissions finished; physical completion unmeasured");
    }
    private void abort(String reason) {
        if (ending || !busy) { return; }
        ending = true; busy = false; known = false; upperLimit = false; lowerLimit = false; connected = false; ++generation;
        try { port.event("abort " + reason + " dirty=true tracking=unknown"); }
        catch (RuntimeException ex) { /* Remain unknown even when journal is unavailable. */ }
        try { port.cleanup(); }
        catch (RuntimeException ex) { blocked = true; }
        ending = false; port.finished(false, reason + "; tracking UNKNOWN");
    }
}
