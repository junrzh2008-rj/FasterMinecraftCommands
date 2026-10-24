package com.fmcc.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MessageQueue: first text fires immediately, following texts are spaced exactly
 * {@link MessageQueue#TICKS_BETWEEN_MESSAGES} (15) client ticks apart, clicks never interleave.
 * <p>
 * Timing is driven through a small fake game loop: {@code enqueue()} happens during one tick and
 * {@code tick()} runs at the end of every tick, which is exactly how FmccClient drives it.
 */
class MessageQueueTest {
    /** Records the tick at which each text was delivered. */
    private static final class Sink implements MessageQueue.Sink {
        final List<String> sent = new ArrayList<>();
        final List<Integer> sentAtTick = new ArrayList<>();
        int tick;
        boolean failNext;
        boolean throwNext;

        @Override
        public boolean send(String text) {
            if (this.throwNext) {
                this.throwNext = false;
                throw new IllegalStateException("sink exploded");
            }
            if (this.failNext) {
                this.failNext = false;
                return false;
            }
            this.sent.add(text);
            this.sentAtTick.add(this.tick);
            return true;
        }
    }

    private final Sink sink = new Sink();
    private final MessageQueue queue = new MessageQueue(this.sink);

    /** Advances the fake client one tick: end-of-tick queue processing. */
    private void tick() {
        this.queue.tick();
        this.sink.tick++;
    }

    @Test
    @DisplayName("the first text is sent immediately on enqueue")
    void firstTextImmediate() {
        int queued = this.queue.enqueue(List.of("a", "b", "c"));
        assertEquals(3, queued);
        assertEquals(List.of("a"), this.sink.sent);
        assertEquals(0, this.sink.sentAtTick.get(0));
        assertEquals(2, this.queue.queuedCount());
        assertEquals(1, this.queue.sentCount());
        assertTrue(this.queue.isBusy());
    }

    @Test
    @DisplayName("queued texts are 15 ticks apart (0.75 s at 20 tps)")
    void fifteenTicksBetweenTexts() {
        this.queue.enqueue(List.of("a", "b", "c")); // a at tick 0
        for (int i = 0; i < 40; i++) {
            tick();
        }
        assertEquals(List.of("a", "b", "c"), this.sink.sent);
        assertEquals(List.of(0, 15, 30), this.sink.sentAtTick);

        // and nothing is sent early. The click happens inside the first simulated tick, whose
        // end-of-tick queue processing is the first tick() call, so "15 ticks after the click"
        // means the 16th tick() call (sink tick 15) for the first follow-up message.
        Sink early = new Sink();
        MessageQueue earlyQueue = new MessageQueue(early);
        earlyQueue.enqueue(List.of("a", "b"));
        for (int i = 0; i < 15; i++) {
            earlyQueue.tick();
        }
        assertEquals(List.of("a"), early.sent, "second text must not appear before 15 ticks elapsed");

        earlyQueue.tick(); // 15 ticks after the click
        assertEquals(List.of("a", "b"), early.sent, "second text must appear on the 15th tick after the click");
    }

    @Test
    @DisplayName("4 messages are sent at exactly [0, 15, 30, 45] (regression: 16-tick off-by-one)")
    void fourMessagesAreExactlyFifteenTicksApart() {
        int queued = this.queue.enqueue(List.of("a", "b", "c", "d"));
        assertEquals(4, queued);
        for (int i = 0; i < 50; i++) {
            tick();
        }
        assertEquals(List.of("a", "b", "c", "d"), this.sink.sent);
        assertEquals(List.of(0, 15, 30, 45), this.sink.sentAtTick,
                "every subsequent message must be 15 ticks after the previous one, never 16");
    }

    @Test
    @DisplayName("two fast clicks are sent in FIFO order, never interleaved")
    void multiClickFifo() {
        this.queue.enqueue(List.of("a1", "a2")); // tick 0: a1 immediate, a2 at tick 15
        for (int i = 0; i < 5; i++) {
            tick();
        }
        this.queue.enqueue(List.of("b1", "b2")); // tick 5: appended behind a2
        for (int i = 0; i < 50; i++) {
            tick();
        }
        assertEquals(List.of("a1", "a2", "b1", "b2"), this.sink.sent);
        assertEquals(List.of(0, 15, 30, 45), this.sink.sentAtTick);
    }

    @Test
    @DisplayName("blank, null and empty inputs are skipped")
    void blankTextsSkipped() {
        assertEquals(0, this.queue.enqueue(null));
        assertEquals(0, this.queue.enqueue(List.of()));
        assertEquals(0, this.queue.enqueue(Arrays.asList(null, "", "   ", "\t\n")));
        assertTrue(this.sink.sent.isEmpty());
        assertFalse(this.queue.isBusy());

        assertEquals(2, this.queue.enqueue(Arrays.asList("  a  ", null, " b ")));
        assertEquals(List.of("a"), this.sink.sent);
        assertEquals(1, this.queue.queuedCount());
    }

    @Test
    @DisplayName("a failing sink aborts the whole pending sequence")
    void sinkFailureAborts() {
        this.queue.enqueue(List.of("a", "b", "c"));
        assertEquals(List.of("a"), this.sink.sent);

        this.sink.failNext = true; // "b" fails on the 15th tick
        for (int i = 0; i < 20; i++) {
            tick();
        }
        assertEquals(List.of("a"), this.sink.sent);
        assertFalse(this.queue.isBusy());
        assertEquals(0, this.queue.queuedCount());
        assertEquals(1, this.queue.sentCount());

        // the queue still works afterwards
        this.queue.enqueue(List.of("d"));
        assertEquals(List.of("a", "d"), this.sink.sent);
    }

    @Test
    @DisplayName("a first-text failure reports zero queued texts")
    void firstSinkFailure() {
        this.sink.failNext = true;
        assertEquals(0, this.queue.enqueue(List.of("a", "b")));
        assertTrue(this.sink.sent.isEmpty());
        assertFalse(this.queue.isBusy());
    }

    @Test
    @DisplayName("a throwing sink is caught and aborts instead of crashing the game")
    void sinkExceptionAborts() {
        this.sink.throwNext = true;
        assertEquals(0, this.queue.enqueue(List.of("a", "b")));
        assertTrue(this.sink.sent.isEmpty());
        assertFalse(this.queue.isBusy());
        assertEquals(0, this.queue.sentCount());
    }

    @Test
    @DisplayName("clear() drops pending texts immediately")
    void clearDropsPending() {
        this.queue.enqueue(List.of("a", "b", "c"));
        assertEquals(2, this.queue.queuedCount());
        this.queue.clear();
        assertFalse(this.queue.isBusy());
        assertEquals(0, this.queue.queuedCount());

        for (int i = 0; i < 40; i++) {
            tick();
        }
        assertEquals(List.of("a"), this.sink.sent, "cleared texts must never be sent");
    }

    @Test
    @DisplayName("a single text becomes idle again after the cooldown")
    void singleTextBecomesIdle() {
        this.queue.enqueue(List.of("only"));
        assertEquals(1, this.sink.sent.size());
        assertTrue(this.queue.isBusy(), "busy while the cooldown runs so clicks append in order");
        for (int i = 0; i < 20; i++) {
            tick();
        }
        assertFalse(this.queue.isBusy());
        assertEquals(0, this.queue.queuedCount());
        assertEquals(1, this.queue.sentCount());
    }

    @Test
    @DisplayName("sink receives trimmed text")
    void trimsText() {
        this.queue.enqueue(List.of("   /seed   "));
        assertEquals(List.of("/seed"), this.sink.sent);
    }

    @Test
    @DisplayName("tick() while idle is a cheap no-op")
    void idleTickNoOp() {
        for (int i = 0; i < 100; i++) {
            tick();
        }
        assertTrue(this.sink.sent.isEmpty());
        assertEquals(0, this.queue.sentCount());
        assertFalse(this.queue.isBusy());
    }

    @Test
    @DisplayName("sentCount only counts real deliveries")
    void sentCountOnlySuccessful() {
        AtomicInteger calls = new AtomicInteger();
        MessageQueue counting = new MessageQueue(text -> {
            calls.incrementAndGet();
            return false;
        });
        counting.enqueue(List.of("a"));
        assertEquals(1, calls.get());
        assertEquals(0, counting.sentCount());
    }
}
