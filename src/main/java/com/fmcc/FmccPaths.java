package com.fmcc;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Resolves where FMCC keeps its data. */
public final class FmccPaths {
    /** Test/automation override: {@code -Dfmcc.config.path=<file>}. */
    public static final String CONFIG_OVERRIDE = "fmcc.config.path";

    private FmccPaths() {
    }

    public static Path configFile() {
        String override = System.getProperty(CONFIG_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return FabricLoader.getInstance().getConfigDir().resolve("fmcc.json");
    }
}
