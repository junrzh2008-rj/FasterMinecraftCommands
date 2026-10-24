package com.fmcc.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigStore: in-memory entry list + immediate atomic persistence to {@code fmcc.json}.
 * Everything is exercised against a real file in a JUnit {@link TempDir} so the same code path
 * runs as in the game.
 */
class ConfigStoreTest {
    @TempDir
    Path tempDir;

    private Path configPath() {
        return this.tempDir.resolve("fmcc.json");
    }

    private ConfigStore newStore() {
        return new ConfigStore(configPath());
    }

    private ConfigStore loadedStore() {
        ConfigStore store = newStore();
        store.load();
        return store;
    }

    @Test
    @DisplayName("missing file loads as an empty config")
    void missingFileLoadsEmpty() {
        ConfigStore store = loadedStore();
        assertEquals(0, store.size());
        assertTrue(store.entries().isEmpty());
        assertEquals(configPath(), store.file());
        assertFalse(Files.exists(configPath()));
    }

    @Test
    @DisplayName("adding a valid entry persists it immediately")
    void addEntryPersists() {
        ConfigStore store = loadedStore();
        Optional<FmccEntry> created = store.addEntry("  测试A  ");
        assertTrue(created.isPresent());
        assertEquals(1, store.size());
        assertNotNull(created.get().getId());
        assertEquals("测试A", created.get().getTitle().strip());
        assertEquals("测试A", created.get().getTitle());
        assertTrue(Files.exists(configPath()), "addEntry must save right away");
        assertEquals("测试A", store.byId(created.get().getId()).orElseThrow().getTitle());
    }

    @Test
    @DisplayName("invalid titles abort creation and persist nothing")
    void addEntryInvalidAborts() {
        ConfigStore store = loadedStore();
        assertTrue(store.addEntry(null).isEmpty());
        assertTrue(store.addEntry("").isEmpty());
        assertTrue(store.addEntry("    ").isEmpty());
        assertTrue(store.addEntry("\u0000\u0001").isEmpty());
        assertEquals(0, store.size());
        assertFalse(Files.exists(configPath()), "nothing to save means no file");
    }

    @Test
    @DisplayName("over-long titles are clamped to 10 code points")
    void addEntryClampsTitle() {
        ConfigStore store = loadedStore();
        FmccEntry entry = store.addEntry("一二三四五六七八九十十一").orElseThrow();
        assertEquals("一二三四五六七八九十", entry.getTitle());

        FmccEntry emoji = store.addEntry("😀".repeat(14)).orElseThrow();
        assertEquals(10, emoji.getTitle().codePointCount(0, emoji.getTitle().length()));
    }

    @Test
    @DisplayName("entries() is an immutable snapshot of display order")
    void entriesSnapshot() {
        ConfigStore store = loadedStore();
        store.addEntry("first");
        store.addEntry("second");
        List<FmccEntry> snapshot = store.entries();
        assertEquals(2, snapshot.size());
        assertEquals("first", snapshot.get(0).getTitle());
        assertEquals("second", snapshot.get(1).getTitle());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new FmccEntry("x", "x", List.of())));
        store.addEntry("third");
        assertEquals(2, snapshot.size(), "old snapshot must not grow");
        assertEquals(3, store.size());
    }

    @Test
    @DisplayName("byId handles null, miss and hit")
    void byId() {
        ConfigStore store = loadedStore();
        FmccEntry entry = store.addEntry("alpha").orElseThrow();
        assertTrue(store.byId(null).isEmpty());
        assertTrue(store.byId("does-not-exist").isEmpty());
        assertEquals(entry, store.byId(entry.getId()).orElseThrow());
    }

    @Test
    @DisplayName("removeEntry deletes by id and persists")
    void removeEntry() {
        ConfigStore store = loadedStore();
        FmccEntry first = store.addEntry("first").orElseThrow();
        FmccEntry second = store.addEntry("second").orElseThrow();

        assertTrue(store.removeEntry(first));
        assertEquals(1, store.size());
        assertEquals(second, store.entries().get(0));

        assertFalse(store.removeEntry(null));
        assertFalse(store.removeEntry(new FmccEntry("unknown-id", "ghost", List.of())));
        assertEquals(1, store.size());

        ConfigStore reloaded = loadedStore();
        assertEquals(1, reloaded.size());
        assertEquals("second", reloaded.entries().get(0).getTitle());
    }

    @Test
    @DisplayName("texts can be added, edited and removed; order is preserved")
    void textEditing() {
        ConfigStore store = loadedStore();
        FmccEntry entry = store.addEntry("entry").orElseThrow();

        assertEquals(0, store.addText(entry));
        assertEquals(1, store.addText(entry));
        assertEquals(List.of("", ""), entry.getTexts());

        store.setText(entry, 0, "/seed");
        store.setText(entry, 1, "hello");
        assertEquals(List.of("/seed", "hello"), entry.getTexts());

        // null is stored as empty, out-of-range indices are ignored
        store.setText(entry, 1, null);
        assertEquals("", entry.getTexts().get(1));
        store.setText(entry, 5, "nope");
        store.setText(entry, -1, "nope");
        assertEquals(2, entry.getTexts().size());

        store.setText(entry, 1, "hello");
        store.removeText(entry, 0);
        assertEquals(List.of("hello"), entry.getTexts());
        store.removeText(entry, 9);
        store.removeText(null, 0);
        assertEquals(List.of("hello"), entry.getTexts());
    }

    @Test
    @DisplayName("save + reload round trips entries and texts in order")
    void saveReloadRoundTrip() throws IOException {
        ConfigStore store = loadedStore();
        FmccEntry first = store.addEntry("第一条").orElseThrow();
        store.addText(first);
        store.addText(first);
        store.setText(first, 0, "/weather clear");
        store.setText(first, 1, "hello world");
        store.addEntry("second");

        // the file on disk is valid JSON with the expected content
        String json = Files.readString(configPath(), StandardCharsets.UTF_8);
        FmccConfig parsed = new Gson().fromJson(json, FmccConfig.class);
        assertNotNull(parsed);
        assertEquals(FmccConfig.CURRENT_VERSION, parsed.getVersion());
        assertEquals(2, parsed.getEntries().size());

        ConfigStore reloaded = loadedStore();
        assertEquals(2, reloaded.size());
        assertEquals("第一条", reloaded.entries().get(0).getTitle());
        assertEquals(List.of("/weather clear", "hello world"), reloaded.entries().get(0).getTexts());
        assertEquals("second", reloaded.entries().get(1).getTitle());
    }

    @Test
    @DisplayName("save leaves no .tmp file behind (atomic move)")
    void saveLeavesNoTempFile() throws IOException {
        ConfigStore store = loadedStore();
        store.addEntry("entry");
        assertTrue(store.save());
        Path tmp = configPath().resolveSibling(configPath().getFileName() + ".tmp");
        assertFalse(Files.exists(tmp), "atomic save must move the temp file into place");
        assertTrue(Files.exists(configPath()));
        try (Stream<Path> files = Files.list(this.tempDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    @DisplayName("save creates missing parent directories")
    void saveCreatesParentDirectories() {
        Path nested = this.tempDir.resolve("nested").resolve("dir").resolve("fmcc.json");
        ConfigStore store = new ConfigStore(nested);
        store.load();
        store.addEntry("kept");
        assertTrue(Files.exists(nested));
    }

    @Test
    @DisplayName("saving an empty store writes a valid versioned document")
    void saveEmptyStore() {
        ConfigStore store = loadedStore();
        assertTrue(store.save());
        assertTrue(Files.exists(configPath()));
        ConfigStore reloaded = loadedStore();
        assertEquals(0, reloaded.size());

        ConfigStore fresh = new ConfigStore(this.tempDir.resolve("fresh.json"));
        fresh.load();
        assertTrue(fresh.save());
        assertTrue(Files.exists(this.tempDir.resolve("fresh.json")));
    }

    @Test
    @DisplayName("corrupt JSON is backed up and the store starts empty")
    void corruptJsonRecovery() throws IOException {
        Files.writeString(configPath(), "this is { not json", StandardCharsets.UTF_8);
        ConfigStore store = loadedStore();

        assertEquals(0, store.size());
        assertFalse(Files.exists(configPath()), "broken file must be moved away");
        try (Stream<Path> files = Files.list(this.tempDir)) {
            List<Path> backups = files
                    .filter(p -> p.getFileName().toString().startsWith("fmcc.json.corrupt-"))
                    .toList();
            assertEquals(1, backups.size(), "exactly one backup file expected");
            assertEquals("this is { not json", Files.readString(backups.get(0), StandardCharsets.UTF_8));
        }

        // and the store is usable again
        store.addEntry("recovered");
        assertEquals(1, loadedStore().size());
    }

    @Test
    @DisplayName("JSON null root is treated as corrupt")
    void nullRootIsCorrupt() throws IOException {
        Files.writeString(configPath(), "null", StandardCharsets.UTF_8);
        ConfigStore store = loadedStore();
        assertEquals(0, store.size());
        assertFalse(Files.exists(configPath()));
    }

    @Test
    @DisplayName("malformed stored entries are sanitised on load")
    void malformedEntriesSanitised() throws IOException {
        String json = """
                {
                  "version": 1,
                  "entries": [
                    {"id": "", "title": "一二三四五六七八九十多余", "texts": ["  a  ", null, "", "  b "]},
                    {"title": "ok", "texts": []},
                    {"id": "fixed", "title": null, "texts": null}
                  ]
                }
                """;
        Files.writeString(configPath(), json, StandardCharsets.UTF_8);

        ConfigStore store = loadedStore();
        assertEquals(3, store.size());

        FmccEntry first = store.entries().get(0);
        assertNotNull(first.getId());
        assertFalse(first.getId().isBlank(), "blank id must be replaced");
        assertEquals("一二三四五六七八九十", first.getTitle(), "title clamped to 10 code points");
        assertEquals(List.of("a", "b"), first.getTexts(), "blank/null texts dropped and trimmed");

        FmccEntry second = store.entries().get(1);
        assertNotNull(second.getId());
        assertFalse(second.getId().isBlank());
        assertEquals("ok", second.getTitle());

        FmccEntry third = store.entries().get(2);
        assertEquals("fixed", third.getId());
        assertFalse(third.getTitle().isBlank(), "null title must be replaced by a fallback");
        assertTrue(third.getTexts().isEmpty());
    }

    @Test
    @DisplayName("a newer config version is tolerated and rewritten at the current version")
    void newerVersionTolerated() throws IOException {
        String json = """
                {"version": 999, "entries": [{"id": "e1", "title": "future", "texts": ["x"]}]}
                """;
        Files.writeString(configPath(), json, StandardCharsets.UTF_8);

        ConfigStore store = loadedStore();
        assertEquals(1, store.size());
        assertEquals("future", store.entries().get(0).getTitle());

        assertTrue(store.save());
        assertEquals(FmccConfig.CURRENT_VERSION,
                new Gson().fromJson(Files.readString(configPath()), FmccConfig.class).getVersion());
    }

    @Test
    @DisplayName("saveIfDirty writes only after a mutation")
    void saveIfDirty() throws IOException {
        ConfigStore store = loadedStore();
        store.saveIfDirty();
        assertFalse(Files.exists(configPath()), "clean store must not write");

        store.markDirty();
        assertTrue(store.isDirty());
        store.saveIfDirty();
        assertTrue(Files.exists(configPath()));
        assertFalse(store.isDirty());

        // mutations mark the store dirty and persist immediately, resetting the flag
        store.addEntry("dirty");
        assertFalse(store.isDirty());
    }

    @Test
    @DisplayName("removing the last entry leaves a valid empty document")
    void removeLastEntry() {
        ConfigStore store = loadedStore();
        FmccEntry entry = store.addEntry("only").orElseThrow();
        assertTrue(store.removeEntry(entry));
        assertEquals(0, store.size());
        assertEquals(0, loadedStore().size());
    }

    @Test
    @DisplayName("FmccConfig defaults are non-null and versioned")
    void configDefaults() {
        FmccConfig config = new FmccConfig();
        assertEquals(FmccConfig.CURRENT_VERSION, config.getVersion());
        assertNotNull(config.getEntries());
        assertTrue(config.getEntries().isEmpty());

        config.setVersion(7);
        config.setEntries(null);
        assertNotNull(config.getEntries());
        assertTrue(config.getEntries().isEmpty());
        assertNotEquals(999, config.getVersion());
    }
}
