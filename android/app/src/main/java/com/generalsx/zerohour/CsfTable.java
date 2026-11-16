// GeneralsX @feature Flux Build 03/11/2026 The game's string tables, for the unit hotkey editor.
//
// generals.csf (GameText.cpp parseCSF): " FSC", version, label count, string count, a reserved word
// and the language; then per label " LBL", its string count, the label's length and bytes, and per
// string " RTS" (or "WRTS", followed by an extra ASCII value), the length in UTF-16 units and the
// text with every byte inverted.
//
// generals.str (the language packs, languages/README.md): UTF-8 text, a label line, a quoted string,
// then END; // comments; escapes \n \t \" \\.
package com.generalsx.zerohour;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class CsfTable {
    /** Label -> text, in file order. A label without a string maps to "". */
    final Map<String, String> strings = new LinkedHashMap<>();
    int version = 3;
    int language;

    static CsfTable parseCsf(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        CsfTable t = new CsfTable();
        if (data.length < 24 || data[0] != ' ' || data[1] != 'F' || data[2] != 'S' || data[3] != 'C') {
            throw new IOException("not a CSF string table");
        }
        b.position(4);
        t.version = b.getInt();
        int labels = b.getInt();
        b.getInt(); // string count
        b.getInt(); // reserved
        t.language = b.getInt();
        if (labels < 0 || labels > 1_000_000) {
            throw new IOException("corrupt CSF label count " + labels);
        }
        try {
            for (int i = 0; i < labels; i++) {
                b.getInt(); // " LBL"
                int count = b.getInt();
                int length = b.getInt();
                byte[] name = new byte[length];
                b.get(name);
                String label = new String(name, StandardCharsets.ISO_8859_1);
                String text = "";
                for (int s = 0; s < count; s++) {
                    int tag = b.getInt();
                    int units = b.getInt();
                    byte[] raw = new byte[units * 2];
                    b.get(raw);
                    for (int k = 0; k < raw.length; k++) {
                        raw[k] = (byte) ~raw[k];
                    }
                    if (s == 0) {
                        text = new String(raw, StandardCharsets.UTF_16LE);
                    }
                    if (tag == 0x53545257) { // "WRTS": an extra ASCII value follows
                        int extra = b.getInt();
                        b.position(b.position() + extra);
                    }
                }
                t.strings.put(label, text);
            }
        } catch (RuntimeException e) {
            throw new IOException("CSF ends early", e);
        }
        return t;
    }

    byte[] toCsf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] { ' ', 'F', 'S', 'C' });
        header.putInt(version);
        header.putInt(strings.size());
        header.putInt(strings.size());
        header.putInt(0);
        header.putInt(language);
        out.write(header.array(), 0, 24);
        for (Map.Entry<String, String> e : strings.entrySet()) {
            byte[] label = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
            byte[] text = e.getValue().getBytes(StandardCharsets.UTF_16LE);
            for (int k = 0; k < text.length; k++) {
                text[k] = (byte) ~text[k];
            }
            ByteBuffer rec = ByteBuffer.allocate(12 + label.length + 8 + text.length).order(ByteOrder.LITTLE_ENDIAN);
            rec.put(new byte[] { ' ', 'L', 'B', 'L' });
            rec.putInt(1);
            rec.putInt(label.length);
            rec.put(label);
            rec.put(new byte[] { ' ', 'R', 'T', 'S' });
            rec.putInt(text.length / 2);
            rec.put(text);
            out.write(rec.array(), 0, rec.capacity());
        }
        return out.toByteArray();
    }

    static CsfTable parseStr(byte[] data) {
        CsfTable t = new CsfTable();
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\r?\n");
        String label = null;
        StringBuilder text = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (text != null) {
                // inside a quoted string that spans lines
                text.append('\n').append(raw);
                if (!isOpen(text)) {
                    t.strings.put(label, unescape(stripQuotes(text)));
                    text = null;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            if (line.equalsIgnoreCase("END")) {
                label = null;
                continue;
            }
            if (label != null && line.startsWith("\"")) {
                text = new StringBuilder(line);
                if (!isOpen(text)) {
                    t.strings.put(label, unescape(stripQuotes(text)));
                    text = null;
                }
                continue;
            }
            if (label == null) {
                label = line;
            }
        }
        return t;
    }

    /** A quoted string is open until a closing quote that is not escaped. */
    private static boolean isOpen(StringBuilder s) {
        if (s.length() < 2) {
            return true;
        }
        int end = s.length() - 1;
        while (end > 0 && Character.isWhitespace(s.charAt(end))) {
            end--;
        }
        if (end == 0 || s.charAt(end) != '"') {
            return true;
        }
        int slashes = 0;
        for (int i = end - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    private static String stripQuotes(StringBuilder s) {
        String v = s.toString().trim();
        return v.substring(1, v.length() - 1);
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(n); break;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
