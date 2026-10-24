package com.fmcc.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRouting decides, without any Minecraft types, whether a configured text goes to the chat or
 * to the command dispatcher, and clamps it to the vanilla 256 character limit.
 */
class ChatRoutingTest {
    @Test
    @DisplayName("null, empty and blank texts produce no action")
    void nothingToSend() {
        assertNull(ChatRouting.route(null));
        assertNull(ChatRouting.route(""));
        assertNull(ChatRouting.route("   "));
        assertNull(ChatRouting.route("\t\n "));
    }

    @Test
    @DisplayName("plain text is routed to the chat, stripped")
    void chatMessage() {
        ChatRouting.Routed routed = ChatRouting.route("  hello world  ");
        assertEquals(new ChatRouting.Routed(false, "hello world"), routed);
        assertFalse(routed.command());
        assertEquals("hello world", routed.payload());
    }

    @Test
    @DisplayName("a leading slash routes to the command dispatcher without the slash")
    void command() {
        ChatRouting.Routed routed = ChatRouting.route("/seed");
        assertEquals(new ChatRouting.Routed(true, "seed"), routed);
        assertTrue(routed.command());
        assertEquals("seed", routed.payload());

        assertEquals("tp 1 2 3", ChatRouting.route("  /tp 1 2 3  ").payload());
        assertEquals(true, ChatRouting.route("  /tp 1 2 3  ").command());
    }

    @Test
    @DisplayName("slash-only input is ignored")
    void slashOnly() {
        assertNull(ChatRouting.route("/"));
        assertNull(ChatRouting.route("/   "));
        assertNull(ChatRouting.route("   /   "));
    }

    @Test
    @DisplayName("'//' keeps the second slash as part of the command payload")
    void doubleSlash() {
        ChatRouting.Routed routed = ChatRouting.route("//say hi");
        assertTrue(routed.command());
        assertEquals("/say hi", routed.payload());
    }

    @Test
    @DisplayName("inner whitespace is preserved, only the ends are stripped")
    void innerWhitespacePreserved() {
        assertEquals("say  hello   there", ChatRouting.route("  say  hello   there ").payload());
    }

    @Test
    @DisplayName("messages longer than 256 UTF-16 chars are truncated (chat packet unit)")
    void lengthLimit() {
        String atLimit = "a".repeat(ChatRouting.MAX_LENGTH);
        assertEquals(atLimit, ChatRouting.route(atLimit).payload());
        assertEquals(256, ChatRouting.MAX_LENGTH);

        String overLimit = "a".repeat(ChatRouting.MAX_LENGTH + 1);
        String routed = ChatRouting.route(overLimit).payload();
        assertEquals(ChatRouting.MAX_LENGTH, routed.length());
        assertEquals(ChatRouting.MAX_LENGTH, routed.codePointCount(0, routed.length()));
    }

    @Test
    @DisplayName("commands are truncated too (slash counts against the limit)")
    void commandLengthLimit() {
        ChatRouting.Routed routed = ChatRouting.route("/" + "b".repeat(300));
        assertTrue(routed.command());
        assertEquals(255, routed.payload().length(), "the leading slash consumes one character");
        assertEquals(ChatRouting.MAX_LENGTH, routed.payload().length() + 1);
    }

    @Test
    @DisplayName("truncation is measured in UTF-16 chars (like the chat packet) and never splits a surrogate pair")
    void surrogateSafeTruncation() {
        String emoji = "\uD83D\uDE00"; // 😀, one code point, two UTF-16 units
        String routed = ChatRouting.route(emoji.repeat(300)).payload();
        // 300 emoji = 600 UTF-16 chars; the limit is 256 chars = 128 whole emoji.
        assertEquals(ChatRouting.MAX_LENGTH, routed.length());
        assertEquals(ChatRouting.MAX_LENGTH / 2, routed.codePointCount(0, routed.length()));
        assertEquals(emoji.repeat(ChatRouting.MAX_LENGTH / 2), routed);
        assertEquals(0, routed.length() % 2);

        // Odd boundary: "a" + emoji... puts a high surrogate exactly at the cut position (index
        // 255). It must be dropped, not left dangling, so 127 complete emoji + 'a' remain
        // (255 UTF-16 chars, ending in the low surrogate of a complete pair).
        String odd = "a" + emoji.repeat(300);
        String oddRouted = ChatRouting.route(odd).payload();
        assertEquals(ChatRouting.MAX_LENGTH - 1, oddRouted.length());
        assertEquals("a" + emoji.repeat(127), oddRouted);
        assertEquals(1, oddRouted.length() % 2);
        char last = oddRouted.charAt(oddRouted.length() - 1);
        assertFalse(Character.isHighSurrogate(last), "must never end on a dangling high surrogate");
        if (Character.isLowSurrogate(last)) {
            // A low surrogate is only valid when its high surrogate is present right before it.
            assertTrue(Character.isHighSurrogate(oddRouted.charAt(oddRouted.length() - 2)),
                    "must never end on an unpaired low surrogate");
        }
    }

    @Test
    @DisplayName("a command of exactly 256 characters keeps its whole payload")
    void commandAtLimit() {
        String payload = "/" + "c".repeat(ChatRouting.MAX_LENGTH - 1);
        ChatRouting.Routed routed = ChatRouting.route(payload);
        assertTrue(routed.command());
        assertEquals(ChatRouting.MAX_LENGTH - 1, routed.payload().length());
    }
}
