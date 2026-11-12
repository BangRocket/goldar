package dev.goldar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SignTextTest {
    @Test void shortTextSitsOnTheFirstLine() {
        assertEquals(List.of("Home", "", "", ""), Tasks.WriteSign.lines("Home"));
    }

    @Test void wordsBreakAtSpaces() {
        assertEquals(List.of("Iron mine below", "mind the lava", "", ""), Tasks.WriteSign.lines("Iron mine below mind the lava"));
    }

    @Test void aLongWordIsSplitAcrossLines() {
        assertEquals(List.of("aaaaaaaaaaaaaaa", "aaaaa", "", ""), Tasks.WriteSign.lines("a".repeat(20)));
    }

    @Test void sixtyCharactersAlwaysFit() {
        // Breaking at spaces would need five lines here; the text is packed instead.
        String text = "abcdefghijklm abcdefghijklm abcdefghijklm abcdefghijklm abcd";
        List<String> lines = Tasks.WriteSign.lines(text);
        assertEquals(4, lines.size());
        for (String line : lines) assertTrue(line.length() <= 15, line);
        assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
    }
}
