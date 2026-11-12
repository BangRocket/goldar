package dev.goldar;

import com.google.gson.JsonArray;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** Written books: reading the one a body holds, and writing a new one from a book, an ink sac and a feather. */
final class Books {
    private Books() {}

    private static final int MAX_PAGES = 100;
    private static final int PAGE_CHARS = 256;
    private static final int TITLE_LIMIT = 64, AUTHOR_LIMIT = 32, TEXT_LIMIT = 8192, TEXT_BYTES = 12288;
    private static final String CUT = " [book text cut short]";

    /** A book as a mind may read it. */
    record Read(String id, String title, String author, String text) {}

    private static boolean control(int c) { return c <= 0x1f || c >= 0x7f && c <= 0x9f; }

    private static boolean joiner(int c) { return c == 0x200c || c == 0x200d; }

    private static boolean selector(int c) { return c >= 0xfe00 && c <= 0xfe0f || c >= 0xe0100 && c <= 0xe01ef; }

    /** Zero-width and direction marks, invisible operators, fillers, separators and the tag block. */
    private static boolean hiddenControl(int c) {
        return c == 0x200b || c >= 0x200e && c <= 0x200f || c == 0x061c || c >= 0x2028 && c <= 0x202e || c >= 0x2060 && c <= 0x206f
            || c == 0x00ad || c == 0x034f || c == 0x180e || c == 0x115f || c == 0x1160 || c == 0x3164 || c == 0xffa0 || c == 0xfeff
            || c >= 0xe0000 && c <= 0xe007f;
    }

    private static boolean invisible(int[] codes, int at) { return at >= 0 && at < codes.length && (joiner(codes[at]) || hiddenControl(codes[at])); }

    private static boolean visible(int[] codes, int at) {
        if (at < 0 || at >= codes.length) return false;
        int c = codes[at];
        return !control(c) && !joiner(c) && !hiddenControl(c) && !selector(c) && !Character.isWhitespace(c) && !Character.isSpaceChar(c);
    }

    /** A joiner is kept only alone, between two visible characters: a run of them is a hidden payload. */
    private static boolean loneJoiner(int[] codes, int at) {
        if (invisible(codes, at - 1) || invisible(codes, at + 1)) return false;
        int before = at - 1, after = at + 1;
        while (before >= 0 && selector(codes[before])) before--;
        while (after < codes.length && selector(codes[after])) after++;
        return visible(codes, before) && visible(codes, after);
    }

    /**
     * Drops what could hide, reorder or split the text a player reads: control characters, the newline and tab among
     * them, direction and zero-width marks, and selectors and joiners that are not doing honest work.
     */
    private static String clean(String s) {
        int[] codes = s.codePoints().toArray();
        StringBuilder out = new StringBuilder(s.length());
        for (int at = 0; at < codes.length; at++) {
            int c = codes[at];
            if (control(c) || hiddenControl(c)) continue;
            if (selector(c)) {
                if ((c == 0xfe0e || c == 0xfe0f) && visible(codes, at - 1)) out.appendCodePoint(c);
                continue;
            }
            if (joiner(c)) {
                if (loneJoiner(codes, at)) out.appendCodePoint(c);
                continue;
            }
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    /** A string's size inside a JSON reply: its escaped UTF-8 bytes, without the quotes. */
    private static int jsonBytes(String text) { return new com.google.gson.JsonPrimitive(text).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 2; }

    /** Cuts the text so that it and the note that it was cut fit in `max` bytes of JSON. */
    private static String fitBytes(String text, int max) {
        if (jsonBytes(text) <= max) return text;
        int end = text.length();
        while (end > 0 && jsonBytes(text.substring(0, end) + CUT) > max) end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + CUT;
    }

    private static String clip(String s, int limit) { return s.length() > limit ? s.substring(0, limit) : s; }

    /** The book in the stack, or null when it is not a written book that can be read. */
    static Read read(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return null;
        StringBuilder text = new StringBuilder();
        List<Component> pages = content.getPages(false);
        for (int i = 0; i < Math.min(MAX_PAGES, pages.size()); i++) text.append(i > 0 ? "\n" : "").append(clean(pages.get(i).getString()));
        String body = fitBytes(clip(text.toString(), TEXT_LIMIT), TEXT_BYTES);
        String title = clip(clean(content.title().raw()), TITLE_LIMIT), author = clip(clean(content.author()), AUTHOR_LIMIT);
        JsonArray key = new JsonArray();
        key.add(title);
        key.add(author);
        key.add(body);
        return new Read("bk_" + GoldarConfig.sha256(key.toString()).substring(0, 26), title, author, body);
    }

    /** The written book a body would read: the one in its hand, else the first it carries. Null when it has none. */
    static ItemStack held(Body b) {
        if (b.getMainHandItem().is(Items.WRITTEN_BOOK)) return b.getMainHandItem();
        for (ItemStack s : b.getInventory().getNonEquipmentItems()) if (s.is(Items.WRITTEN_BOOK)) return s;
        return null;
    }

    /** True when the body carries what a book is made from. */
    static boolean canWrite(Body b) { return b.count("writable_book") > 0 || b.count("book") > 0 && b.count("ink_sac") > 0 && b.count("feather") > 0; }

    private static void take(Body b, String item) {
        int slot = b.slotOf(item);
        if (slot >= 0) b.getInventory().getNonEquipmentItems().get(slot).shrink(1);
    }

    /** Uses up the materials and puts the finished book in the inventory. Call only when `canWrite`. */
    static Read write(Body b, String title, String text) {
        if (b.count("writable_book") > 0) {
            take(b, "writable_book");
        } else {
            take(b, "book");
            take(b, "ink_sac");
            take(b, "feather");
        }
        List<Filterable<Component>> pages = new ArrayList<>();
        for (int i = 0; i < text.length() && pages.size() < MAX_PAGES; i += PAGE_CHARS) {
            pages.add(Filterable.passThrough(Component.literal(text.substring(i, Math.min(text.length(), i + PAGE_CHARS)))));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title), b.getGameProfile().name(), 0, pages, true));
        Read read = read(book);
        if (!b.getInventory().add(book)) b.drop(book, false);
        return read;
    }
}
