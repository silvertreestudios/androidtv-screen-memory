package org.offline.screenmemory;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Private atomic files, no storage permission. Any I/O failure blocks control. */
final class Repository implements Catalog.InitializationStore {
    private final AtomicFile data, journal, initialization;
    Repository(Context context) {
        initialization = new AtomicFile(new File(context.getFilesDir(), "cinemascope-initialized"));
        data = new AtomicFile(new File(context.getFilesDir(), "memories"));
        journal = new AtomicFile(new File(context.getFilesDir(), "journal"));
    }
    List<Memory> load() {
        try { return data.getBaseFile().exists() || new File(data.getBaseFile() + ".bak").exists()
                ? MemoryCodec.decode(data.readFully()) : Collections.singletonList(Memory.NATIVE); }
        catch (IOException | RuntimeException ex) { throw new IllegalStateException("Cannot read memories; data preserved", ex); }
    }
    @Override public boolean initialized() {
        if (!initialization.getBaseFile().exists() && !new File(initialization.getBaseFile() + ".bak").exists()) { return false; }
        try {
            if (!java.util.Arrays.equals(initialization.readFully(), new byte[] {1})) {
                throw new IllegalStateException("Invalid initialization marker");
            }
            return true;
        } catch (IOException ex) { throw new IllegalStateException("Cannot read initialization marker", ex); }
    }
    @Override public void markInitialized() {
        try { write(initialization, new byte[] {1}); }
        catch (IOException ex) { throw new IllegalStateException("Cannot initialize memories", ex); }
    }
    @Override public void save(List<Memory> memories) {
        try { write(data, MemoryCodec.encode(memories)); }
        catch (IOException ex) { throw new IllegalStateException("Cannot save memories", ex); }
    }
    void event(String event) {
        try {
            String prior = journal.getBaseFile().exists() || new File(journal.getBaseFile() + ".bak").exists()
                    ? new String(journal.readFully(), StandardCharsets.UTF_8) : "";
            if (prior.length() > 60000) { throw new IOException("Journal oversized"); }
            String line = System.currentTimeMillis() + " " + event.replace('\n', ' ') + "\n";
            String next = prior + line;
            if (next.length() > 48000) { next = next.substring(next.indexOf('\n', next.length() - 40000) + 1); }
            write(journal, next.getBytes(StandardCharsets.UTF_8));
            if (Log.i("ScreenMemory", event) < 0) { throw new IllegalStateException("Critical logcat failure"); }
        } catch (IOException ex) { throw new IllegalStateException("Critical journal unavailable", ex); }
    }
    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(bytes); stream.flush();
            stream.getFD().sync(); file.finishWrite(stream);
        } catch (IOException | RuntimeException ex) { if (stream != null) { file.failWrite(stream); } throw ex; }
    }
}
