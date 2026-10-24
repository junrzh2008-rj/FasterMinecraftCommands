package com.fmcc.chat;

/**
 * Pure routing rules for one configured text: strip it, clamp it to the vanilla chat limit and
 * decide whether it is a command ({@code /...}) or a chat message. Kept free of Minecraft types
 * so it can be unit tested.
 */
public final class ChatRouting {
    /**
     * Vanilla's limit for chat messages and command input, counted in UTF-16 chars exactly like
     * {@code StringEncoding.encode} used by the chat packet (code points are NOT the unit here:
     * 256 emoji would be 512 chars and get the packet encoder to throw).
     */
    public static final int MAX_LENGTH = 256;

    /** The final action for one text. */
    public record Routed(boolean command, String payload) {
    }

    private ChatRouting() {
    }

    /**
     * @param raw configured text as typed by the player
     * @return the routed message, or null when nothing should be sent
     */
    public static Routed route(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        text = truncate(text, MAX_LENGTH);
        if (text.startsWith("/")) {
            String command = text.substring(1).strip();
            if (command.isEmpty()) {
                return null;
            }
            return new Routed(true, command);
        }
        return new Routed(false, text);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        // Never cut a surrogate pair in half: the game would send a lone surrogate.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, Math.max(0, end));
    }
}
