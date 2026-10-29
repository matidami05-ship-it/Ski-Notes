package com.ski.notes.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class TextDocTest {

    private static TextBox box(String t) {
        return new TextBox(100, 200, 800, 36, 0xFF000000, t,
            new TextBox.Run[]{new TextBox.Run(0, 5, TextBox.BOLD | TextBox.UNDERLINE, 0xFFE5383B, 1.5f)});
    }

    @Test
    public void codecRoundTripsTextAndPdfPages() throws Exception {
        NoteDoc d = NoteDoc.blank("n1");
        d.hasPdf = true;
        d.pages.get(0).texts.add(box("Hello world\nsecond line ✓"));
        d.pages.add(new NoteDoc.Page(900f, 3));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NoteCodec.write(d, bos);
        NoteDoc r = NoteCodec.read(new ByteArrayInputStream(bos.toByteArray()));
        assertTrue(r.hasPdf);
        assertEquals(2, r.pages.size());
        assertEquals(900f, r.pages.get(1).height, 0);
        assertEquals(3, r.pages.get(1).pdfPage);
        assertEquals(-1, r.pages.get(0).pdfPage);
        TextBox t = r.pages.get(0).texts.get(0);
        assertTrue(t.sameAs(d.pages.get(0).texts.get(0)));
        NoteCodec.Meta m = NoteCodec.readMeta(new ByteArrayInputStream(bos.toByteArray()));
        assertTrue(m.text.contains("second line ✓"));
        assertTrue(m.hasPdf);
    }

    @Test
    public void readsVersionOneFiles() throws Exception {
        // Hand-built v1 file: one page with one 1-point stroke.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
        o.writeInt(0x534B4E54); o.writeInt(1); o.writeUTF("old"); o.writeUTF("Old note");
        o.writeLong(1); o.writeLong(2); o.writeByte(1); o.writeByte(0); o.writeInt(1);
        o.writeInt(1); o.writeByte(0); o.writeInt(0xFF000000); o.writeFloat(6); o.writeInt(1);
        o.writeFloat(5); o.writeFloat(6); o.writeFloat(0.5f);
        o.flush();
        NoteDoc d = NoteCodec.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals("Old note", d.title);
        assertEquals(1, d.strokeCount());
        assertEquals(NoteDoc.PAGE_H, d.pages.get(0).height, 0);
        assertFalse(d.hasPdf);
    }

    @Test
    public void textUndoRedo() {
        NoteDoc d = NoteDoc.blank("x");
        History h = new History(d, 50);
        TextBox a = box("one");
        h.setTexts(0, Arrays.asList(a));
        TextBox b = a.withContent("one two", a.runs);
        h.setTexts(0, Arrays.asList(b));
        assertEquals("one two", d.pages.get(0).texts.get(0).text);
        h.undo();
        assertEquals("one", d.pages.get(0).texts.get(0).text);
        h.undo();
        assertTrue(d.pages.get(0).texts.isEmpty());
        assertFalse(h.canUndo());
        h.redo();
        h.redo();
        assertEquals("one two", d.pages.get(0).texts.get(0).text);
        // no-op change records nothing
        h.setTexts(0, new ArrayList<>(d.pages.get(0).texts));
        h.undo();
        assertEquals("one", d.pages.get(0).texts.get(0).text);
    }

    @Test
    public void clearPageUndoesStrokesAndTextTogether() {
        NoteDoc d = NoteDoc.blank("x");
        History h = new History(d, 50);
        Stroke s = new Stroke(0, 0, 4);
        s.add(1, 1, 0.5f);
        h.add(0, s);
        h.setTexts(0, Arrays.asList(box("hi")));
        h.apply(0, Arrays.asList(0), null, new ArrayList<>());
        assertTrue(d.pages.get(0).isEmpty());
        h.undo();
        assertEquals(1, d.pages.get(0).strokes.size());
        assertEquals(1, d.pages.get(0).texts.size());
    }

    @Test
    public void docxRoundTrip() throws Exception {
        List<RichPara> paras = new ArrayList<>();
        RichPara t = new RichPara();
        t.heading = -1;
        t.append("My Title", 0, 0, 1f);
        paras.add(t);
        RichPara h = new RichPara();
        h.heading = 2;
        h.append("Section & <stuff>", 0, 0, 1f);
        paras.add(h);
        RichPara p = new RichPara();
        p.append("Plain ", 0, 0, 1f);
        p.append("bold red", TextBox.BOLD, 0xFFE5383B, 1f);
        p.append(" big italic", TextBox.ITALIC, 0, 2f);
        paras.add(p);
        RichPara b = new RichPara();
        b.bullet = true;
        b.pageBreakBefore = true;
        b.append("A bullet", 0, 0, 1f);
        paras.add(b);
        RichPara img = new RichPara();
        img.image = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        img.imageW = 200;
        img.imageH = 100;
        paras.add(img);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Docx.write(paras, "T", bos);
        List<RichPara> r = Docx.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(5, r.size());
        assertEquals(-1, r.get(0).heading);
        assertEquals("My Title", r.get(0).plain());
        assertEquals(2, r.get(1).heading);
        assertEquals("Section & <stuff>", r.get(1).plain());
        assertEquals("Plain bold red big italic", r.get(2).plain());
        boolean sawBoldRed = false, sawBigItalic = false;
        for (TextBox.Run run : r.get(2).runs) {
            String piece = r.get(2).plain().substring(run.start, run.end);
            if (piece.equals("bold red") && (run.flags & TextBox.BOLD) != 0 && run.color == 0xFFE5383B) sawBoldRed = true;
            if (piece.equals(" big italic") && (run.flags & TextBox.ITALIC) != 0 && run.scale == 2f) sawBigItalic = true;
        }
        assertTrue(sawBoldRed);
        assertTrue(sawBigItalic);
        assertTrue(r.get(3).bullet);
        assertTrue(r.get(3).pageBreakBefore);
        assertEquals(7, r.get(4).image.length);
        assertEquals(200, r.get(4).imageW);
    }

    @Test
    public void textBoxSplitsIntoParagraphs() {
        TextBox t = new TextBox(0, 0, 100, 30, 0, "Head\n• item one\nend",
            new TextBox.Run[]{new TextBox.Run(0, 4, TextBox.BOLD, 0, 1.5f), new TextBox.Run(7, 10, TextBox.ITALIC, 0, 1f)});
        List<RichPara> ps = RichPara.fromTextBox(t);
        assertEquals(3, ps.size());
        assertEquals("Head", ps.get(0).plain());
        assertEquals(TextBox.BOLD, ps.get(0).runs.get(0).flags);
        assertTrue(ps.get(1).bullet);
        assertEquals("item one", ps.get(1).plain());
        assertEquals(0, ps.get(1).runs.get(0).start);
        assertEquals(3, ps.get(1).runs.get(0).end);
        assertEquals("end", ps.get(2).plain());
    }

    @Test(expected = java.io.IOException.class)
    public void docxRejectsNonWord() throws Exception {
        Docx.read(new ByteArrayInputStream("hello".getBytes()));
    }
}
