package com.fmcc.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * Sends the texts of one entry top to bottom with a fixed 0.75 s (15 client ticks) gap.
 * <p>
 * The first text of a click is sent immediately; each following text is released by
 * {@link #tick()}. Multiple clicks are queued in order, so two fast clicks never interleave.
 * The class is deliberately free of Minecraft types so it can be unit tested.
 */
public final class MessageQueue {
    /** 0.75 s at the vanilla 20 ticks per second. */
    public static final int TICKS_BETWEEN_MESSAGES = 15;

    /** Delivery callback. Returning false aborts the whole pending sequence. */
    @FunctionalInterface
    public interface Sink {
        boolean send(String text);
    }

    private final Sink sink;
    private final Deque<String> pending = new ArrayDeque<>();
    private int cooldown;
    private boolean active;
    private int sentCount;

    public MessageQueue(Sink sink) {
        this.sink = sink;
    }

    /**
     * Appends texts to the send queue. Blank texts are ignored. When the queue is idle the first
     * text is delivered right away, matching "click a button and it sends".
     *
     * @return number of texts that entered the queue
     */
    public int enqueue(Collection<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        List<String> cleaned = new ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                cleaned.add(text.strip());
            }
        }
        if (cleaned.isEmpty()) {
            return 0;
        }
        int queued = 0;
        if (!active) {
            active = true;
            cooldown = TICKS_BETWEEN_MESSAGES;
            if (deliver(cleaned.remove(0))) {
                queued++;
            } else {
                clear();
                return 0;
            }
        }
        if (!cleaned.isEmpty()) {
            pending.addAll(cleaned);
            queued += cleaned.size();
        }
        return queued;
    }

    /** Advances the queue by one client tick. Cheap no-op while idle. */
    public void tick() {
        if (!active) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (pending.isEmpty()) {
            active = false;
            cooldown = 0;
            return;
        }
        String next = pending.poll();
        if (!deliver(next)) {
            clear();
            return;
        }
        // tick() already burned this tick, so one less tick keeps the gap at exactly 15 ticks.
        cooldown = TICKS_BETWEEN_MESSAGES - 1;
    }

    /** Drops everything that is still pending (used on disconnect / world change). */
    public void clear() {
        pending.clear();
        cooldown = 0;
        active = false;
    }

    public boolean isBusy() {
        return active;
    }

    public int queuedCount() {
        return pending.size();
    }

    /** Total texts handed to the sink since the queue was created (diagnostics / self-test). */
    public int sentCount() {
        return sentCount;
    }

    private boolean deliver(String text) {
        try {
            boolean delivered = sink.send(text);
            if (delivered) {
                sentCount++;
            }
            return delivered;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
