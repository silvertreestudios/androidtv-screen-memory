package org.offline.screenmemory;

/** One keyboard gesture; adapters cancel their timer whenever this state is canceled. */
final class SelectGesture {
    private boolean down, held;
    boolean press(boolean eligible, int repeats) {
        if (!eligible || repeats != 0 || down) { return false; }
        down = true; held = false; return true;
    }
    boolean hold(boolean eligible) {
        if (!eligible) { cancel(); return false; }
        if (!down || held) { return false; }
        held = true; return true;
    }
    boolean release(boolean eligible, boolean canceled) {
        boolean act = down && !held && eligible && !canceled;
        cancel(); return act;
    }
    void cancel() { down = false; held = false; }
}
