package fi.alavesa.scpaccess.door;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Matches what a player asks SCP-079 for ("the door in the cafeteria") against door names ("Cafeteria Door 2").
 * A name matches when it contains the whole phrase, or every meaningful word of it.
 */
public final class NameMatch {

    private static final Set<String> FILLER = Set.of("the", "a", "an", "door", "doors", "in", "at", "to", "of", "near", "by", "gate", "please", "open", "close", "lock", "unlock", "up", "down", "room");

    private NameMatch() { }

    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : normalize(s).split(" ")) if (!w.isEmpty() && !FILLER.contains(w)) out.add(w);
        return out;
    }

    /** Single letters name a part of a place ("hallway b"); "a" alone may just be the article. */
    private static boolean hasToken(String name, String token) {
        for (String t : normalize(name).split(" ")) if (t.equals(token)) return true;
        return false;
    }

    /**
     * Of the names that matched, the ones that fit best: a single-letter word in the query ("hallway b") must be a
     * word of the name, unless it is the article "a" ("open a door").
     */
    public static <T> List<T> refine(List<T> items, java.util.function.Function<T, String> nameOf, String query) {
        List<T> out = new ArrayList<>(items);
        String[] tokens = normalize(query).split(" ");
        for (int i = 0; i < tokens.length; i++) {
            String w = tokens[i];
            if (w.length() != 1 || !Character.isLetter(w.charAt(0))) continue;
            // "open a door", "a door in the hall": the article; "hallway a": a place
            boolean article = w.equals("a") && (i == 0 || (i + 1 < tokens.length && tokens[i + 1].startsWith("door")));
            List<T> with = new ArrayList<>();
            for (T t : out) if (hasToken(nameOf.apply(t), w)) with.add(t);
            if (!article) out = with;
        }
        return out;
    }

    /** @param name a door's name; @param query what was asked for */
    public static boolean matches(String name, String query) {
        String n = normalize(name), q = normalize(query);
        if (q.isEmpty()) return false;
        if (n.contains(q)) return true;
        List<String> wanted = words(query);
        if (wanted.isEmpty()) return false;
        List<String> have = words(name);
        for (String w : wanted) {
            boolean found = false;
            for (String h : have) if (h.equals(w) || (w.length() >= 4 && (h.startsWith(w) || w.startsWith(h) && h.length() >= 4))) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }

    /** "Cafeteria Door 2" -> "Cafeteria Door": the name without its trailing number. */
    public static String location(String name) {
        return name.replaceAll("\\s+\\d+$", "").trim();
    }
}
