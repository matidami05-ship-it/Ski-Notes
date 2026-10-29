package com.ski.notes.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Reads and writes Word (.docx) files as formatted paragraphs. */
public final class Docx {
    private Docx() {
    }

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";

    // ------------------------------------------------------------------ reading

    public static List<RichPara> read(InputStream is) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(is)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 15];
            long total = 0;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                boolean want = name.equals("word/document.xml") || name.equals("word/styles.xml")
                    || name.equals("word/_rels/document.xml.rels") || name.equals("word/numbering.xml")
                    || name.startsWith("word/media/");
                if (!want) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 300L * 1024 * 1024) throw new IOException("File is too large");
                }
                parts.put(name, bos.toByteArray());
            }
        }
        byte[] doc = parts.get("word/document.xml");
        if (doc == null) throw new IOException("Not a Word document");
        try {
            DocumentBuilder db = builder();
            Map<String, String> styleHeading = parseStyles(db, parts.get("word/styles.xml"));
            Map<String, String> rels = parseRels(db, parts.get("word/_rels/document.xml.rels"));
            Document d = db.parse(new ByteArrayInputStream(doc));
            Element body = first(d.getDocumentElement(), W, "body");
            List<RichPara> out = new ArrayList<>();
            if (body != null) readBlock(body, out, styleHeading, rels, parts);
            return out;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Couldn't read this Word file", ex);
        }
    }

    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static void readBlock(Element parent, List<RichPara> out, Map<String, String> styles,
                                  Map<String, String> rels, Map<String, byte[]> parts) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!W.equals(el.getNamespaceURI())) continue;
            switch (el.getLocalName()) {
                case "p":
                    readParagraph(el, out, styles, rels, parts);
                    break;
                case "tbl":
                    readTable(el, out, styles, rels, parts);
                    break;
                case "sdt": {
                    Element c = first(el, W, "sdtContent");
                    if (c != null) readBlock(c, out, styles, rels, parts);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void readTable(Element tbl, List<RichPara> out, Map<String, String> styles,
                                  Map<String, String> rels, Map<String, byte[]> parts) {
        for (Element tr : children(tbl, W, "tr")) {
            RichPara row = new RichPara();
            boolean firstCell = true;
            for (Element tc : children(tr, W, "tc")) {
                List<RichPara> cell = new ArrayList<>();
                readBlock(tc, cell, styles, rels, parts);
                if (!firstCell) row.append("   |   ", 0, 0xFF9AA0AA, 1f);
                firstCell = false;
                boolean firstPara = true;
                for (RichPara cp : cell) {
                    if (cp.image != null) continue;
                    if (!firstPara) row.text.append(' ');
                    firstPara = false;
                    int off = row.text.length();
                    row.text.append(cp.text);
                    for (TextBox.Run r : cp.runs) row.runs.add(new TextBox.Run(r.start + off, r.end + off, r.flags, r.color, r.scale));
                }
            }
            out.add(row);
        }
    }

    private static void readParagraph(Element p, List<RichPara> out, Map<String, String> styles,
                                      Map<String, String> rels, Map<String, byte[]> parts) {
        RichPara para = new RichPara();
        Element pPr = first(p, W, "pPr");
        if (pPr != null) {
            Element ps = first(pPr, W, "pStyle");
            if (ps != null) para.heading = headingFor(ps.getAttributeNS(W, "val"), styles);
            Element ol = first(pPr, W, "outlineLvl");
            if (ol != null && para.heading == 0) {
                try {
                    int lvl = Integer.parseInt(ol.getAttributeNS(W, "val"));
                    if (lvl <= 2) para.heading = lvl + 1;
                } catch (NumberFormatException ignored) {
                }
            }
            if (first(pPr, W, "numPr") != null) para.bullet = true;
            Element jc = first(pPr, W, "jc");
            if (jc != null) {
                String v = jc.getAttributeNS(W, "val");
                if ("center".equals(v)) para.align = RichPara.ALIGN_CENTER;
                else if ("right".equals(v) || "end".equals(v)) para.align = RichPara.ALIGN_RIGHT;
            }
            if (first(pPr, W, "pageBreakBefore") != null) para.pageBreakBefore = true;
        }
        float headScale = RichPara.headingScale(para.heading);
        int headFlags = para.heading != 0 ? TextBox.BOLD : 0;
        List<RichPara> images = new ArrayList<>();
        readRuns(p, para, headScale, headFlags, rels, parts, images, out);
        // A paragraph that only holds a picture becomes just the picture.
        if (!(images.size() > 0 && para.text.length() == 0)) out.add(para);
        else if (para.pageBreakBefore) images.get(0).pageBreakBefore = true;
        out.addAll(images);
    }

    /** Walks runs, including those inside hyperlinks, smart tags and inserted-text wrappers. */
    private static void readRuns(Element container, RichPara para, float headScale, int headFlags,
                                 Map<String, String> rels, Map<String, byte[]> parts, List<RichPara> images, List<RichPara> out) {
        for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!W.equals(el.getNamespaceURI())) continue;
            String ln = el.getLocalName();
            if (ln.equals("hyperlink") || ln.equals("smartTag") || ln.equals("ins") || ln.equals("fldSimple")) {
                readRuns(el, para, headScale, headFlags, rels, parts, images, out);
                continue;
            }
            if (ln.equals("sdt")) {
                Element c = first(el, W, "sdtContent");
                if (c != null) readRuns(c, para, headScale, headFlags, rels, parts, images, out);
                continue;
            }
            if (!ln.equals("r")) continue;
            int flags = headFlags;
            int color = 0;
            float scale = headScale;
            Element rPr = first(el, W, "rPr");
            if (rPr != null) {
                if (on(rPr, "b")) flags |= TextBox.BOLD;
                if (on(rPr, "i")) flags |= TextBox.ITALIC;
                Element u = first(rPr, W, "u");
                if (u != null && !"none".equals(u.getAttributeNS(W, "val"))) flags |= TextBox.UNDERLINE;
                if (on(rPr, "strike") || on(rPr, "dstrike")) flags |= TextBox.STRIKE;
                Element c = first(rPr, W, "color");
                if (c != null) {
                    String v = c.getAttributeNS(W, "val");
                    if (v != null && v.length() == 6 && !"000000".equals(v)) {
                        try {
                            color = 0xFF000000 | Integer.parseInt(v, 16);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                Element sz = first(rPr, W, "sz");
                if (sz != null) {
                    try {
                        float half = Float.parseFloat(sz.getAttributeNS(W, "val"));
                        scale = Math.max(0.5f, Math.min(4f, half / 22f)); // 11pt body = 1.0
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (!(c instanceof Element)) continue;
                Element ce = (Element) c;
                String cn = ce.getLocalName();
                if (W.equals(ce.getNamespaceURI())) {
                    switch (cn) {
                        case "t":
                            para.append(ce.getTextContent(), flags, color, scale);
                            break;
                        case "tab":
                            para.append("    ", flags, color, scale);
                            break;
                        case "br": {
                            String type = ce.getAttributeNS(W, "type");
                            if ("page".equals(type)) {
                                // Finish the current paragraph and start the rest on a new page.
                                out.add(copyOf(para));
                                para.text.setLength(0);
                                para.runs.clear();
                                para.pageBreakBefore = true;
                                para.bullet = false;
                            } else {
                                para.append(" ", flags, color, scale); // soft line break
                            }
                            break;
                        }
                        case "cr":
                            para.append(" ", flags, color, scale);
                            break;
                        case "drawing":
                            RichPara img = readImage(ce, rels, parts);
                            if (img != null) images.add(img);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    private static RichPara copyOf(RichPara p) {
        RichPara c = new RichPara();
        c.text.append(p.text);
        c.runs.addAll(p.runs);
        c.heading = p.heading;
        c.bullet = p.bullet;
        c.align = p.align;
        c.pageBreakBefore = p.pageBreakBefore;
        return c;
    }

    private static RichPara readImage(Element drawing, Map<String, String> rels, Map<String, byte[]> parts) {
        NodeList blips = drawing.getElementsByTagNameNS(A, "blip");
        if (blips.getLength() == 0) return null;
        String rid = ((Element) blips.item(0)).getAttributeNS(R, "embed");
        String target = rels.get(rid);
        if (target == null) return null;
        String path = target.startsWith("/") ? target.substring(1) : "word/" + target;
        byte[] data = parts.get(path);
        if (data == null) return null;
        RichPara p = new RichPara();
        p.image = data;
        NodeList ext = drawing.getElementsByTagNameNS(WP, "extent");
        if (ext.getLength() > 0) {
            Element e = (Element) ext.item(0);
            try {
                p.imageW = (int) (Long.parseLong(e.getAttribute("cx")) / 12700L);
                p.imageH = (int) (Long.parseLong(e.getAttribute("cy")) / 12700L);
            } catch (NumberFormatException ignored) {
            }
        }
        return p;
    }

    private static boolean on(Element rPr, String name) {
        Element e = first(rPr, W, name);
        if (e == null) return false;
        String v = e.getAttributeNS(W, "val");
        return v == null || v.isEmpty() || !(v.equals("0") || v.equals("false") || v.equals("none"));
    }

    private static int headingFor(String styleId, Map<String, String> styles) {
        String name = styles.getOrDefault(styleId, styleId);
        String n = name.toLowerCase().replace(" ", "");
        if (n.equals("title")) return -1;
        if (n.startsWith("heading")) {
            try {
                int lvl = Integer.parseInt(n.substring(7));
                return Math.max(1, Math.min(3, lvl));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        if (n.equals("subtitle")) return 3;
        return 0;
    }

    private static Map<String, String> parseStyles(DocumentBuilder db, byte[] xml) {
        Map<String, String> m = new HashMap<>();
        if (xml == null) return m;
        try {
            Document d = db.parse(new ByteArrayInputStream(xml));
            NodeList list = d.getElementsByTagNameNS(W, "style");
            for (int i = 0; i < list.getLength(); i++) {
                Element s = (Element) list.item(i);
                Element name = first(s, W, "name");
                if (name != null) m.put(s.getAttributeNS(W, "styleId"), name.getAttributeNS(W, "val"));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static Map<String, String> parseRels(DocumentBuilder db, byte[] xml) {
        Map<String, String> m = new HashMap<>();
        if (xml == null) return m;
        try {
            Document d = db.parse(new ByteArrayInputStream(xml));
            NodeList list = d.getDocumentElement().getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                if (!(list.item(i) instanceof Element)) continue;
                Element r = (Element) list.item(i);
                m.put(r.getAttribute("Id"), r.getAttribute("Target"));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private static Element first(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local.equals(n.getLocalName()) && ns.equals(n.getNamespaceURI())) return (Element) n;
        }
        return null;
    }

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local.equals(n.getLocalName()) && ns.equals(n.getNamespaceURI())) out.add((Element) n);
        }
        return out;
    }

    // ------------------------------------------------------------------ writing

    /** Writes paragraphs (with optional PNG pictures) as a .docx file. Base text is 11pt. */
    public static void write(List<RichPara> paras, String title, OutputStream os) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(os);
        List<byte[]> media = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (RichPara p : paras) {
            if (p.image != null) {
                media.add(p.image);
                body.append(imageParagraph(media.size(), p.imageW, p.imageH));
                continue;
            }
            body.append("<w:p><w:pPr>");
            if (p.heading == -1) body.append("<w:pStyle w:val=\"Title\"/>");
            else if (p.heading > 0) body.append("<w:pStyle w:val=\"Heading").append(p.heading).append("\"/>");
            else if (p.bullet) body.append("<w:pStyle w:val=\"ListBullet\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>");
            if (p.pageBreakBefore) body.append("<w:pageBreakBefore/>");
            if (p.align == RichPara.ALIGN_CENTER) body.append("<w:jc w:val=\"center\"/>");
            else if (p.align == RichPara.ALIGN_RIGHT) body.append("<w:jc w:val=\"right\"/>");
            body.append("</w:pPr>");
            String text = p.plain();
            // Split the text at run boundaries so each piece has one style.
            List<Integer> cuts = new ArrayList<>();
            cuts.add(0);
            cuts.add(text.length());
            for (TextBox.Run r : p.runs) {
                cuts.add(Math.max(0, Math.min(text.length(), r.start)));
                cuts.add(Math.max(0, Math.min(text.length(), r.end)));
            }
            cuts.sort(null);
            for (int i = 0; i + 1 < cuts.size(); i++) {
                int a = cuts.get(i), b = cuts.get(i + 1);
                if (a >= b) continue;
                int flags = 0, color = 0;
                float scale = 1f;
                for (TextBox.Run r : p.runs) {
                    if (r.start <= a && r.end >= b) {
                        flags |= r.flags;
                        if (r.color != 0) color = r.color;
                        if (r.scale != 1f) scale = r.scale;
                    }
                }
                body.append("<w:r>");
                StringBuilder rpr = new StringBuilder();
                if ((flags & TextBox.BOLD) != 0) rpr.append("<w:b/>");
                if ((flags & TextBox.ITALIC) != 0) rpr.append("<w:i/>");
                if ((flags & TextBox.STRIKE) != 0) rpr.append("<w:strike/>");
                if ((flags & TextBox.UNDERLINE) != 0) rpr.append("<w:u w:val=\"single\"/>");
                if (color != 0) rpr.append(String.format("<w:color w:val=\"%06X\"/>", color & 0xFFFFFF));
                if (scale != 1f && p.heading == 0) rpr.append("<w:sz w:val=\"").append(Math.round(22 * scale)).append("\"/>");
                if (rpr.length() > 0) body.append("<w:rPr>").append(rpr).append("</w:rPr>");
                String piece = text.substring(a, b);
                String[] lines = piece.split("[ \n]", -1);
                for (int li = 0; li < lines.length; li++) {
                    if (li > 0) body.append("<w:br/>");
                    body.append("<w:t xml:space=\"preserve\">").append(esc(lines[li])).append("</w:t>");
                }
                body.append("</w:r>");
            }
            body.append("</w:p>");
        }

        put(zip, "[Content_Types].xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>");
        put(zip, "_rels/.rels",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>");
        put(zip, "docProps/core.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>" + esc(title == null ? "" : title) + "</dc:title><dc:creator>Ski Notes</dc:creator></cp:coreProperties>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rIdNum\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>");
        for (int i = 1; i <= media.size(); i++) {
            String ext = isJpeg(media.get(i - 1)) ? "jpeg" : "png";
            rels.append("<Relationship Id=\"rIdImg").append(i)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image")
                .append(i).append('.').append(ext).append("\"/>");
        }
        rels.append("</Relationships>");
        put(zip, "word/_rels/document.xml.rels", rels.toString());
        put(zip, "word/styles.xml", STYLES);
        put(zip, "word/numbering.xml", NUMBERING);
        put(zip, "word/document.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\" xmlns:wp=\"" + WP + "\" xmlns:a=\"" + A + "\""
                + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
                + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
                + "</w:body></w:document>");
        for (int i = 1; i <= media.size(); i++) {
            byte[] data = media.get(i - 1);
            zip.putNextEntry(new ZipEntry("word/media/image" + i + "." + (isJpeg(data) ? "jpeg" : "png")));
            zip.write(data);
            zip.closeEntry();
        }
        zip.finish();
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static String imageParagraph(int idx, int wPt, int hPt) {
        // Fit within the text column (about 6.6 inches wide).
        double maxW = 475;
        double w = wPt > 0 ? wPt : maxW, h = hPt > 0 ? hPt : maxW * 0.7;
        if (w > maxW) {
            h = h * maxW / w;
            w = maxW;
        }
        long cx = Math.round(w * 12700), cy = Math.round(h * 12700);
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
            + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/><wp:docPr id=\"" + idx + "\" name=\"Picture " + idx + "\"/>"
            + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
            + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + idx + "\" name=\"image" + idx + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
            + "<pic:blipFill><a:blip r:embed=\"rIdImg" + idx + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
            + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
            + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
            + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c < 0x20 && c != '\t') break; // not allowed in XML
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<w:styles xmlns:w=\"" + W + "\">"
        + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"48\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"200\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"160\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:style>"
        + "</w:styles>";

    private static final String NUMBERING = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        + "<w:numbering xmlns:w=\"" + W + "\">"
        + "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/>"
        + "<w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>"
        + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>"
        + "</w:numbering>";
}
