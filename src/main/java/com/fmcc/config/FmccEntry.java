package com.fmcc.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One menu entry: a short title shown on the main button plus the ordered list of texts that are
 * sent when the button is pressed.
 */
public final class FmccEntry {
    private String id;
    private String title;
    private List<String> texts;

    /** Gson constructor. */
    @SuppressWarnings("unused")
    private FmccEntry() {
        this.texts = new ArrayList<>();
    }

    public FmccEntry(String id, String title, List<String> texts) {
        this.id = id;
        this.title = title;
        this.texts = new ArrayList<>(texts == null ? List.of() : texts);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /** Mutable live list of texts in send order. */
    public List<String> getTexts() {
        if (texts == null) {
            texts = new ArrayList<>();
        }
        return texts;
    }

    public void setTexts(List<String> texts) {
        this.texts = new ArrayList<>(texts == null ? List.of() : texts);
    }

    /** @return a snapshot of the non-blank texts, trimmed, in send order. */
    public List<String> sendableTexts() {
        List<String> out = new ArrayList<>();
        for (String text : getTexts()) {
            if (text != null) {
                String trimmed = text.strip();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    /** Copies the entry, deep-copying the mutable text list. */
    public FmccEntry copy() {
        return new FmccEntry(id, getTitle(), getTexts());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof FmccEntry other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
