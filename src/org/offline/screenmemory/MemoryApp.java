package org.offline.screenmemory;

import android.app.Application;

/** Process-local authority: no offset restoration from disk, and never auto control. */
public final class MemoryApp extends Application {
    Repository repository;
    Catalog catalog;
    String error;
    final RuntimeState runtime = new RuntimeState();
    @Override public void onCreate() {
        super.onCreate();
        try { repository = new Repository(this); catalog = new Catalog(Catalog.initialize(repository.load(), repository), repository); }
        catch (RuntimeException ex) { error = "Storage unavailable or corrupt. Existing files preserved; control disabled."; }
    }
}
