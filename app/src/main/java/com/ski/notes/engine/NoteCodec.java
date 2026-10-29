package com.ski.notes.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Compact binary file format for notes (.skn). */
public final class NoteCodec {
    private static final int MAGIC = 0x534B4E54; // "SKNT"
    private static final int VERSION = 1;

    private NoteCodec() {
    }

    /** Light-weight info for the notes list, read without loading strokes. */
    public static final class Meta {
        public String id;
        public String title;
        public long created;
        public long modified;
        public int paper;
        public int tone;
        public int pageCount;
    }

    public static void write(NoteDoc d, OutputStream os) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(os, 1 << 16));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(d.id == null ? "" : d.id);
        out.writeUTF(d.title == null ? "" : d.title);
        out.writeLong(d.created);
        out.writeLong(d.modified);
        out.writeByte(d.paper);
        out.writeByte(d.tone);
        out.writeInt(d.pages.size());
        for (NoteDoc.Page pg : d.pages) {
            out.writeInt(pg.strokes.size());
            for (Stroke s : pg.strokes) {
                out.writeByte(s.tool);
                out.writeInt(s.color);
                out.writeFloat(s.width);
                out.writeInt(s.n);
                for (int i = 0; i < s.n; i++) {
                    out.writeFloat(s.x[i]);
                    out.writeFloat(s.y[i]);
                    out.writeFloat(s.p[i]);
                }
            }
        }
        out.flush();
    }

    private static DataInputStream open(InputStream is) {
        return new DataInputStream(new BufferedInputStream(is, 1 << 16));
    }

    private static Meta readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Not a Ski Notes file");
        int v = in.readInt();
        if (v > VERSION) throw new IOException("Made by a newer version of Ski Notes");
        Meta m = new Meta();
        m.id = in.readUTF();
        m.title = in.readUTF();
        m.created = in.readLong();
        m.modified = in.readLong();
        m.paper = in.readUnsignedByte();
        m.tone = in.readUnsignedByte();
        m.pageCount = in.readInt();
        if (m.pageCount < 0 || m.pageCount > 100_000) throw new IOException("Damaged file");
        return m;
    }

    public static Meta readMeta(InputStream is) throws IOException {
        return readHeader(open(is));
    }

    public static NoteDoc read(InputStream is) throws IOException {
        DataInputStream in = open(is);
        Meta m = readHeader(in);
        NoteDoc d = new NoteDoc(m.id);
        d.title = m.title;
        d.created = m.created;
        d.modified = m.modified;
        d.paper = Math.min(m.paper, NoteDoc.PAPER_COUNT - 1);
        d.tone = Math.min(m.tone, NoteDoc.TONE_COUNT - 1);
        for (int pi = 0; pi < m.pageCount; pi++) {
            NoteDoc.Page pg = new NoteDoc.Page();
            int sc = in.readInt();
            if (sc < 0 || sc > 10_000_000) throw new IOException("Damaged file");
            for (int si = 0; si < sc; si++) {
                int tool = in.readUnsignedByte();
                int color = in.readInt();
                float width = in.readFloat();
                int n = in.readInt();
                if (n < 0 || n > 10_000_000) throw new IOException("Damaged file");
                Stroke s = new Stroke(Math.min(tool, Stroke.TOOL_COUNT - 1), color, width, n);
                for (int i = 0; i < n; i++) {
                    float x = in.readFloat();
                    float y = in.readFloat();
                    float p = in.readFloat();
                    s.add(x, y, p);
                }
                pg.strokes.add(s);
            }
            d.pages.add(pg);
        }
        if (d.pages.isEmpty()) d.pages.add(new NoteDoc.Page());
        return d;
    }
}
