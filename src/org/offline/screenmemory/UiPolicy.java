package org.offline.screenmemory;

import java.math.BigDecimal;

/** Pure presentation rules; never grants permission to send a command. */
public final class UiPolicy {
    private UiPolicy() { }
    public static String ratioName(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Aspect width and height must be positive finite numbers");
        }
        String result = BigDecimal.valueOf(width).stripTrailingZeros().toPlainString() + ":"
                + BigDecimal.valueOf(height).stripTrailingZeros().toPlainString();
        if (result.length() > 80) { throw new IllegalArgumentException("Ratio name too long; enter a short name"); }
        return result;
    }
    public static String resolveName(String name, double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Aspect width and height must be positive finite numbers");
        }
        return name.trim().isEmpty() ? ratioName(width, height) : name.trim();
    }
    /** All dimensions are physical pixels; font size is fitted independently of system scaling. */
    public static int[] panel(int width, int height, float scaledDensity) {
        if (width <= 0 || height <= 0 || !Float.isFinite(scaledDensity) || scaledDensity <= 0) {
            throw new IllegalArgumentException("Invalid display metrics");
        }
        int w = Math.min(width, Math.round(width * .72f));
        int row = Math.max(1, Math.min(Math.round(52 * scaledDensity), height / 18));
        int text = Math.max(1, Math.min(Math.round(18 * scaledDensity), Math.min(row / 3, w / 55)));
        return new int[] {w, row, text, row * 6};
    }
    /** Touch activation does not require keyboard focus; both paths require a live window. */
    public static boolean canActivate(boolean enabled, boolean attached, boolean windowFocused,
            boolean keyboardFocused, boolean keyboardInput) {
        return enabled && attached && windowFocused && (!keyboardInput || keyboardFocused);
    }
    /** A focus/window loss permanently invalidates already deferred actions. */
    public static final class Dispatch {
        private long generation;
        public long ticket() { return generation; }
        public void cancel() { generation++; }
        public boolean valid(long ticket, boolean eligible) { return eligible && ticket == generation; }
    }
    public static final class Focus {
        private String target;
        public void selected(String tag) { target = tag; }
        public String target() { return target; }
        public String restore(boolean busy) { return busy ? null : target; }
        public void clear() { target = null; }
    }
}
