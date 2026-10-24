package com.fmcc.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data object rules: send order, trimming, blank dropping and defensive copies. */
class FmccEntryTest {
    @Test
    @DisplayName("sendableTexts keeps order and trims each text")
    void sendableTextsKeepsOrderAndTrims() {
        FmccEntry entry = new FmccEntry("id", "Title", Arrays.asList("  /seed  ", "hello", "  "));
        assertEquals(List.of("/seed", "hello"), entry.sendableTexts());
    }

    @Test
    @DisplayName("sendableTexts drops null and blank texts")
    void sendableTextsDropsBlank() {
        List<String> texts = new ArrayList<>();
        texts.add(null);
        texts.add("");
        texts.add("   ");
        texts.add("\t\n");
        texts.add("ok");
        FmccEntry entry = new FmccEntry("id", "Title", texts);
        assertEquals(List.of("ok"), entry.sendableTexts());
    }

    @Test
    @DisplayName("constructor and setTexts are null-safe / defensive")
    void nullSafety() {
        FmccEntry entry = new FmccEntry(null, null, null);
        assertTrue(entry.getTexts().isEmpty());
        assertEquals("", entry.getTitle());

        List<String> source = new ArrayList<>(List.of("a"));
        entry.setTexts(source);
        source.add("b");
        assertEquals(List.of("a"), entry.getTexts(), "setTexts must copy the list");

        entry.setTexts(null);
        assertTrue(entry.getTexts().isEmpty());
    }

    @Test
    @DisplayName("getTexts is mutable live state for the config screen")
    void textsAreLive() {
        FmccEntry entry = new FmccEntry("id", "Title", List.of());
        entry.getTexts().add("first");
        entry.getTexts().add("second");
        assertEquals(List.of("first", "second"), entry.getTexts());
        entry.getTexts().set(0, "changed");
        assertEquals("changed", entry.getTexts().get(0));
    }

    @Test
    @DisplayName("copy() is a deep copy")
    void copyIsDeep() {
        FmccEntry original = new FmccEntry("id", "Title", List.of("a", "b"));
        FmccEntry copy = original.copy();
        assertEquals(original, copy);
        assertEquals(original.getTitle(), copy.getTitle());
        assertEquals(List.of("a", "b"), copy.getTexts());
        copy.getTexts().add("c");
        assertEquals(List.of("a", "b"), original.getTexts());
        assertNotEquals(original.getTexts(), copy.getTexts());
    }

    @Test
    @DisplayName("equals/hashCode are based on the id only")
    void equalityById() {
        FmccEntry a = new FmccEntry("same", "A", List.of("x"));
        FmccEntry b = new FmccEntry("same", "B", List.of("y"));
        FmccEntry c = new FmccEntry("other", "A", List.of("x"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertFalse(a.equals(null));
        assertFalse(a.equals("not an entry"));
    }

    @Test
    @DisplayName("setTitle / setId are reflected by the getters")
    void setters() {
        FmccEntry entry = new FmccEntry("id", "old", List.of());
        entry.setTitle("new");
        entry.setId("id2");
        assertEquals("new", entry.getTitle());
        assertEquals("id2", entry.getId());
    }
}
