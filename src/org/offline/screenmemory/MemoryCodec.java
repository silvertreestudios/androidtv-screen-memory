package org.offline.screenmemory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Current unshipped schema only. Decode errors never trigger replacement of user data. */
public final class MemoryCodec {
    private MemoryCodec() { }
    public static byte[] encode(List<Memory> memories) throws IOException {
        List<Memory> valid = Memory.validate(memories);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(valid.size());
            for (Memory m : valid) {
                out.writeUTF(m.id); out.writeUTF(m.name); out.writeDouble(m.width); out.writeDouble(m.height);
                out.writeInt(m.zoom); out.writeInt(m.x); out.writeInt(m.y);
            }
        }
        return bytes.toByteArray();
    }
    public static List<Memory> decode(byte[] bytes) throws IOException {
        if (bytes.length > 65536) { throw new IOException("Memory file too large"); }
        List<Memory> result = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = in.readInt();
            if (count < 0 || count > 100) { throw new IOException("Invalid memory count"); }
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) {
                Memory m = new Memory(in.readUTF(), in.readUTF(), in.readDouble(), in.readDouble(), in.readInt(), in.readInt(), in.readInt());
                if (!ids.add(m.id)) { throw new IOException("Duplicate ID"); }
                result.add(m);
            }
            if (in.read() != -1) { throw new IOException("Trailing data"); }
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid stored memory", ex); }
        return Memory.validate(result);
    }
}
