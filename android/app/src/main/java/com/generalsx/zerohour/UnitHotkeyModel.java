// GeneralsX @feature Flux Build 03/11/2026 Unit, building and power hotkeys, GenHotkeys-style.
//
// In both games a control-bar button's hotkey is the character after '&' in its label's text
// (HotKeyManager::searchHotKey; e.g. CONTROLBAR:ConstructAmericaPowerPlant = "Cold Fusion &Reactor").
// So this reads what the engine reads -- the factions (PlayerTemplate.ini), every object's side,
// sorting, name and command set (Data\INI\Object), the command sets and their buttons' labels
// (CommandSet.ini, CommandButton.ini) and the string table -- lets the player move the '&', and
// writes only the changed labels as a loose Data/<language>/generals.csf.
//
// Why a partial table works: a loose generals.csf is read first and counts as a mod's table
// (GameText.cpp buildModTextLayers). Without a language pack it is the primary table and every
// label it lacks falls back to the game's or the active mod's own text; with a pack its changed
// labels override the pack. A full copy would instead hide a mod's renamed labels.
//
// A hotkey pack's own loose generals.csf is kept: its labels go into the written table (the
// original file is backed up by HotkeyPackInstaller, and Reset brings it back). The written table
// names the labels the editor changed in its marker label, so a later run tells them apart.
package com.generalsx.zerohour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UnitHotkeyModel {
    static final String MARKER_LABEL = "GXFLUX:HotkeyEditor";
    private static final String MARKER_PREFIX = "edited:";

    static final class Side {
        final String name;
        String title;
        final List<Unit> units = new ArrayList<>();

        Side(String name) {
            this.name = name;
        }
    }

    static final class Unit {
        final String object;
        final String title;
        final String sorting;
        final String commandSet;
        final List<Slot> slots = new ArrayList<>();

        Unit(String object, String title, String sorting, String commandSet) {
            this.object = object;
            this.title = title;
            this.sorting = sorting;
            this.commandSet = commandSet;
        }
    }

    static final class Slot {
        final int index;
        final String button;
        final String label;

        Slot(int index, String button, String label) {
            this.index = index;
            this.button = button;
            this.label = label;
        }
    }

    private static final class ObjectDef {
        final String name;
        final String parent;
        String side;
        String sorting;
        String displayName;
        String commandSet;

        ObjectDef(String name, String parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    final List<Side> sides = new ArrayList<>();
    // GeneralsX @bugfix Flux Build 05/11/2026 Labels are matched without case, as the engine does
    // (GameText compares them case-insensitively). Leikeze's hotkey table spells every label in lower
    // case ("object:ranger"); with case-sensitive maps no INI label matched it and the editor listed
    // no unit at all once it could see that pack.
    /** What the game shows for each label without the editor's file (archives, language pack, a pack's loose table). */
    final Map<String, String> baseline = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** The editor's changes: label -> new text. */
    final Map<String, String> edits = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Labels of a hotkey pack's loose table, written back on save. */
    final Map<String, String> keptLoose = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** How many units' buttons use each label. */
    final Map<String, Integer> labelUnits = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Command-map keys pressed without a modifier in a game: letter -> command. */
    final Map<Character, String> commandKeys = new TreeMap<>();
    String textSource = "";
    /** The loose generals.csf is the editor's own (it carries the marker), not a hotkey pack's. */
    boolean looseIsOurs;
    private int csfLanguage;

    // ------------------------------------------------------------------ loading

    static UnitHotkeyModel load(GameDataIndex index, String languageDir) throws IOException {
        UnitHotkeyModel m = new UnitHotkeyModel();
        m.loadText(index, languageDir);
        m.loadCommandKeys(index, languageDir);

        Map<String, String> sideTitles = parsePlayerTemplates(text(index, "Data\\INI\\PlayerTemplate.ini"));
        Map<String, String> buttonLabels = new HashMap<>();
        // Buttons that place a building (Command = DOZER_CONSTRUCT): what makes a unit a builder.
        Set<String> constructButtons = new java.util.HashSet<>();
        for (String path : files(index, "Data\\INI\\CommandButton.ini", "Data\\INI\\CommandButton\\")) {
            parseBlocks(text(index, path), "CommandButton", (name, key, value) -> {
                if (key.equalsIgnoreCase("TextLabel")) {
                    buttonLabels.put(name, value);
                } else if (key.equalsIgnoreCase("Command")) {
                    if (value.trim().equalsIgnoreCase("DOZER_CONSTRUCT")) {
                        constructButtons.add(name);
                    } else {
                        constructButtons.remove(name);
                    }
                }
            }, name -> {
                buttonLabels.remove(name);
                constructButtons.remove(name);
            });
        }
        Map<String, TreeMap<Integer, String>> sets = new HashMap<>();
        for (String path : files(index, "Data\\INI\\CommandSet.ini", "Data\\INI\\CommandSet\\")) {
            parseBlocks(text(index, path), "CommandSet", (name, key, value) -> {
                try {
                    sets.computeIfAbsent(name, n -> new TreeMap<>()).put(Integer.parseInt(key.trim()), value);
                } catch (NumberFormatException ignored) {
                    // not a slot line
                }
            }, name -> sets.put(name, new TreeMap<>()));
        }
        Map<String, ObjectDef> objects = new LinkedHashMap<>();
        for (String path : index.list("Data\\INI\\Object\\", ".ini")) {
            parseObjects(text(index, path), objects);
        }

        for (Map.Entry<String, String> s : sideTitles.entrySet()) {
            Side side = new Side(s.getKey());
            side.title = nonEmpty(m.text(s.getValue()), s.getKey());
            m.sides.add(side);
        }
        Map<String, Side> bySide = new HashMap<>();
        for (Side s : m.sides) {
            bySide.put(s.name.toLowerCase(Locale.ROOT), s);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ObjectDef raw : objects.values()) {
            ObjectDef o = resolve(raw, objects, 0);
            if (o.side == null || o.commandSet == null) {
                continue;
            }
            Side side = bySide.get(o.side.toLowerCase(Locale.ROOT));
            TreeMap<Integer, String> set = sets.get(o.commandSet);
            if (side == null || set == null) {
                continue;
            }
            String sorting = o.sorting == null ? "" : o.sorting.toUpperCase(Locale.ROOT);
            if (!(sorting.equals("STRUCTURE") || sorting.equals("VEHICLE") || sorting.equals("INFANTRY"))) {
                // GeneralsX @bugfix Flux Build 05/11/2026 A builder is listed whatever its
                // EditorSorting says (a mod may leave it out or use another value): its command set
                // holds the build buttons whose keys the player most wants to change.
                if (!isBuilder(set, constructButtons)) {
                    continue;
                }
                sorting = "VEHICLE";
            }
            // GeneralsX @feature Flux Build 05/11/2026 Dozers and Workers get a group of their own at the
            // top of the list (BUILDER): players look for them first and missed them among the vehicles.
            // A builder has buttons that place buildings (Command = DOZER_CONSTRUCT). Not the button
            // name: training buttons (Barracks, War Factory) and drone upgrades are also called
            // Command_Construct..., and their units are not builders.
            if (!sorting.equals("STRUCTURE") && isBuilder(set, constructButtons)) {
                sorting = "BUILDER";
            }
            String title = o.displayName != null ? m.text(o.displayName) : null;
            if (title == null || title.isEmpty() || !seen.add(o.side + "|" + title + "|" + o.commandSet)) {
                continue;
            }
            Unit unit = new Unit(o.name, title.replace("\n", " "), sorting, o.commandSet);
            Set<String> labelsHere = new LinkedHashSet<>();
            for (Map.Entry<Integer, String> slot : set.entrySet()) {
                String label = buttonLabels.get(slot.getValue());
                // GeneralsX @bugfix Flux Build 05/11/2026 One entry per label: a garrison or transport
                // has one Exit button per seat (Fire Base: FireBaseExit x4, Chinook: TransportExit
                // x8), all the same key, and the list showed each of them.
                if (label != null && m.baseline.containsKey(label) && !labelsHere.contains(label)) {
                    unit.slots.add(new Slot(slot.getKey(), slot.getValue(), label));
                    labelsHere.add(label);
                }
            }
            if (unit.slots.isEmpty()) {
                continue;
            }
            for (String label : labelsHere) {
                m.labelUnits.merge(label, 1, Integer::sum);
            }
            side.units.add(unit);
        }
        for (Side s : m.sides) {
            s.units.sort((a, b) -> {
                int bySort = sortRank(a.sorting) - sortRank(b.sorting);
                return bySort != 0 ? bySort : a.title.compareToIgnoreCase(b.title);
            });
        }
        m.sides.removeIf(s -> s.units.isEmpty());
        return m;
    }

    private static boolean isBuilder(TreeMap<Integer, String> set, Set<String> constructButtons) {
        for (String button : set.values()) {
            if (button != null && constructButtons.contains(button.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int sortRank(String sorting) {
        switch (sorting) {
            case "BUILDER": return 0;
            case "STRUCTURE": return 1;
            case "INFANTRY": return 2;
            default: return 3;
        }
    }

    private static String nonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static String text(GameDataIndex index, String path) throws IOException {
        byte[] data = index.read(path);
        return data == null ? "" : new String(data, StandardCharsets.ISO_8859_1);
    }

    /** The single file, then a folder of split files (the GeneralsOnline patch's layout). */
    private static List<String> files(GameDataIndex index, String single, String folder) {
        List<String> out = new ArrayList<>();
        out.add(single);
        out.addAll(index.list(folder, ".ini"));
        return out;
    }

    private void loadText(GameDataIndex index, String languageDir) throws IOException {
        String csf = "Data\\" + languageDir + "\\generals.csf";
        byte[] base = index.readFromArchives(csf);
        textSource = index.sourceOf(csf);
        if (base == null && !languageDir.equalsIgnoreCase("english")) {
            base = index.readFromArchives("Data\\English\\generals.csf");
            textSource = index.sourceOf("Data\\English\\generals.csf");
        }
        if (base == null) {
            throw new IOException("generals.csf");
        }
        CsfTable game = CsfTable.parseCsf(base);
        csfLanguage = game.language;
        baseline.putAll(game.strings);
        // A language pack's text is what the game shows over its own table.
        java.io.File pack = index.looseFile("Data\\" + languageDir + "\\generals.str");
        if (pack != null) {
            baseline.putAll(CsfTable.parseStr(java.nio.file.Files.readAllBytes(pack.toPath())).strings);
            textSource = textSource + " + generals.str";
        }
        // A mod's own string tables (Shockwave's data\generals.str): what it adds, and what it words
        // differently from the game. With the editor's table in place these come right after it,
        // ahead of the game's (GameText.cpp puts mod tables first among the fallbacks).
        Map<String, String> gameStrings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        gameStrings.putAll(game.strings);
        List<Object[]> modFiles = index.modStringFiles(csf);
        for (int i = modFiles.size() - 1; i >= 0; i--) {
            String name = (String) modFiles.get(i)[0];
            byte[] data = (byte[]) modFiles.get(i)[1];
            CsfTable mod = name.endsWith(".csf") ? CsfTable.parseCsf(data) : CsfTable.parseStr(data);
            for (Map.Entry<String, String> e : mod.strings.entrySet()) {
                String gameText = gameStrings.get(e.getKey());
                if (gameText == null || !gameText.equals(e.getValue())) {
                    baseline.put(e.getKey(), e.getValue());
                }
            }
            textSource = textSource + " + " + name.substring(0, name.indexOf(':'));
        }
        java.io.File loose = index.looseFile(csf);
        if (loose != null) {
            CsfTable table = CsfTable.parseCsf(java.nio.file.Files.readAllBytes(loose.toPath()));
            String marker = table.strings.remove(MARKER_LABEL);
            looseIsOurs = marker != null;
            Set<String> ours = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (marker != null && marker.startsWith(MARKER_PREFIX)) {
                for (String l : marker.substring(MARKER_PREFIX.length()).split(",")) {
                    if (!l.isEmpty()) {
                        ours.add(l);
                    }
                }
            }
            for (Map.Entry<String, String> e : table.strings.entrySet()) {
                if (ours.contains(e.getKey())) {
                    edits.put(e.getKey(), e.getValue());
                } else {
                    keptLoose.put(e.getKey(), e.getValue());
                    baseline.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    private void loadCommandKeys(GameDataIndex index, String languageDir) throws IOException {
        Map<String, HotkeyEditorActivity.Rec> map = new LinkedHashMap<>();
        for (String path : new String[] { "Data\\" + languageDir + "\\CommandMap.ini", "Data\\English\\CommandMap.ini",
                "Data\\INI\\CommandMap.ini" }) {
            if (path.startsWith("Data\\English") && !map.isEmpty()) {
                continue;
            }
            for (HotkeyEditorActivity.Rec r : HotkeyEditorActivity.parse(text(index, path)).values()) {
                HotkeyEditorActivity.Rec cur = map.get(r.name);
                if (cur == null) {
                    map.put(r.name, r.copy());
                } else {
                    cur.overlay(r);
                }
            }
        }
        for (HotkeyEditorActivity.Rec r : map.values()) {
            String key = r.key();
            String scope = r.useableIn == null ? "" : r.useableIn.toUpperCase(Locale.ROOT);
            if ("NONE".equals(r.modifiers()) && "DOWN".equals(r.transition()) && scope.contains("GAME")
                    && key.length() == 5 && key.startsWith("KEY_")) {
                commandKeys.put(key.charAt(4), r.name);
            }
        }
    }

    // ------------------------------------------------------------------ INI parsing

    private interface Field {
        void set(String block, String key, String value);
    }

    private interface Start {
        void begin(String block);
    }

    /** Flat blocks: "<keyword> Name" ... "End"; every "key = value" line inside. */
    private static void parseBlocks(String text, String keyword, Field field, Start start) {
        String block = null;
        for (String raw : text.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (block == null) {
                String[] words = line.split("\\s+");
                if (words.length >= 2 && words[0].equalsIgnoreCase(keyword)) {
                    block = words[1];
                    start.begin(block);
                }
                continue;
            }
            if (line.equalsIgnoreCase("End")) {
                block = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                String value = line.substring(eq + 1).trim().split("\\s+")[0];
                field.set(block, line.substring(0, eq).trim(), value);
            }
        }
    }

    private static Map<String, String> parsePlayerTemplates(String text) {
        Map<String, String[]> templates = new LinkedHashMap<>();
        parseBlocks(text, "PlayerTemplate", (name, key, value) -> {
            String[] t = templates.get(name);
            if (key.equalsIgnoreCase("Side")) t[0] = value;
            else if (key.equalsIgnoreCase("PlayableSide")) t[1] = value;
            else if (key.equalsIgnoreCase("DisplayName")) t[2] = value;
        }, name -> templates.put(name, new String[3]));
        Map<String, String> sides = new LinkedHashMap<>();
        for (String[] t : templates.values()) {
            if (t[0] != null && t[2] != null && "yes".equalsIgnoreCase(t[1]) && !sides.containsKey(t[0])) {
                sides.put(t[0], t[2]);
            }
        }
        return sides;
    }

    private static final Pattern OBJECT = Pattern.compile("^Object\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESKIN = Pattern.compile("^ObjectReskin\\s+(\\S+)\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /**
     * The fields the editor needs, first occurrence inside each object: the design section that
     * holds them comes before the behaviour modules (a CommandSetUpgrade module's own CommandSet line
     * comes later and is not the object's).
     */
    private static void parseObjects(String text, Map<String, ObjectDef> out) {
        ObjectDef cur = null;
        for (String raw : text.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = OBJECT.matcher(line);
            if (m.find()) {
                cur = new ObjectDef(m.group(1), null);
                out.put(cur.name, cur);
                continue;
            }
            m = RESKIN.matcher(line);
            if (m.find()) {
                cur = new ObjectDef(m.group(1), m.group(2));
                out.put(cur.name, cur);
                continue;
            }
            if (cur == null) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String rest = line.substring(eq + 1).trim();
            String value = rest.isEmpty() ? "" : rest.split("\\s+")[0];
            if (key.equalsIgnoreCase("Side") && cur.side == null) cur.side = value;
            else if (key.equalsIgnoreCase("EditorSorting") && cur.sorting == null) cur.sorting = value;
            else if (key.equalsIgnoreCase("DisplayName") && cur.displayName == null) cur.displayName = value;
            else if (key.equalsIgnoreCase("CommandSet") && cur.commandSet == null) cur.commandSet = value;
        }
    }

    /** An ObjectReskin takes what it does not set from the object it reskins. */
    private static ObjectDef resolve(ObjectDef o, Map<String, ObjectDef> all, int depth) {
        if (o.parent == null || depth > 8) {
            return o;
        }
        ObjectDef p = all.get(o.parent);
        if (p == null) {
            return o;
        }
        p = resolve(p, all, depth + 1);
        ObjectDef r = new ObjectDef(o.name, null);
        r.side = o.side != null ? o.side : p.side;
        r.sorting = o.sorting != null ? o.sorting : p.sorting;
        r.displayName = o.displayName != null ? o.displayName : p.displayName;
        r.commandSet = o.commandSet != null ? o.commandSet : p.commandSet;
        return r;
    }

    private static String stripComment(String line) {
        int cut = line.length();
        int semi = line.indexOf(';');
        if (semi >= 0) cut = semi;
        int slashes = line.indexOf("//");
        if (slashes >= 0 && slashes < cut) cut = slashes;
        return line.substring(0, cut);
    }

    // ------------------------------------------------------------------ hotkeys

    /** The text the game will show for a label (after the editor's changes). */
    String text(String label) {
        String e = edits.get(label);
        return e != null ? e : baseline.get(label);
    }

    /** The label's hotkey: the character after '&', upper case; 0 if it has none. */
    static char letterOf(String text) {
        if (text == null) {
            return 0;
        }
        int amp = text.indexOf('&');
        if (amp < 0 || amp + 1 >= text.length()) {
            return 0;
        }
        char c = Character.toUpperCase(text.charAt(amp + 1));
        return Character.isLetterOrDigit(c) ? c : 0;
    }

    /** The label's text without its hotkey marker (for display). */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        return HOTKEY_SUFFIX.matcher(text).replaceAll("").replace("&", "");
    }

    private static final Pattern HOTKEY_SUFFIX = Pattern.compile("\\s*[\\[(]&.[\\])]\\s*$");

    /**
     * The text with the hotkey moved to a letter: in front of that letter where the name has it
     * (at the start of a word if possible), else as " (&X)" at the end -- the language packs' style
     * for names in other scripts. 0 removes the hotkey.
     */
    static String withLetter(String text, char letter) {
        String s = HOTKEY_SUFFIX.matcher(text == null ? "" : text).replaceAll("");
        int amp = s.indexOf('&');
        if (amp >= 0) {
            s = s.substring(0, amp) + s.substring(amp + 1);
        }
        if (letter == 0) {
            return s;
        }
        char want = Character.toUpperCase(letter);
        int at = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.toUpperCase(s.charAt(i)) == want && s.charAt(i) < 128) {
                boolean wordStart = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
                if (wordStart) {
                    at = i;
                    break;
                }
                if (at < 0) {
                    at = i;
                }
            }
        }
        return at >= 0 ? s.substring(0, at) + "&" + s.substring(at) : s + " (&" + want + ")";
    }

    void setLetter(String label, char letter) {
        String base = baseline.get(label);
        String next = withLetter(text(label), letter);
        if (next.equals(base)) {
            edits.remove(label);
        } else {
            edits.put(label, next);
        }
    }

    void revert(String label) {
        edits.remove(label);
    }

    /** Labels of this unit whose hotkey another of its buttons also uses. */
    Set<String> conflicts(Unit unit) {
        Map<Character, Set<String>> byLetter = new HashMap<>();
        for (Slot s : unit.slots) {
            char c = letterOf(text(s.label));
            if (c != 0) {
                byLetter.computeIfAbsent(c, k -> new LinkedHashSet<>()).add(s.label);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> labels : byLetter.values()) {
            if (labels.size() > 1) {
                out.addAll(labels);
            }
        }
        return out;
    }

    int unitsWithConflicts() {
        int n = 0;
        for (Side s : sides) {
            for (Unit u : s.units) {
                if (!conflicts(u).isEmpty()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The table to write, or null when there is nothing of the editor's to keep. */
    byte[] toCsf() {
        if (edits.isEmpty()) {
            return null;
        }
        CsfTable out = new CsfTable();
        out.language = csfLanguage;
        // One entry per label whatever its case (a kept "object:ranger" and an edited "OBJECT:Ranger").
        Map<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(keptLoose);
        merged.putAll(edits);
        out.strings.putAll(merged);
        List<String> labels = new ArrayList<>(edits.keySet());
        Collections.sort(labels);
        out.strings.put(MARKER_LABEL, MARKER_PREFIX + String.join(",", labels));
        return out.toCsf();
    }
}
