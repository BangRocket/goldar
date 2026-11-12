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
    private static final int TITLE_LIMIT = 64, AUTHOR_LIMIT = 32, TEXT_LIMIT = 8192;
    private static final String CUT = " [book text cut short]";

    /** A book as a mind may read it. */
    record Read(String id, String title, String author, String text) {}

    /** Drops what could hide or reorder text: control, direction and zero-width characters. Line breaks stay. */
    private static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            boolean control = c < 0x20 && c != '\n' || c >= 0x7F && c <= 0x9F;
            boolean direction = c == 0x061C || c >= 0x200E && c <= 0x200F || c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069;
            boolean hidden = c == 0x200B || c == 0x2060 || c == 0xFEFF || c >= 0xE0000 && c <= 0xE007F;
            if (!control && !direction && !hidden) out.appendCodePoint(c);
        });
        return out.toString();
    }

    private static String clip(String s, int limit) { return s.length() > limit ? s.substring(0, limit) : s; }

    /** The book in the stack, or null when it is not a written book that can be read. */
    static Read read(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return null;
        StringBuilder text = new StringBuilder();
        List<Component> pages = content.getPages(false);
        for (int i = 0; i < Math.min(MAX_PAGES, pages.size()); i++) text.append(i > 0 ? "\n" : "").append(pages.get(i).getString());
        String body = clean(text.toString());
        if (body.length() > TEXT_LIMIT) body = body.substring(0, TEXT_LIMIT - CUT.length()) + CUT;
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
