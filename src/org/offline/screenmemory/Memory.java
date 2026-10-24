package org.offline.screenmemory;

import java.util.ArrayList;
import java.util.List;

/** Immutable offsets are short-step counts relative to the user's intended Native. */
public final class Memory {
    public static final String NATIVE_ID = "native";
    public static final Memory NATIVE = new Memory(NATIVE_ID, "Native", 16, 9, 0, 0, 0);
    public final String id, name;
    public final double width, height;
    public final int zoom, x, y;
    // Software work/storage limits only: these are NOT measured hardware bounds.
    public static final int MAX_OFFSET = 100;
    public Memory(String id, String name, double width, double height, int zoom, int x, int y) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}") || name == null
                || name.trim().isEmpty() || name.length() > 80 || name.indexOf('\n') >= 0
                || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || Math.abs((long) zoom) > MAX_OFFSET || Math.abs((long) x) > MAX_OFFSET
                || Math.abs((long) y) > MAX_OFFSET) { throw new IllegalArgumentException("Invalid memory: name, positive finite ratio or software offset limit"); }
        if (NATIVE_ID.equals(id) && (!"Native".equals(name) || width != 16 || height != 9
                || zoom != 0 || x != 0 || y != 0)) { throw new IllegalArgumentException("Native is fixed"); }
        this.id = id; this.name = name.trim(); this.width = width; this.height = height;
        this.zoom = zoom; this.x = x; this.y = y;
    }
    public Memory offsets(int z, int h, int v) { return new Memory(id, name, width, height, z, h, v); }
    public static double[] fit(double canvasWidth, double canvasHeight, double w, double h) {
        if (!Double.isFinite(canvasWidth) || !Double.isFinite(canvasHeight) || canvasWidth <= 0 || canvasHeight <= 0
                || !Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) { throw new IllegalArgumentException("Invalid geometry"); }
        boolean widthFills = w / h >= canvasWidth / canvasHeight;
        double rw = widthFills ? canvasWidth : canvasHeight * (w / h);
        double rh = widthFills ? canvasWidth * (h / w) : canvasHeight;
        return new double[] {(canvasWidth - rw) / 2, (canvasHeight - rh) / 2, rw, rh};
    }
    public static List<Memory> validate(List<Memory> input) {
        if (input == null || input.size() > 100) { throw new IllegalArgumentException("Memory count exceeds software limit"); }
        List<Memory> result = new ArrayList<>(); result.add(NATIVE);
        java.util.Set<String> ids = new java.util.HashSet<>(); ids.add(NATIVE_ID);
        for (Memory m : input) {
            if (m == null) { throw new IllegalArgumentException("Null memory"); }
            if (NATIVE_ID.equals(m.id)) { continue; }
            if (!ids.add(m.id)) { throw new IllegalArgumentException("Duplicate memory ID"); }
            result.add(m);
        }
        if (result.size() > 100) { throw new IllegalArgumentException("Memory count exceeds software limit"); }
        return result;
    }
}
