package com.fmcc.config;

import java.util.ArrayList;
import java.util.List;

/** Serialised root of {@code config/fmcc.json}. */
public final class FmccConfig {
    /** Current schema version, bumped when the on-disk format changes incompatibly. */
    public static final int CURRENT_VERSION = 1;

    private int version = CURRENT_VERSION;
    private List<FmccEntry> entries = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<FmccEntry> getEntries() {
        if (entries == null) {
            entries = new ArrayList<>();
        }
        return entries;
    }

    public void setEntries(List<FmccEntry> entries) {
        this.entries = new ArrayList<>(entries == null ? List.of() : entries);
    }
}
