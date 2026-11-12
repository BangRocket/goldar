package dev.goldar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wording Geldar gives for bad arguments, and its rules for speech. */
class ParityTest {
    private static JsonObject schema(String tool) throws Exception {
        try (InputStream in = ParityTest.class.getResourceAsStream("/goldar/tools.json")) {
            for (JsonElement t : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                if (t.getAsJsonObject().get("name").getAsString().equals(tool)) return t.getAsJsonObject().getAsJsonObject("inputSchema");
            }
        }
        throw new IllegalArgumentException(tool);
    }

    private static List<String> check(String tool, String args) throws Exception {
        return Schema.check(schema(tool), JsonParser.parseString(args));
    }

    @Test void validArgumentsHaveNoFaults() throws Exception {
        assertEquals(List.of(), check("look", "{}"));
        assertEquals(List.of(), check("wait", "{\"max_seconds\":30,\"cursor\":\"c_12\",\"interests\":[\"damage\"]}"));
        assertEquals(List.of(), check("act", "{\"say\":null,\"actions\":[{\"do\":\"mine\",\"block\":\"oak_log\",\"count\":3}]}"));
        assertEquals(List.of(), check("act", "{\"intent\":{\"goal\":\"gather_oak_log\",\"program\":\"gather\",\"arguments\":{\"block\":\"oak_log\",\"count\":12},"
            + "\"wake_on\":[\"goal_completed\"],\"limits\":{\"max_world_ticks\":2400,\"max_block_operations\":64}}}"));
    }

    @Test void faultsAreWordedAsGeldarWordsThem() throws Exception {
        assertEquals(List.of("\"\" must NOT have additional properties 'foo'"), check("look", "{\"foo\":1}"));
        assertEquals(List.of("\"\" must have required property 'text'"), check("journal", "{}"));
        assertEquals(List.of("/text must be string"), check("journal", "{\"text\":5}"));
        assertEquals(List.of("/text must NOT have fewer than 1 characters"), check("say", "{\"text\":\"\"}"));
        assertEquals(List.of("/text must NOT have more than 256 characters"), check("say", "{\"text\":\"" + "a".repeat(257) + "\"}"));
        assertEquals(List.of("\"\" must have required property 'max_seconds'"), check("wait", "{}"));
        assertEquals(List.of("/max_seconds must be >= 5"), check("wait", "{\"max_seconds\":1}"));
        assertEquals(List.of("/max_seconds must be <= 240"), check("wait", "{\"max_seconds\":999}"));
        assertEquals(List.of("/max_seconds must be integer"), check("wait", "{\"max_seconds\":5.5}"));
        assertEquals(List.of("/interests/0 must be equal to one of the allowed values"), check("wait", "{\"max_seconds\":5,\"interests\":[\"gossip\"]}"));
        assertEquals(List.of("/cursor must match pattern \"^c_[0-9]{1,12}$\""), check("wait", "{\"max_seconds\":5,\"cursor\":\"x\"}"));
        assertEquals(List.of("/reasoning must NOT have more than 600 characters"), check("act", "{\"say\":\"hi\",\"reasoning\":\"" + "a".repeat(601) + "\"}"));
    }

    @Test void lengthsCountCharactersNotCodeUnits() throws Exception {
        assertEquals(List.of(), check("say", "{\"text\":\"" + "😀".repeat(256) + "\"}"));
    }

    @Test void everyFaultIsListed() throws Exception {
        assertEquals(List.of("\"\" must have required property 'max_seconds'", "\"\" must NOT have additional properties 'a'", "\"\" must NOT have additional properties 'b'"),
            check("wait", "{\"a\":1,\"b\":2}"));
    }

    @Test void aNameOpeningOrClosingALineAddressesItsOwner() {
        assertTrue(Voice.addressed("Adam, come here", "Adam"));
        assertTrue(Voice.addressed("hey adam can you help", "Adam"));
        assertTrue(Voice.addressed("where are you, Adam?", "Adam"));
        assertTrue(Voice.addressed("thanks @Adam for the wood", "Adam"));
        assertFalse(Voice.addressed("I saw Adam at the lake", "Adam"));
        assertFalse(Voice.addressed("an adamant refusal", "Adam"));
    }

    @Test void speechIsCleanedBeforeItIsSaid() {
        assertEquals("one two", Voice.clean(" one\n\ttwo​ ", 256));
        assertEquals("", Voice.clean(" ​\n", 256));
        assertEquals("abc", Voice.clean("abcdef", 3));
    }
}
