package com.fmcc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.fmcc.FmccClient;
import com.fmcc.util.TitleRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads and saves {@code config/fmcc.json}, keeping the in-memory list of entries.
 * <p>
 * All mutating operations persist immediately (the file is tiny) and every IO failure is logged
 * instead of thrown so the game never crashes because of a broken config.
 */
public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
    private final List<FmccEntry> entries = new ArrayList<>();
    private boolean dirty;

    public ConfigStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the config, falling back to an empty list on any problem. */
    public void load() {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            FmccConfig config = GSON.fromJson(json, FmccConfig.class);
            if (config == null) {
                throw new JsonParseException("empty config root");
            }
            if (config.getVersion() > FmccConfig.CURRENT_VERSION) {
                FmccClient.LOGGER.warn("FMCC config version {} is newer than supported version {}; "
                        + "loading it read-only anyway", config.getVersion(), FmccConfig.CURRENT_VERSION);
            }
            Set<String> seenIds = new HashSet<>();
            for (FmccEntry entry : config.getEntries()) {
                if (entry == null) {
                    // Hand edited / partially corrupt configs can contain null list elements.
                    continue;
                }
                sanitizeLoaded(entry, seenIds);
                entries.add(entry);
            }
            FmccClient.LOGGER.info("Loaded {} FMCC entries from {}", entries.size(), file);
        } catch (IOException | RuntimeException e) {
            backupBrokenFile(e);
            entries.clear();
        }
    }

    /** Writes the config atomically. Never throws; returns false when saving failed. */
    public boolean save() {
        Path parent = file.getParent();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FmccConfig config = new FmccConfig();
            config.setVersion(FmccConfig.CURRENT_VERSION);
            config.setEntries(new ArrayList<>(entries));
            String json = GSON.toJson(config);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            return true;
        } catch (IOException | RuntimeException e) {
            FmccClient.LOGGER.error("Could not save FMCC config to {}", file, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // nothing sensible to do here
            }
            return false;
        }
    }

    /** Saves only when something changed since the last successful save. */
    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Immutable snapshot of the entries, in display order. */
    public List<FmccEntry> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public Optional<FmccEntry> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (FmccEntry entry : entries) {
            if (id.equals(entry.getId())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Creates and stores a new entry.
     *
     * @param rawTitle title typed by the player; normalised through {@link TitleRules}
     * @return the created entry, or empty when the title is not valid (creation must abort)
     */
    public Optional<FmccEntry> addEntry(String rawTitle) {
        String title = TitleRules.sanitize(rawTitle);
        if (!TitleRules.isValid(title)) {
            return Optional.empty();
        }
        FmccEntry entry = new FmccEntry(UUID.randomUUID().toString(), title, List.of());
        entries.add(entry);
        dirty = true;
        save();
        return Optional.of(entry);
    }

    /** Deletes an entry (and therefore its texts). Returns true when something was removed. */
    public boolean removeEntry(FmccEntry entry) {
        if (entry == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            FmccEntry candidate = entries.get(i);
            boolean sameObject = candidate == entry;
            boolean sameId = candidate.getId() != null && candidate.getId().equals(entry.getId());
            if (sameObject || sameId) {
                entries.remove(i);
                dirty = true;
                save();
                return true;
            }
        }
        return false;
    }

    /** Deletes the text at {@code index} from {@code entry} and persists. */
    public void removeText(FmccEntry entry, int index) {
        if (entry == null) {
            return;
        }
        List<String> texts = entry.getTexts();
        if (index < 0 || index >= texts.size()) {
            return;
        }
        texts.remove(index);
        dirty = true;
        save();
    }

    /** Appends an empty text and persists; returns its index. */
    public int addText(FmccEntry entry) {
        entry.getTexts().add("");
        dirty = true;
        save();
        return entry.getTexts().size() - 1;
    }

    /** Replaces one text and persists. */
    public void setText(FmccEntry entry, int index, String value) {
        List<String> texts = entry.getTexts();
        if (index < 0 || index >= texts.size()) {
            return;
        }
        String cleaned = value == null ? "" : value;
        if (cleaned.equals(texts.get(index))) {
            return;
        }
        texts.set(index, cleaned);
        dirty = true;
        save();
    }

    private void sanitizeLoaded(FmccEntry entry, java.util.Set<String> seenIds) {
        if (entry == null) {
            return;
        }
        String id = entry.getId();
        if (id == null || id.isBlank() || !seenIds.add(id)) {
            // Missing or duplicated ids: regenerate so id lookups stay unambiguous.
            id = UUID.randomUUID().toString();
            seenIds.add(id);
        }
        entry.setId(id);
        String title = TitleRules.sanitize(entry.getTitle());
        if (!TitleRules.isValid(title)) {
            title = title.isEmpty() ? "Entry" : title;
        }
        entry.setTitle(title);
        // Hand edited configs can contain control characters that would make the chat packet be
        // rejected (and get the player kicked); strip them before they reach the send path.
        List<String> cleanedTexts = new ArrayList<>();
        for (String text : entry.sendableTexts()) {
            cleanedTexts.add(stripControlCharacters(text));
        }
        entry.setTexts(cleanedTexts);
    }

    private static String stripControlCharacters(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        raw.codePoints().filter(codePoint -> !Character.isISOControl(codePoint)).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    private void backupBrokenFile(Exception cause) {
        FmccClient.LOGGER.error("FMCC config at {} is unreadable; keeping a backup and starting empty", file, cause);
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + stamp);
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            FmccClient.LOGGER.warn("Broken FMCC config backed up to {}", backup);
        } catch (IOException e) {
            FmccClient.LOGGER.warn("Could not back up broken FMCC config", e);
        }
    }
}
