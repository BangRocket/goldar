package dev.goldar;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How speech is cleaned before it is said or heard, and how a line is judged to be spoken to someone. */
public final class Voice {
    private Voice() {}

    public static final int MAX_LINE_CHARS = 256;
    private static final Pattern GREETING = Pattern.compile("^(hey|hi|hello|oi|ok|okay|yo)[\\s,!.]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CLOSES = Pattern.compile("^[\\s?!.]*$");

    /** A code point no one types on purpose, which can forge or hide a line: controls, zero-width and direction marks, fillers, tags. */
    static boolean hidden(int c) {
        return c <= 0x1f || c >= 0x7f && c <= 0x9f || c >= 0x200b && c <= 0x200f || c >= 0x2028 && c <= 0x202e || c >= 0x2060 && c <= 0x206f
            || c == 0xfeff || c == 0x00ad || c == 0x034f || c == 0x061c || c == 0x115f || c == 0x1160 || c == 0x180e || c >= 0x180b && c <= 0x180d
            || c == 0x180f || c >= 0x17b4 && c <= 0x17b5 || c == 0x3164 || c == 0xffa0 || c >= 0xfff0 && c <= 0xfffb || c >= 0xfe00 && c <= 0xfe0f
            || c >= 0xe0000 && c <= 0xe0fff || c >= 0x1bca0 && c <= 0x1bca3 || c >= 0x1d173 && c <= 0x1d17a || Character.getType(c) == Character.FORMAT;
    }

    /**
     * Makes a line safe to say and to store: newlines and tabs become one space, hidden characters go, runs of spaces
     * collapse, the ends are trimmed and the rest is cut to `max` code points. Empty when nothing is left.
     */
    public static String clean(String text, int max) {
        String flat = text.replaceAll("[\\r\\n\\t]+", " ");
        StringBuilder visible = new StringBuilder();
        flat.codePoints().filter(c -> !hidden(c)).forEach(visible::appendCodePoint);
        String squeezed = visible.toString().replaceAll(" {2,}", " ").trim();
        if (squeezed.codePointCount(0, squeezed.length()) > max) squeezed = squeezed.substring(0, squeezed.offsetByCodePoints(0, max));
        return squeezed.trim();
    }

    /**
     * Whether the line speaks to the one called `name`: the name as a whole word that opens the line (a greeting may
     * come first), closes it, is set off by a comma or colon, or follows an `@`. A name elsewhere in the line is a
     * mention, not an address.
     */
    public static boolean addressed(String text, String name) {
        if (name == null || name.isBlank()) return false;
        String word = "[\\p{L}\\p{N}_]";
        Matcher m = Pattern.compile("(?<!" + word + ")" + Pattern.quote(name) + "(?!" + word + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        while (m.find()) {
            String before = text.substring(0, m.start()).stripTrailing(), after = text.substring(m.end()).stripLeading();
            boolean opens = before.isEmpty() || GREETING.matcher(before).matches();
            boolean closes = CLOSES.matcher(after).matches();
            boolean setOff = after.startsWith(",") || after.startsWith(":") || before.endsWith(",") || before.endsWith(":")
                || m.start() > 0 && text.charAt(m.start() - 1) == '@';
            if (opens || closes || setOff) return true;
        }
        return false;
    }
}
