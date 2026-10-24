package org.offline.screenmemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Copy-on-write metadata: drafts and physical tracking have independent lifetimes. */
public final class Catalog {
    public interface Store { void save(List<Memory> memories); }
    private List<Memory> memories;
    private final Store store;
    public Catalog(List<Memory> input, Store store) { memories = Memory.validate(input); this.store = store; }
    public interface InitializationStore extends Store {
        boolean initialized();
        void markInitialized();
    }
    /** Save catalog first; a failed marker retries safely by stable ID. Load/validate before
     * invoking this policy so corrupt existing bytes are never replaced. */
    public static List<Memory> initialize(List<Memory> input, InitializationStore store) {
        List<Memory> next = Memory.validate(input);
        if (store.initialized()) { return next; }
        if (next.stream().noneMatch(m -> m.id.equals("cinemascope"))) {
            next.add(new Memory("cinemascope", "CinemaScope", 2.4, 1.0, 0, 0, 0));
        }
        next = Memory.validate(next);
        store.save(next);
        store.markInitialized();
        return next;
    }
    public List<Memory> all() { return Collections.unmodifiableList(memories); }
    public Memory get(String id) {
        for (Memory memory : memories) { if (memory.id.equals(id)) { return memory; } }
        return null;
    }
    public void save(Memory draft) {
        if (Memory.NATIVE_ID.equals(draft.id)) { throw new IllegalArgumentException("Native is read only"); }
        List<Memory> next = new ArrayList<>(memories);
        next.removeIf(m -> m.id.equals(draft.id)); next.add(draft);
        next = Memory.validate(next); store.save(next); memories = next;
    }
    public void delete(String id) {
        if (Memory.NATIVE_ID.equals(id)) { throw new IllegalArgumentException("Native cannot be deleted"); }
        List<Memory> next = new ArrayList<>(memories);
        next.removeIf(m -> m.id.equals(id)); store.save(next); memories = next;
    }
}
