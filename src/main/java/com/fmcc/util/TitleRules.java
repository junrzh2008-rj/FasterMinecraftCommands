package com.fmcc.util;

/**
 * Rules for entry titles ("10 characters or fewer").
 * <p>
 * Length is counted in Unicode code points, so a Chinese character counts as one and an emoji
 * (a surrogate pair in UTF-16) also counts as one. Everything else is trimmed and stripped of
 * control characters before counting.
 */
public final class TitleRules {
    /** Maximum number of code points a title may use. */
    public static final int MAX_CODE_POINTS = 10;
    /** Extra UTF-16 room so that 10 emoji can be typed before they are clamped. */
    public static final int EDIT_BOX_MAX_CHARS = 64;

    private TitleRules() {
    }

    /**
     * Normalises a raw title: trims surrounding whitespace, drops control characters and clamps
     * the code point count to {@link #MAX_CODE_POINTS}.
     *
     * @param raw raw user input, may be null
     * @return sanitised title, never null (may be empty)
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .forEach(sb::appendCodePoint);
        String cleaned = sb.toString().strip();
        if (count(cleaned) <= MAX_CODE_POINTS) {
            return cleaned;
        }
        int end = cleaned.offsetByCodePoints(0, MAX_CODE_POINTS);
        return cleaned.substring(0, end);
    }

    /** @return true when the sanitised title is usable (1..{@link #MAX_CODE_POINTS} code points). */
    public static boolean isValid(String raw) {
        String cleaned = sanitize(raw);
        int length = count(cleaned);
        return length >= 1 && length <= MAX_CODE_POINTS;
    }

    /** @return the number of Unicode code points in {@code text}. */
    public static int count(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
