package com.fmcc.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Title rules: 1..10 Unicode code points, control characters stripped, surrounding blank removed.
 * Code point counting is what makes CJK and emoji behave like "one character" for the player.
 */
class TitleRulesTest {
    @Test
    @DisplayName("sanitize trims surrounding whitespace")
    void sanitizeTrims() {
        assertEquals("abc", TitleRules.sanitize("  abc  "));
        assertEquals("abc", TitleRules.sanitize("\t abc \n"));
        assertEquals("", TitleRules.sanitize("    "));
    }

    @Test
    @DisplayName("sanitize(null) is an empty string, never null")
    void sanitizeNull() {
        assertEquals("", TitleRules.sanitize(null));
        assertEquals("", TitleRules.sanitize(""));
    }

    @Test
    @DisplayName("control characters are removed before trimming")
    void sanitizeStripsControlCharacters() {
        assertEquals("abc", TitleRules.sanitize("a\u0000b\tc"));
        assertEquals("x", TitleRules.sanitize("\u0001  x  \u0002"));
        assertEquals("a b", TitleRules.sanitize("a\u0007 b"));
    }

    @Test
    @DisplayName("CJK titles count one code point per character and keep 10")
    void sanitizeCjkBoundary() {
        String ten = "一二三四五六七八九十";
        assertEquals(10, TitleRules.count(ten));
        assertEquals(ten, TitleRules.sanitize(ten));
        assertTrue(TitleRules.isValid(ten));

        String eleven = ten + "十一";
        assertEquals(ten, TitleRules.sanitize(eleven));
        assertEquals(12, TitleRules.count(eleven));
    }

    @Test
    @DisplayName("emoji surrogate pairs count as one code point")
    void sanitizeEmojiSurrogatePairs() {
        String single = "😀";
        assertEquals(1, TitleRules.count(single));
        assertEquals(1, TitleRules.sanitize(single).codePointCount(0, TitleRules.sanitize(single).length()));

        String twelve = single.repeat(12);
        String clamped = TitleRules.sanitize(twelve);
        assertEquals(10, TitleRules.count(clamped));
        assertEquals(single.repeat(10), clamped);
        // never split a surrogate pair
        assertFalse(Character.isHighSurrogate(clamped.charAt(clamped.length() - 1)));
    }

    @Test
    @DisplayName("mixed CJK + emoji + ASCII counts per code point")
    void sanitizeMixedCodePoints() {
        // 2 CJK + 1 emoji + 5 ASCII = 8 code points, 9 UTF-16 units
        String mixed = "测试😀12345";
        assertEquals(9, mixed.length());
        assertEquals(8, TitleRules.count(mixed));
        assertEquals(mixed, TitleRules.sanitize(mixed));

        String clamped = TitleRules.sanitize("测试😀123456789");
        assertEquals(10, TitleRules.count(clamped));
        assertEquals("测试😀1234567", clamped);
    }

    @Test
    @DisplayName("isValid rejects blank input only; over-long input is clamped, not rejected")
    void isValidBoundaries() {
        assertTrue(TitleRules.isValid("a"));
        assertTrue(TitleRules.isValid("  a  "));
        assertTrue(TitleRules.isValid("1234567890"));
        // Intended semantics: sanitize() clamps to 10 code points, so isValid() means "the
        // sanitised title is 1..10". The UI enforces the limit by clamping while typing
        // (EDIT_BOX_MAX_CHARS + listener), so no raw overflow ever reaches the model.
        assertEquals("1234567890", TitleRules.sanitize("12345678901"));
        assertTrue(TitleRules.isValid("12345678901"));
        assertTrue(TitleRules.isValid(TitleRules.sanitize("12345678901")));
        assertFalse(TitleRules.isValid(null));
        assertFalse(TitleRules.isValid(""));
        assertFalse(TitleRules.isValid("     "));
        assertFalse(TitleRules.isValid("\u0000\u0001"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "\u0000"})
    @DisplayName("blank/control-only titles are invalid")
    void blankTitlesAreInvalid(String raw) {
        assertFalse(TitleRules.isValid(raw));
        assertEquals(0, TitleRules.count(TitleRules.sanitize(raw)));
    }

    @Test
    @DisplayName("count handles null, empty and supplementary code points")
    void countBoundaries() {
        assertEquals(0, TitleRules.count(null));
        assertEquals(0, TitleRules.count(""));
        assertEquals(1, TitleRules.count("😀"));
        assertEquals(2, TitleRules.count("😀😀"));
        assertEquals(3, TitleRules.count("a😀b"));
    }

    @Test
    @DisplayName("MAX_CODE_POINTS is 10 and EDIT_BOX_MAX_CHARS leaves room for 10 emoji")
    void constants() {
        assertEquals(10, TitleRules.MAX_CODE_POINTS);
        assertTrue(TitleRules.EDIT_BOX_MAX_CHARS >= TitleRules.MAX_CODE_POINTS * 2);
    }
}
