package com.fmcc.selftest;

import com.fmcc.FmccClient;
import com.fmcc.config.ConfigStore;
import com.fmcc.config.FmccEntry;
import com.fmcc.ui.AddEntryScreen;
import com.fmcc.ui.EntryConfigScreen;
import com.fmcc.ui.FmccScreen;
import com.fmcc.ui.UiLayout;
import com.fmcc.ui.UiTheme;
import com.fmcc.util.TitleRules;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-game automated self test, armed with {@code -Dfmcc.selftest=true}.
 * <p>
 * The harness runs a scripted state machine from {@link #tick(MinecraftClient)} (called at the end
 * of every client tick by {@link FmccClient}) and drives the <b>real</b> UI: every button press is
 * {@code Screen.mouseClicked(x, y, 0)} at the centre of a widget found among the current screen's
 * children, and every title/text is typed through {@link TextFieldWidget#charTyped(char, int)} when
 * possible. Between steps it waits a few ticks so rendering settles and the GPU screenshot readback
 * can complete.
 * <p>
 * Verified scenario: open the menu, empty-state and row geometry, "+" cancel creates nothing,
 * title validation, creating two entries, deleting one, configuring texts (add fields, type, delete
 * a field), returning to the menu, pressing the send button (queue assertion when a player is in a
 * world, skipped on the title screen where {@code ChatDelivery} cannot send), deleting the entry
 * and checking that the store on disk contains nothing left over. A JSON report with per-step
 * results, geometry checks and screenshot references is written to
 * {@code <gameDir>/fmcc-selftest-report.json}, then the client quits itself.
 * <p>
 * All steps are wrapped: a failure is recorded and still produces a report + client stop, so the
 * harness can never crash the game. OWNER: tester.
 */
public final class SelfTest {
    /** {@code -Dfmcc.selftest=true} arms the harness. */
    public static final String ENABLE_PROPERTY = "fmcc.selftest";
    /** Report file name, relative to the Minecraft run directory. */
    public static final String REPORT_FILE_NAME = "fmcc-selftest-report.json";

    private static final String PREFIX = "[FMCC-SELFTEST] ";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern PNG_NAME = Pattern.compile("([A-Za-z0-9_.\\-]+\\.png)");

    /** Ticks to wait after every step so the frame (and the screenshot readback) settles. */
    private static final int SETTLE_TICKS = 3;
    /** How long (ticks) to wait for the client to reach the title screen / a world. */
    private static final int READY_TIMEOUT_TICKS = 20 * 90;
    /** Ticks to wait for the accessibility onboarding screen to fade out after close(). */
    private static final int ONBOARDING_FADE_TICKS = 100;
    /** Hard limit (ticks) for the scripted scenario itself (well under a minute in practice). */
    private static final int SCENARIO_TIMEOUT_TICKS = 20 * 60;
    /** Ticks to wait for outstanding screenshot callbacks before the report is written. */
    private static final int FINISH_GRACE_TICKS = 80;
    /** Default per-step poll limit. */
    private static final int DEFAULT_STEP_TIMEOUT = 600;

    private static final String TEXT_COMMAND = "/seed";
    private static final String TEXT_CHAT = "hello fmcc";
    /**
     * A normal FMCC menu frame is well above 95 KB; a much smaller PNG means the frame was a splash
     * logo / black transition frame, so the harness warns loudly when a screenshot looks wrong.
     */
    private static final long MIN_EXPECTED_SCREENSHOT_BYTES = 40_000L;

    // ------------------------------------------------------------------ state

    private interface StepAction {
        /** @return true when the step is done, false to poll again on the next tick. */
        boolean run(MinecraftClient client) throws Exception;
    }

    private static final class Step {
        private final String id;
        private final StepAction action;
        private final int timeoutTicks;
        private int attempts;

        private Step(String id, StepAction action, int timeoutTicks) {
            this.id = id;
            this.action = action;
            this.timeoutTicks = timeoutTicks;
        }
    }

    private static final class StepResult {
        private final String id;
        private final String status;
        private final String message;
        private final int tick;

        private StepResult(String id, String status, String message, int tick) {
            this.id = id;
            this.status = status;
            this.message = message;
            this.tick = tick;
        }
    }

    /** Thrown by a step that is intentionally not applicable in this environment. */
    private static final class SkipStep extends RuntimeException {
        private SkipStep(String message) {
            super(message);
        }
    }

    private static final List<Step> STEPS = buildSteps();
    private static final List<StepResult> RESULTS = new ArrayList<>();
    private static final List<JsonObject> GEOMETRY = new ArrayList<>();
    private static final List<JsonObject> SCREENSHOTS = new ArrayList<>();
    private static final List<String> GEOMETRY_FAILURES = new ArrayList<>();
    private static final AtomicInteger PENDING_SCREENSHOTS = new AtomicInteger();

    private static MinecraftClient client;
    private static int ticks;
    private static int stepIndex;
    private static int settleTicks;
    private static int readyTick = -1;
    private static int finishWaitTicks;
    private static boolean finishing;
    private static boolean finishPass;
    private static boolean reportWritten;
    private static boolean quitRequested;
    private static String firstFailureId;
    private static String fatal;
    private static String stepMessage = "";
    private static int lastScreenWidth;
    private static int lastScreenHeight;
    /** Vanilla's first-launch accessibility onboarding must be dismissed before the title screen. */
    private static boolean onboardingClosed;
    private static int onboardingCloseTick = -1;
    /** Countdown of the extra settle applied after the vanilla splash overlay disappears. */
    private static int splashSettleTicks = -1;
    /** Tick at which the send button was clicked (the in-world screenshot is taken ~1 s later). */
    private static int sendClickTick = -1;
    /** Retry/wait state for the real-key end-to-end checks (requirement 2 + ESC). */
    private static int hotkeyAttempts;
    private static int hotkeyWaitTicks = -1;
    /** Retry/wait state for closing the menu by pressing the hotkey again. */
    private static int hotkeyCloseAttempts;
    private static int hotkeyCloseWaitTicks = -1;
    private static int escAttempts;
    private static int escWaitTicks = -1;
    /** True once ESC has been injected, so "menu closed" can be distinguished from "not started". */
    private static boolean escSent;
    /** True when the ESC check had to open an FmccScreen programmatically first. */
    private static boolean escOpenedProgrammatically;

    // scenario state
    private static int baselineEntries;
    private static String entryAId;
    private static String entryBId;
    private static String titleA = "FMCC self test A";
    private static String titleB = "FMCC self test B";
    private static int sentCountBeforeSend = -1;
    private static String startedAt;

    /** Cached once: the harness flag cannot change at runtime and this is queried every tick. */
    private static final boolean ENABLED = Boolean.getBoolean(ENABLE_PROPERTY);

    private SelfTest() {
    }

    /** @return true when the harness is armed with {@code -Dfmcc.selftest=true}. */
    public static boolean enabled() {
        return ENABLED;
    }

    // ==================================================================== entry

    /** Called once from the client initialiser when the harness is armed. */
    public static void init() {
        if (!enabled()) {
            return;
        }
        ticks = 0;
        stepIndex = 0;
        settleTicks = 0;
        readyTick = -1;
        finishWaitTicks = 0;
        finishing = false;
        finishPass = false;
        reportWritten = false;
        quitRequested = false;
        firstFailureId = null;
        fatal = null;
        stepMessage = "";
        baselineEntries = 0;
        entryAId = null;
        entryBId = null;
        sentCountBeforeSend = -1;
        onboardingClosed = false;
        onboardingCloseTick = -1;
        splashSettleTicks = -1;
        sendClickTick = -1;
        hotkeyAttempts = 0;
        hotkeyWaitTicks = -1;
        hotkeyCloseAttempts = 0;
        hotkeyCloseWaitTicks = -1;
        escAttempts = 0;
        escWaitTicks = -1;
        escSent = false;
        escOpenedProgrammatically = false;
        startedAt = OffsetDateTime.now().toString();
        RESULTS.clear();
        GEOMETRY.clear();
        SCREENSHOTS.clear();
        GEOMETRY_FAILURES.clear();
        PENDING_SCREENSHOTS.set(0);
        for (Step step : STEPS) {
            step.attempts = 0;
        }
        // Unique, short titles (<= 10 code points) so the run also works with a non-empty config.
        String suffix = Integer.toHexString((int) (System.currentTimeMillis() & 0xFFF));
        titleA = TitleRules.sanitize("FMCC测A" + suffix);
        titleB = TitleRules.sanitize("FMCC测B" + suffix);
        log("armed with " + ENABLE_PROPERTY + "=true; titles are '" + titleA + "' / '" + titleB
                + "'; report goes to <gameDir>/" + REPORT_FILE_NAME);
    }

    /** Called at the end of every client tick by {@link FmccClient}. */
    public static void tick(MinecraftClient minecraftClient) {
        if (!enabled() || minecraftClient == null) {
            return;
        }
        client = minecraftClient;
        try {
            tickInternal(minecraftClient);
        } catch (Throwable throwable) {
            logError("unexpected harness error: " + describe(throwable), throwable);
            if (!finishing) {
                RESULTS.add(new StepResult("harness-crash", "fail", describe(throwable), ticks));
                if (firstFailureId == null) {
                    firstFailureId = "harness-crash";
                }
                fatal = describe(throwable);
                requestFinish(minecraftClient, false);
            }
        }
    }

    // ================================================================ main loop

    private static void tickInternal(MinecraftClient mc) {
        ticks++;
        if (mc.currentScreen != null) {
            lastScreenWidth = mc.currentScreen.width;
            lastScreenHeight = mc.currentScreen.height;
        }
        if (finishing) {
            runFinish(mc);
            return;
        }
        if (readyTick < 0 && ticks > READY_TIMEOUT_TICKS) {
            RESULTS.add(new StepResult("wait-for-client", "fail",
                    "client did not reach the title screen or a world within "
                            + (READY_TIMEOUT_TICKS / 20) + " s (screen=" + screenName(mc.currentScreen) + ")",
                    ticks));
            firstFailureId = "wait-for-client";
            requestFinish(mc, false);
            return;
        }
        if (readyTick >= 0 && ticks - readyTick > SCENARIO_TIMEOUT_TICKS) {
            RESULTS.add(new StepResult("scenario-hard-timeout", "fail",
                    "scenario exceeded " + (SCENARIO_TIMEOUT_TICKS / 20) + " s at step "
                            + currentStepId() + " (settle=" + settleTicks + ")", ticks));
            if (firstFailureId == null) {
                firstFailureId = "scenario-hard-timeout";
            }
            requestFinish(mc, false);
            return;
        }
        if (settleTicks > 0) {
            settleTicks--;
            return;
        }
        if (stepIndex >= STEPS.size()) {
            requestFinish(mc, overallPass());
            return;
        }
        Step step = STEPS.get(stepIndex);
        try {
            stepMessage = "";
            if (step.action.run(mc)) {
                recordPass(step);
                stepIndex++;
                settleTicks = SETTLE_TICKS;
                if (stepIndex >= STEPS.size()) {
                    requestFinish(mc, overallPass());
                }
                return;
            }
            step.attempts++;
            if (step.attempts > step.timeoutTicks) {
                recordFail(step, "timed out after " + step.attempts + " ticks");
                requestFinish(mc, false);
            }
        } catch (SkipStep skip) {
            recordSkip(step, skip.getMessage());
            stepIndex++;
            settleTicks = SETTLE_TICKS;
            if (stepIndex >= STEPS.size()) {
                requestFinish(mc, overallPass());
            }
        } catch (Throwable throwable) {
            recordFail(step, describe(throwable));
            requestFinish(mc, false);
        }
    }

    private static void recordPass(Step step) {
        String message = stepMessage == null || stepMessage.isBlank() ? "ok" : stepMessage;
        RESULTS.add(new StepResult(step.id, "pass", message, ticks));
        log("PASS " + step.id + ": " + message);
    }

    private static void recordFail(Step step, String message) {
        String detail = message;
        if (stepMessage != null && !stepMessage.isBlank()) {
            detail = message + " (last: " + stepMessage + ")";
        }
        RESULTS.add(new StepResult(step.id, "fail", detail, ticks));
        if (firstFailureId == null) {
            firstFailureId = step.id;
        }
        logError("FAIL " + step.id + ": " + detail, null);
    }

    private static void recordSkip(Step step, String message) {
        String detail = message == null ? "skipped" : message;
        RESULTS.add(new StepResult(step.id, "skipped", detail, ticks));
        log("SKIP " + step.id + ": " + detail);
    }

    private static boolean overallPass() {
        return firstFailureId == null
                && RESULTS.stream().noneMatch(result -> "fail".equals(result.status));
    }

    private static String currentStepId() {
        return stepIndex < STEPS.size() ? STEPS.get(stepIndex).id : "<done>";
    }

    // ================================================================ finish/report

    private static void requestFinish(MinecraftClient mc, boolean pass) {
        if (finishing) {
            return;
        }
        finishing = true;
        finishPass = pass;
        finishWaitTicks = 0;
        log("scenario finished: " + (pass ? "PASS" : "FAIL")
                + " (steps=" + RESULTS.size() + ", pending screenshots=" + PENDING_SCREENSHOTS.get() + ")");
    }

    private static void runFinish(MinecraftClient mc) {
        finishWaitTicks++;
        if (!reportWritten) {
            if (PENDING_SCREENSHOTS.get() <= 0 || finishWaitTicks > FINISH_GRACE_TICKS) {
                writeReport(mc);
                reportWritten = true;
            }
        }
        if (reportWritten && !quitRequested) {
            quitRequested = true;
            try {
                File report = reportFile(mc);
                log("RESULT=" + (finishPass ? "PASS" : "FAIL") + " report=" + report.getAbsolutePath());
                mc.scheduleStop();
            } catch (Throwable throwable) {
                logError("could not stop the client cleanly: " + describe(throwable), throwable);
            }
        }
    }

    private static File reportFile(MinecraftClient mc) {
        if (mc.runDirectory == null) {
            return new File(REPORT_FILE_NAME).getAbsoluteFile();
        }
        return new File(mc.runDirectory, REPORT_FILE_NAME);
    }

    private static void writeReport(MinecraftClient mc) {
        try {
            long passed = RESULTS.stream().filter(result -> "pass".equals(result.status)).count();
            long failed = RESULTS.stream().filter(result -> "fail".equals(result.status)).count();
            long skipped = RESULTS.stream().filter(result -> "skipped".equals(result.status)).count();

            JsonObject root = new JsonObject();
            root.addProperty("selftest", true);
            root.addProperty("mod", "FasterMinecraftCommands (FMCC)");
            root.addProperty("result", finishPass ? "PASS" : "FAIL");
            root.addProperty("startedAt", startedAt);
            root.addProperty("finishedAt", OffsetDateTime.now().toString());
            root.addProperty("durationTicks", ticks);

            JsonObject environment = new JsonObject();
            environment.addProperty("minecraftVersion", gameVersionName(mc));
            environment.addProperty("versionType", safeVersionType(mc));
            environment.addProperty("runDirectory", mc.runDirectory == null ? "" : mc.runDirectory.getAbsolutePath());
            ConfigStore store = FmccClient.store();
            environment.addProperty("configFile", store == null ? "" : store.file().toString());
            environment.addProperty("configEntriesAtStart", baselineEntries);
            environment.addProperty("configEntriesNow", store == null ? -1 : store.size());
            environment.addProperty("playerInWorld", mc.player != null);
            environment.addProperty("currentScreen", screenName(mc.currentScreen));
            environment.addProperty("screenWidth", lastScreenWidth);
            environment.addProperty("screenHeight", lastScreenHeight);
            root.add("environment", environment);

            JsonObject summary = new JsonObject();
            summary.addProperty("total", RESULTS.size());
            summary.addProperty("passed", passed);
            summary.addProperty("failed", failed);
            summary.addProperty("skipped", skipped);
            summary.addProperty("plannedSteps", STEPS.size());
            root.add("summary", summary);
            root.addProperty("aborted", !finishPass);
            root.addProperty("failedStep", firstFailureId == null ? "" : firstFailureId);
            if (fatal != null) {
                root.addProperty("fatal", fatal);
            }

            JsonArray steps = new JsonArray();
            for (StepResult result : RESULTS) {
                JsonObject step = new JsonObject();
                step.addProperty("id", result.id);
                step.addProperty("status", result.status);
                step.addProperty("message", result.message == null ? "" : result.message);
                step.addProperty("tick", result.tick);
                steps.add(step);
            }
            root.add("steps", steps);

            JsonArray geometry = new JsonArray();
            for (JsonObject check : GEOMETRY) {
                geometry.add(check);
            }
            root.add("geometry", geometry);

            JsonArray screenshots = new JsonArray();
            for (JsonObject screenshot : SCREENSHOTS) {
                screenshots.add(screenshot);
            }
            root.add("screenshots", screenshots);

            JsonArray onDisk = new JsonArray();
            for (String name : listScreenshots(mc)) {
                onDisk.add(name);
            }
            root.addProperty("screenshotsDirectory",
                    new File(mc.runDirectory, "screenshots").getAbsolutePath());
            root.add("screenshotsOnDisk", onDisk);

            Path path = reportFile(mc).toPath();
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
            log("report written to " + path.toAbsolutePath() + " (" + RESULTS.size() + " steps, "
                    + passed + " passed, " + failed + " failed, " + skipped + " skipped)");
        } catch (Throwable throwable) {
            logError("could not write the report: " + describe(throwable), throwable);
        }
    }

    private static String gameVersionName(MinecraftClient mc) {
        try {
            return SharedConstants.getGameVersion().name();
        } catch (Throwable throwable) {
            return safeVersion(mc);
        }
    }

    private static String safeVersionType(MinecraftClient mc) {
        try {
            return mc.getVersionType();
        } catch (Throwable throwable) {
            return "unknown";
        }
    }

    private static String safeVersion(MinecraftClient mc) {
        try {
            return mc.getGameVersion();
        } catch (Throwable throwable) {
            return "unknown";
        }
    }

    private static List<String> listScreenshots(MinecraftClient mc) {
        List<String> names = new ArrayList<>();
        if (mc.runDirectory == null) {
            return names;
        }
        File[] files = new File(mc.runDirectory, "screenshots").listFiles(
                (dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
        if (files == null) {
            return names;
        }
        for (File file : files) {
            names.add(file.getName() + " (" + file.length() + " bytes)");
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // ================================================================== steps

    private static List<Step> buildSteps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("wait-for-client", SelfTest::waitForClient, READY_TIMEOUT_TICKS));
        steps.add(new Step("wait-for-splash-fade", SelfTest::waitForSplashFade, DEFAULT_STEP_TIMEOUT));
        // Requirement 2 + ESC: real key press opens the menu, ESC closes it (end-to-end key path).
        steps.add(new Step("assert-hotkey-binding", SelfTest::assertHotkeyBinding, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("hotkey-opens-menu", SelfTest::hotkeyOpensMenu, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("hotkey-closes-menu", SelfTest::hotkeyClosesMenu, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("esc-closes-menu", SelfTest::escClosesMenu, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("open-main-menu", SelfTest::openMainMenu, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-main-menu", SelfTest::waitForMainMenu, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("assert-empty-geometry", SelfTest::assertEmptyGeometry, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-main-empty", mc -> screenshot(mc, "main-empty"), DEFAULT_STEP_TIMEOUT));
        // Cancel path: "+" -> Cancel must not create anything.
        steps.add(new Step("click-add-button-for-cancel", mc -> clickAddButton(mc, "add (cancel path)"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-add-screen", SelfTest::waitForAddScreen, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-cancel", SelfTest::clickCancel, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-menu-after-cancel", SelfTest::waitForMenuAfterCancel, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-main-after-cancel", mc -> screenshot(mc, "main-after-cancel"), DEFAULT_STEP_TIMEOUT));
        // Entry A: create through the real dialog.
        steps.add(new Step("click-add-button", mc -> clickAddButton(mc, "add"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-add-screen-a", SelfTest::waitForAddScreen, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("type-title-a", mc -> typeTitle(mc, titleA), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-create-a", SelfTest::clickCreate, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-menu-with-entry-a", SelfTest::waitForEntryA, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-main-one-entry", mc -> screenshot(mc, "main-one-entry"), DEFAULT_STEP_TIMEOUT));
        // Entry B: makes the row-layout assertions meaningful.
        steps.add(new Step("click-add-button-b", mc -> clickAddButton(mc, "add (second entry)"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-add-screen-b", SelfTest::waitForAddScreen, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("type-title-b", mc -> typeTitle(mc, titleB), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-create-b", SelfTest::clickCreate, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-menu-with-entry-b", SelfTest::waitForEntryB, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-main-two-entries", mc -> screenshot(mc, "main-two-entries"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("assert-row-geometry", SelfTest::assertRowGeometry, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("delete-entry-b", SelfTest::deleteEntryB, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-entry-b-deleted", SelfTest::waitForEntryBDeleted, DEFAULT_STEP_TIMEOUT));
        // Configure entry A.
        steps.add(new Step("click-configure-a", SelfTest::clickConfigureA, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-config-screen", SelfTest::waitForConfigScreen, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-config-add-1", mc -> clickConfigAdd(mc), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-1-text", SelfTest::waitForOneText, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-config-add-2", mc -> clickConfigAdd(mc), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-2-texts", SelfTest::waitForTwoTexts, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("type-texts", SelfTest::typeTexts, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-text-values", SelfTest::waitForTextValues, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-config-two-texts", mc -> screenshot(mc, "config-two-texts"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("delete-second-text", SelfTest::deleteSecondText, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-text-shrink", SelfTest::waitForTextShrink, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-config-after-delete", mc -> screenshot(mc, "config-after-delete"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("click-back", SelfTest::clickBack, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-menu-return", SelfTest::waitForMenuReturn, DEFAULT_STEP_TIMEOUT));
        // Send through the main row button. On the title screen ChatDelivery cannot send, so the
        // queue assertions are recorded as skipped; in a world the queue is verified end to end.
        steps.add(new Step("click-send-a", SelfTest::clickSendA, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("verify-send-queued", SelfTest::verifySendQueued, 100));
        steps.add(new Step("wait-queue-drain", SelfTest::waitQueueDrain, 60));
        steps.add(new Step("screenshot-after-send", SelfTest::screenshotAfterSend, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("delete-entry-a", SelfTest::deleteEntryA, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("wait-for-entry-a-deleted", SelfTest::waitForEntryADeleted, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("screenshot-main-empty-after-delete", mc -> screenshot(mc, "main-empty-after-delete"), DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("verify-store-file", SelfTest::verifyStoreFile, DEFAULT_STEP_TIMEOUT));
        steps.add(new Step("scenario-complete", SelfTest::scenarioComplete, DEFAULT_STEP_TIMEOUT));
        return steps;
    }

    // ---- step implementations -------------------------------------------

    private static boolean waitForClient(MinecraftClient mc) {
        Screen current = mc.currentScreen;

        // Vanilla's first-launch accessibility onboarding blocks the title screen. Dismiss it the
        // same way its "Continue" button does: close() starts the fade-out and marks the options as
        // onboarded. If the fade never completes, force a fresh TitleScreen so the run cannot hang.
        if (current instanceof AccessibilityOnboardingScreen onboarding) {
            if (!onboardingClosed) {
                onboardingClosed = true;
                onboardingCloseTick = ticks;
                log("dismissed accessibility onboarding (close())");
                try {
                    onboarding.close();
                } catch (Throwable throwable) {
                    logError("could not close the accessibility onboarding screen: "
                            + describe(throwable), throwable);
                }
            } else if (onboardingCloseTick >= 0
                    && ticks - onboardingCloseTick > ONBOARDING_FADE_TICKS) {
                log("accessibility onboarding did not fade out; forcing a fresh TitleScreen");
                mc.setScreen(new TitleScreen());
                onboardingCloseTick = ticks;
            }
            return false;
        }

        // Ready on the title screen, or in a world with the HUD visible (currentScreen == null).
        // While a world is still loading the game shows an intermediate screen; opening the menu
        // over it would lose the screen (and could even interrupt the join), so keep waiting.
        boolean titleScreen = current instanceof TitleScreen;
        boolean worldReady = mc.player != null && current == null;
        if (!titleScreen && !worldReady) {
            if (ticks % 200 == 0) {
                log("waiting for the title screen or a loaded world; current screen="
                        + screenName(current) + ", player=" + (mc.player != null)
                        + ", loading=" + !mc.isFinishedLoading());
            }
            return false;
        }

        // The vanilla splash overlay (Mojang logo, "Loading...") is not a Screen; it is drawn on top
        // of everything and would otherwise end up in the first screenshots. Wait it out.
        if (mc.getOverlay() != null) {
            if (ticks % 100 == 0) {
                log("waiting for the vanilla splash overlay to finish (screen="
                        + screenName(current) + ")");
            }
            return false;
        }
        readyTick = ticks;
        ConfigStore store = FmccClient.store();
        baselineEntries = store == null ? 0 : store.size();
        stepMessage = "client ready: screen=" + screenName(mc.currentScreen)
                + ", inWorld=" + (mc.player != null)
                + ", baselineEntries=" + baselineEntries
                + ", config=" + (store == null ? "<null>" : store.file());
        return true;
    }

    /**
     * The splash overlay can be gone for a tick before the first menu frame is actually drawn, so
     * wait an extra ~1 s (20 ticks) after it disappears. This keeps the first screenshot free of the
     * Mojang logo / black frame.
     */
    private static boolean waitForSplashFade(MinecraftClient mc) {
        if (mc.getOverlay() != null) {
            splashSettleTicks = -1;
            return false;
        }
        if (splashSettleTicks < 0) {
            splashSettleTicks = 20;
            return false;
        }
        if (splashSettleTicks > 0) {
            splashSettleTicks--;
            return false;
        }
        stepMessage = "splash overlay gone; viewport settled for an extra 20 ticks";
        return true;
    }

    /**
     * Requirement 2: the registered key binding must exist, live in the FMCC category, be named by
     * {@code key.fmcc.open} and default to Tab. Recorded as geometry/report checks.
     */
    private static boolean assertHotkeyBinding(MinecraftClient mc) {
        KeyBinding binding = FmccClient.openKey();
        require(binding != null, "FmccClient.openKey() returned null (key binding was not registered)");
        GEOMETRY_FAILURES.clear();
        checkGeometry("key.fmcc.open".equals(binding.getTranslationKey()), "hotkey-translation-key",
                "key.fmcc.open", String.valueOf(binding.getTranslationKey()));
        checkGeometry("category.fmcc".equals(binding.getCategory()), "hotkey-category",
                "category.fmcc", String.valueOf(binding.getCategory()));

        InputUtil.Key defaultKey = binding.getDefaultKey();
        checkGeometry(defaultKey != null, "hotkey-default-key-present", "non-null", "null");
        if (defaultKey != null) {
            checkGeometry(defaultKey.getCategory() == InputUtil.Type.KEYSYM, "hotkey-default-key-type",
                    "KEYSYM", String.valueOf(defaultKey.getCategory()));
            checkGeometry(defaultKey.getCode() == GLFW.GLFW_KEY_TAB, "hotkey-default-key-code",
                    "GLFW_KEY_TAB=" + GLFW.GLFW_KEY_TAB, String.valueOf(defaultKey.getCode()));
            checkGeometry("key.keyboard.tab".equals(defaultKey.getTranslationKey()),
                    "hotkey-default-key-name", "key.keyboard.tab",
                    String.valueOf(defaultKey.getTranslationKey()));
        }
        checkGeometry(binding.matchesKey(GLFW.GLFW_KEY_TAB, 0), "hotkey-bound-to-tab",
                "bound to Tab", "matchesKey(GLFW_KEY_TAB,0)=" + binding.matchesKey(GLFW.GLFW_KEY_TAB, 0));

        if (!GEOMETRY_FAILURES.isEmpty()) {
            throw new AssertionError("hotkey binding check failed: " + String.join(", ", GEOMETRY_FAILURES));
        }
        stepMessage = "hotkey ok: translationKey=key.fmcc.open, category=category.fmcc, "
                + "default=Tab (KEYSYM code " + (defaultKey == null ? "?" : defaultKey.getCode()) + "), "
                + "boundToTab=" + binding.matchesKey(GLFW.GLFW_KEY_TAB, 0);
        return true;
    }

    /**
     * Requirement 2 end-to-end: inject a real Tab key press through {@link KeyBinding#onKeyPressed}
     * and wait (retrying up to 3 times) for the END_CLIENT_TICK handler in FmccClient to open the
     * menu.
     * <p>
     * The menu is in-game only: {@code FmccClient.openMenu()} opens only when
     * {@code currentScreen == null} (the HUD state), so on the title screen this cannot be exercised
     * and the step is recorded as <b>skipped</b>; the real proof runs in the in-world self-test
     * ({@code runClientSelfTestWorld}).
     */
    private static boolean hotkeyOpensMenu(MinecraftClient mc) {
        if (mc.player == null) {
            throw new SkipStep("title-screen environment: the FMCC menu opens only from the in-game HUD "
                    + "(currentScreen == null), so the Tab end-to-end check runs in the in-world "
                    + "run (runClientSelfTestWorld)");
        }
        if (mc.currentScreen instanceof FmccScreen) {
            stepMessage = "Tab hotkey opened FmccScreen after " + hotkeyAttempts + " press(es)"
                    + " (screen=" + screenName(mc.currentScreen)
                    + ", playerInWorld=" + (mc.player != null) + ")";
            return true;
        }
        if (hotkeyWaitTicks < 0) {
            hotkeyAttempts = 0;
            hotkeyWaitTicks = 20;
            pressHotkey();
            return false;
        }
        if (hotkeyWaitTicks > 0) {
            hotkeyWaitTicks--;
            return false;
        }
        require(hotkeyAttempts < 3, "Tab hotkey did not open FmccScreen after 3 presses (screen="
                + screenName(mc.currentScreen) + ", playerInWorld=" + (mc.player != null) + ")");
        pressHotkey();
        hotkeyWaitTicks = 20;
        return false;
    }

    private static void pressHotkey() {
        hotkeyAttempts++;
        KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_TAB, 0));
        log("simulated Tab press #" + hotkeyAttempts
                + " via KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW_KEY_TAB, 0))");
    }

    /**
     * Player requirement: pressing the hotkey again closes the menu. End-to-end only makes sense
     * in-world (the hotkey cannot open the menu on the title screen), so it is skipped there.
     */
    private static boolean hotkeyClosesMenu(MinecraftClient mc) {
        if (mc.player == null) {
            throw new SkipStep("title-screen environment: the menu is not opened by the hotkey here, "
                    + "so the press-again-to-close path is covered by runClientSelfTestWorld");
        }
        Screen screen = mc.currentScreen;
        if (screen == null) {
            stepMessage = "hotkey closed the menu after " + hotkeyCloseAttempts
                    + " press(es) (currentScreen=null, playerInWorld=true)";
            return true;
        }
        require(screen instanceof FmccScreen,
                "expected FmccScreen before the close-hotkey check, got " + screenName(screen));
        if (hotkeyCloseWaitTicks < 0) {
            hotkeyCloseAttempts = 0;
            // Give the toggle debounce window time to pass before the second press.
            hotkeyCloseWaitTicks = 20;
            return false;
        }
        if (hotkeyCloseWaitTicks > 0) {
            hotkeyCloseWaitTicks--;
            return false;
        }
        require(hotkeyCloseAttempts < 3, "hotkey did not close the menu after 3 presses (screen="
                + screenName(mc.currentScreen) + ")");
        hotkeyCloseAttempts++;
        KeyBinding.onKeyPressed(InputUtil.fromKeyCode(GLFW.GLFW_KEY_TAB, 0));
        log("simulated Tab press #" + hotkeyCloseAttempts + " to close the menu");
        hotkeyCloseWaitTicks = 20;
        return false;
    }

    /**
     * ESC requirement end-to-end, environment independent: make sure an FmccScreen is current
     * (opening one programmatically when the title screen or no screen is current), then inject ESC
     * through {@link Screen#keyPressed} and wait (retrying up to 3 times) until the menu is closed.
     * A client that restores another screen after closing (e.g. the title screen) still counts as
     * closed; a {@code currentScreen == null} result is reported explicitly.
     */
    private static boolean escClosesMenu(MinecraftClient mc) {
        Screen screen = mc.currentScreen;

        // Success: the ESC we injected made the FmccScreen go away (null, or the client restored a
        // different screen such as the title screen). Still retry when it is still the menu.
        if (escSent && !(screen instanceof FmccScreen)) {
            stepMessage = "ESC closed the menu: currentScreen=" + screenName(screen)
                    + (screen == null ? " (null, as expected)" : " (client restored " + screenName(screen) + ")")
                    + " after " + escAttempts + " ESC attempt(s), playerInWorld=" + (mc.player != null);
            return true;
        }

        // Ensure an open menu to send ESC to (in-world the hotkey step already opened it; on the
        // title screen we open it programmatically so ESC can be verified there too).
        if (!(screen instanceof FmccScreen)) {
            if (!escOpenedProgrammatically) {
                escOpenedProgrammatically = true;
                log("opening FmccScreen programmatically for the ESC check (current screen="
                        + screenName(screen) + ")");
                mc.setScreen(new FmccScreen());
            }
            return false; // retry next tick until the menu is the current screen
        }

        if (escWaitTicks < 0) {
            escAttempts = 0;
            escWaitTicks = 20;
            escSent = true;
            pressEscape(screen);
            return false;
        }
        if (escWaitTicks > 0) {
            escWaitTicks--;
            return false;
        }
        require(escAttempts < 3, "ESC did not close FmccScreen after 3 attempts (screen="
                + screenName(mc.currentScreen) + ")");
        pressEscape(screen);
        escWaitTicks = 20;
        return false;
    }

    private static void pressEscape(Screen screen) {
        escAttempts++;
        boolean handled = screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        log("sent ESC to " + screenName(screen) + " (attempt " + escAttempts
                + ", handled=" + handled + ")");
    }

    private static boolean openMainMenu(MinecraftClient mc) {
        mc.setScreen(new FmccScreen());
        stepMessage = "opened FmccScreen via client.setScreen(new FmccScreen())";
        return true;
    }

    private static boolean waitForMainMenu(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        FmccScreen menu = (FmccScreen) mc.currentScreen;
        stepMessage = "main menu open with " + menu.entryCount() + " entries, scrollOffset=" + menu.scrollOffset();
        return true;
    }

    private static boolean assertEmptyGeometry(MinecraftClient mc) throws Exception {
        Screen screen = requireScreen(mc, FmccScreen.class);
        FmccScreen menu = (FmccScreen) screen;
        UiLayout.Panel panel = menu.panel();
        require(panel != null, "FmccScreen.panel() returned null");
        GEOMETRY_FAILURES.clear();
        ClickableWidget plus = requirePlus(screen);
        checkGeometry(true, "empty-add-button-present", "1 '+' button", "found");
        checkGeometry(plus.getRight() == panel.contentRight(), "empty-add-button-right-aligned",
                "right=" + panel.contentRight(), "right=" + plus.getRight());
        checkGeometry(plus.getWidth() == UiTheme.ADD_SIZE && plus.getHeight() == UiTheme.ADD_SIZE,
                "empty-add-button-size", UiTheme.ADD_SIZE + "x" + UiTheme.ADD_SIZE,
                plus.getWidth() + "x" + plus.getHeight());
        if (menu.maxScroll() == 0) {
            int expectedY = UiLayout.addButtonY(panel, menu.entryCount(), menu.scrollOffset());
            checkGeometry(plus.getY() == expectedY, "empty-add-button-y", "y=" + expectedY, "y=" + plus.getY());
        }
        if (!GEOMETRY_FAILURES.isEmpty()) {
            throw new AssertionError("geometry check failed: " + String.join(", ", GEOMETRY_FAILURES));
        }
        stepMessage = "empty state: '+' right edge=" + plus.getRight() + ", contentRight=" + panel.contentRight()
                + ", y=" + plus.getY() + ", size=" + plus.getWidth() + "x" + plus.getHeight();
        return true;
    }

    private static boolean clickAddButton(MinecraftClient mc, String what) {
        Screen screen = requireScreen(mc, FmccScreen.class);
        ClickableWidget plus = requirePlus(screen);
        clickWidget(mc, plus, what);
        stepMessage = "clicked " + what + " at (" + centerX(plus) + "," + centerY(plus) + "), label='"
                + labelOf(plus) + "'";
        return true;
    }

    private static boolean waitForAddScreen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof AddEntryScreen)) {
            return false;
        }
        Screen screen = mc.currentScreen;
        TextFieldWidget field = firstField(screen);
        stepMessage = "AddEntryScreen open, title field=" + (field != null ? "present" : "MISSING");
        return true;
    }

    private static boolean clickCancel(MinecraftClient mc) {
        Screen screen = requireScreen(mc, AddEntryScreen.class);
        ClickableWidget cancel = findByKey(screen, "fmcc.add.cancel", "Cancel", "取消");
        requireWidget(cancel, "Cancel button on AddEntryScreen");
        clickWidget(mc, cancel, "cancel button");
        stepMessage = "clicked Cancel; store size before returning=" + requireStore().size();
        return true;
    }

    private static boolean waitForMenuAfterCancel(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        int size = requireStore().size();
        require(size == baselineEntries,
                "Cancel must not create an entry: store size " + size + " != baseline " + baselineEntries
                        + " (titles: " + titles() + ")");
        stepMessage = "cancel returned to the menu and created nothing (store size=" + size + ")";
        return true;
    }

    private static boolean typeTitle(MinecraftClient mc, String title) {
        Screen screen = requireScreen(mc, AddEntryScreen.class);
        TextFieldWidget field = firstField(screen);
        require(field != null, "AddEntryScreen has no TextFieldWidget: widgets=" + widgetLabels(screen));
        clickWidget(mc, field, "title field");
        typeInto(field, title);
        String actual = field.getText();
        require(title.equals(actual), "typed title '" + actual + "' != '" + title + "'");
        stepMessage = "typed title '" + actual + "' (" + TitleRules.count(actual) + " code points)";
        return true;
    }

    private static boolean clickCreate(MinecraftClient mc) {
        Screen screen = requireScreen(mc, AddEntryScreen.class);
        ClickableWidget create = findByKey(screen, "fmcc.add.confirm", "Create", "创建");
        requireWidget(create, "Create button on AddEntryScreen");
        require(create.active, "Create button is disabled although the typed title should be valid; "
                + "widgets=" + widgetLabels(screen));
        clickWidget(mc, create, "Create button");
        stepMessage = "clicked Create (active=true), store size now=" + requireStore().size();
        return true;
    }

    private static boolean waitForEntryA(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        ConfigStore store = requireStore();
        require(store.size() == baselineEntries + 1,
                "expected " + (baselineEntries + 1) + " entries after creating A, got " + store.size()
                        + " (" + titles() + ")");
        FmccEntry entry = findByTitle(store, titleA);
        require(entry != null, "created entry '" + titleA + "' not found; entries=" + titles());
        entryAId = entry.getId();
        stepMessage = "entry A persisted: id=" + entryAId + ", title='" + entry.getTitle() + "'";
        return true;
    }

    private static boolean waitForEntryB(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        ConfigStore store = requireStore();
        require(store.size() == baselineEntries + 2,
                "expected " + (baselineEntries + 2) + " entries after creating B, got " + store.size()
                        + " (" + titles() + ")");
        FmccEntry entry = findByTitle(store, titleB);
        require(entry != null, "created entry '" + titleB + "' not found; entries=" + titles());
        entryBId = entry.getId();
        stepMessage = "entry B persisted: id=" + entryBId + ", title='" + entry.getTitle() + "'";
        return true;
    }

    private static boolean assertRowGeometry(MinecraftClient mc) throws Exception {
        Screen screen = requireScreen(mc, FmccScreen.class);
        FmccScreen menu = (FmccScreen) screen;
        ConfigStore store = requireStore();
        UiLayout.Panel panel = menu.panel();
        require(panel != null, "FmccScreen.panel() returned null");
        GEOMETRY_FAILURES.clear();

        List<FmccEntry> entries = store.entries();
        List<ClickableWidget> widgets = widgets(screen);
        int scroll = menu.scrollOffset();
        int expectedMainWidth = Math.round(panel.contentWidth() * 2f / 3f);

        checkGeometry(menu.entryCount() == entries.size(), "entry-count-in-sync",
                "" + entries.size(), "" + menu.entryCount());

        ClickableWidget plus = requirePlus(screen);
        checkGeometry(plus.getRight() == panel.contentRight(), "add-button-right-aligned",
                "right=" + panel.contentRight(), "right=" + plus.getRight());
        checkGeometry(plus.getWidth() == UiTheme.ADD_SIZE && plus.getHeight() == UiTheme.ADD_SIZE,
                "add-button-size", UiTheme.ADD_SIZE + "x" + UiTheme.ADD_SIZE,
                plus.getWidth() + "x" + plus.getHeight());

        boolean fullyVisible = menu.maxScroll() == 0;
        if (!fullyVisible) {
            checkGeometry(true, "row-geometry-skipped-scrolling",
                    "maxScroll=0", "maxScroll=" + menu.maxScroll());
        }
        int expectedAddY = UiLayout.addButtonY(panel, menu.entryCount(), scroll);
        if (fullyVisible) {
            checkGeometry(plus.getY() == expectedAddY, "add-button-y",
                    "y=" + expectedAddY, "y=" + plus.getY());
        }

        if (fullyVisible) {
            String configureLabel = translated("fmcc.menu.configure");
            String configureShortLabel = translated("fmcc.menu.configure.short");
            String deleteLabel = translated("fmcc.menu.delete");
            for (int i = 0; i < entries.size(); i++) {
                FmccEntry entry = entries.get(i);
                UiLayout.MainRow expected = UiLayout.mainRow(panel, i, scroll);
                String prefix = "row-" + i;
                ClickableWidget main = findAtRow(widgets, entry.getTitle(), expected.y());
                requireWidget(main, prefix + " send button with title '" + entry.getTitle() + "'");
                checkGeometry(main.getX() == expected.mainX(), prefix + "-send-x",
                        "x=" + expected.mainX(), "x=" + main.getX());
                checkGeometry(main.getY() == expected.y(), prefix + "-send-y",
                        "y=" + expected.y(), "y=" + main.getY());
                checkGeometry(expected.mainWidth() == expectedMainWidth, prefix + "-two-thirds-width",
                        "round(2/3*" + panel.contentWidth() + ")=" + expectedMainWidth,
                        "" + expected.mainWidth());
                checkGeometry(main.getWidth() == expectedMainWidth, prefix + "-send-width",
                        "width=" + expectedMainWidth, "width=" + main.getWidth());
                checkGeometry(main.getHeight() == UiTheme.ROW_HEIGHT, prefix + "-send-height",
                        "height=" + UiTheme.ROW_HEIGHT, "height=" + main.getHeight());

                ClickableWidget configure = findAtRowAny(widgets, expected.y(), configureLabel, configureShortLabel);
                requireWidget(configure, prefix + " configure button");
                checkGeometry(configure.getX() == expected.configureX(), prefix + "-configure-x",
                        "x=" + expected.configureX(), "x=" + configure.getX());
                checkGeometry(configure.getWidth() == expected.configureWidth(), prefix + "-configure-width",
                        "width=" + expected.configureWidth(), "width=" + configure.getWidth());

                ClickableWidget delete = findAtRow(widgets, deleteLabel, expected.y());
                requireWidget(delete, prefix + " delete button");
                checkGeometry(delete.getX() == expected.deleteX(), prefix + "-delete-x",
                        "x=" + expected.deleteX(), "x=" + delete.getX());
                checkGeometry(delete.getWidth() == expected.deleteWidth(), prefix + "-delete-width",
                        "width=" + expected.deleteWidth(), "width=" + delete.getWidth());
                checkGeometry(delete.getRight() == panel.contentRight(), prefix + "-delete-right-aligned",
                        "right=" + panel.contentRight(), "right=" + delete.getRight());

                checkGeometry(main.getX() < configure.getX() && configure.getX() < delete.getX(),
                        prefix + "-button-order", "send < configure < delete",
                        main.getX() + " < " + configure.getX() + " < " + delete.getX());
                checkGeometry(main.getRight() <= configure.getX() && configure.getRight() <= delete.getX(),
                        prefix + "-buttons-no-overlap", "no horizontal overlap",
                        "send.right=" + main.getRight() + ", configure.x=" + configure.getX()
                                + ", configure.right=" + configure.getRight() + ", delete.x=" + delete.getX());

                if (i > 0) {
                    UiLayout.MainRow previous = UiLayout.mainRow(panel, i - 1, scroll);
                    int gap = expected.y() - previous.y();
                    checkGeometry(gap == UiLayout.rowStride(),
                            prefix + "-row-stride", "stride=" + UiLayout.rowStride(), "gap=" + gap);
                    checkGeometry(previous.y() + UiTheme.ROW_HEIGHT <= expected.y(),
                            prefix + "-rows-no-overlap", "previous bottom <= current top",
                            (previous.y() + UiTheme.ROW_HEIGHT) + " <= " + expected.y());
                }
            }
            // "+" must sit below the last row and be aligned with the delete column.
            int lastY = entries.isEmpty() ? panel.listTop() : UiLayout.mainRow(panel, entries.size() - 1, scroll).y();
            checkGeometry(plus.getY() >= lastY + UiTheme.ROW_HEIGHT, "add-button-below-last-row",
                    "y>=" + (lastY + UiTheme.ROW_HEIGHT), "y=" + plus.getY());
        }

        if (!GEOMETRY_FAILURES.isEmpty()) {
            throw new AssertionError("geometry check failed: " + String.join(", ", GEOMETRY_FAILURES));
        }
        stepMessage = "geometry ok: " + entries.size() + " rows, contentWidth=" + panel.contentWidth()
                + ", expectedSendWidth=" + expectedMainWidth + ", add right=" + plus.getRight();
        return true;
    }

    private static boolean deleteEntryB(MinecraftClient mc) {
        Screen screen = requireScreen(mc, FmccScreen.class);
        ConfigStore store = requireStore();
        FmccEntry entry = store.byId(entryBId).orElse(null);
        require(entry != null, "entry B disappeared before it could be deleted");
        ClickableWidget main = requireRowWidget(screen, entry.getTitle());
        ClickableWidget delete = requireRowWidgetAt(screen, rowY(main), translated("fmcc.menu.delete"));
        clickWidget(mc, delete, "delete button for entry B");
        stepMessage = "clicked '-' on row '" + entry.getTitle() + "' (store size before=" + store.size() + ")";
        return true;
    }

    private static boolean waitForEntryBDeleted(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        ConfigStore store = requireStore();
        require(store.byId(entryBId).isEmpty(), "entry B still present after clicking delete");
        require(store.size() == baselineEntries + 1,
                "expected " + (baselineEntries + 1) + " entries after deleting B, got " + store.size());
        require(store.byId(entryAId).isPresent(), "entry A must survive deleting entry B");
        stepMessage = "entry B deleted and persisted; remaining entries=" + titles();
        return true;
    }

    private static boolean clickConfigureA(MinecraftClient mc) {
        Screen screen = requireScreen(mc, FmccScreen.class);
        ConfigStore store = requireStore();
        FmccEntry entry = store.byId(entryAId).orElse(null);
        require(entry != null, "entry A disappeared before configuration");
        ClickableWidget main = requireRowWidget(screen, entry.getTitle());
        ClickableWidget configure = findAtRowAny(widgets(screen), rowY(main),
                translated("fmcc.menu.configure"), translated("fmcc.menu.configure.short"));
        requireWidget(configure, "configure button in row of '" + entry.getTitle() + "'");
        clickWidget(mc, configure, "configure button for entry A");
        stepMessage = "clicked Configure on row '" + entry.getTitle() + "'";
        return true;
    }

    private static boolean waitForConfigScreen(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof EntryConfigScreen)) {
            return false;
        }
        EntryConfigScreen config = (EntryConfigScreen) mc.currentScreen;
        FmccEntry shown = config.entry();
        if (shown != null && entryAId != null && !entryAId.equals(shown.getId())) {
            throw new AssertionError("config screen shows wrong entry: id=" + shown.getId()
                    + " expected=" + entryAId);
        }
        stepMessage = "EntryConfigScreen open for '" + (shown == null ? "?" : shown.getTitle())
                + "', texts=" + (shown == null ? "?" : shown.getTexts().size());
        return true;
    }

    private static boolean clickConfigAdd(MinecraftClient mc) {
        Screen screen = requireScreen(mc, EntryConfigScreen.class);
        ClickableWidget plus = requirePlus(screen);
        FmccEntry entry = requireEntryA();
        clickWidget(mc, plus, "config '+' button");
        stepMessage = "clicked '+' in config screen; texts before=" + entry.getTexts().size();
        return true;
    }

    private static boolean waitForOneText(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof EntryConfigScreen)) {
            return false;
        }
        FmccEntry entry = requireEntryA();
        if (entry.getTexts().size() != 1) {
            return false;
        }
        stepMessage = "config screen now has 1 text field";
        return true;
    }

    private static boolean waitForTwoTexts(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof EntryConfigScreen)) {
            return false;
        }
        FmccEntry entry = requireEntryA();
        if (entry.getTexts().size() != 2) {
            return false;
        }
        stepMessage = "config screen now has 2 text fields";
        return true;
    }

    private static boolean typeTexts(MinecraftClient mc) {
        Screen screen = requireScreen(mc, EntryConfigScreen.class);
        List<TextFieldWidget> fields = fields(screen);
        require(fields.size() >= 2, "expected 2 text fields, found " + fields.size()
                + ": " + widgetLabels(screen));
        clickWidget(mc, fields.get(0), "first text field");
        typeInto(fields.get(0), TEXT_COMMAND);
        clickWidget(mc, fields.get(1), "second text field");
        typeInto(fields.get(1), TEXT_CHAT);
        stepMessage = "typed '" + fields.get(0).getText() + "' and '" + fields.get(1).getText()
                + "' into the two fields";
        return true;
    }

    private static boolean waitForTextValues(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof EntryConfigScreen)) {
            return false;
        }
        FmccEntry entry = requireEntryA();
        List<String> texts = entry.getTexts();
        if (texts.size() != 2) {
            return false;
        }
        if (!TEXT_COMMAND.equals(texts.get(0)) || !TEXT_CHAT.equals(texts.get(1))) {
            return false;
        }
        stepMessage = "model texts persisted in order: " + texts;
        return true;
    }

    private static boolean deleteSecondText(MinecraftClient mc) {
        Screen screen = requireScreen(mc, EntryConfigScreen.class);
        FmccEntry entry = requireEntryA();
        require(entry.getTexts().size() == 2, "expected 2 texts before deleting one, got "
                + entry.getTexts().size());
        List<TextFieldWidget> fields = fields(screen);
        require(fields.size() >= 2, "expected 2 text fields, found " + fields.size());
        // The delete button for a row shares the row's y; the field is vertically inset inside it.
        List<ClickableWidget> deletes = new ArrayList<>();
        String deleteLabel = translated("fmcc.menu.delete");
        for (ClickableWidget widget : widgets(screen)) {
            if (deleteLabel.equals(labelOf(widget))) {
                deletes.add(widget);
            }
        }
        deletes.sort(Comparator.comparingInt(ClickableWidget::getY));
        require(deletes.size() >= 2, "expected 2 '-' buttons in the config screen, found "
                + deletes.size() + ": " + widgetLabels(screen));
        clickWidget(mc, deletes.get(1), "delete button of the second text");
        stepMessage = "clicked '-' on the second text row";
        return true;
    }

    private static boolean waitForTextShrink(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof EntryConfigScreen)) {
            return false;
        }
        FmccEntry entry = requireEntryA();
        List<String> texts = entry.getTexts();
        if (texts.size() != 1) {
            return false;
        }
        require(TEXT_COMMAND.equals(texts.get(0)), "after deleting the second text the model should be ["
                + TEXT_COMMAND + "] but was " + texts);
        stepMessage = "text deleted and persisted, remaining texts=" + texts;
        return true;
    }

    private static boolean clickBack(MinecraftClient mc) {
        Screen screen = requireScreen(mc, EntryConfigScreen.class);
        ClickableWidget back = findByKey(screen, "fmcc.config.back", "Back", "返回");
        requireWidget(back, "Back button on EntryConfigScreen");
        clickWidget(mc, back, "Back button");
        stepMessage = "clicked Back";
        return true;
    }

    private static boolean waitForMenuReturn(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        FmccEntry entry = requireEntryA();
        require(entry.getTexts().size() == 1 && TEXT_COMMAND.equals(entry.getTexts().get(0)),
                "texts were not preserved when returning to the menu: " + entry.getTexts());
        stepMessage = "returned to the menu; entry A texts=" + entry.getTexts();
        return true;
    }

    private static boolean clickSendA(MinecraftClient mc) {
        Screen screen = requireScreen(mc, FmccScreen.class);
        FmccEntry entry = requireEntryA();
        ClickableWidget main = requireRowWidget(screen, entry.getTitle());
        sentCountBeforeSend = FmccClient.queue() == null ? -1 : FmccClient.queue().sentCount();
        clickWidget(mc, main, "send button for entry A");
        sendClickTick = ticks;
        stepMessage = "clicked send on '" + entry.getTitle() + "' with texts=" + entry.sendableTexts()
                + ", queue.sentCount before=" + sentCountBeforeSend
                + ", playerInWorld=" + (mc.player != null);
        return true;
    }

    private static boolean verifySendQueued(MinecraftClient mc) {
        if (mc.player == null) {
            throw new SkipStep("no player in a world (title screen): ChatDelivery cannot send, "
                    + "so the message-queue assertion was skipped - the empty-text / not-in-world path "
                    + "was exercised instead");
        }
        require(FmccClient.queue() != null, "FmccClient.queue() is null");
        if (FmccClient.queue().sentCount() > sentCountBeforeSend) {
            stepMessage = "queue sent the message: sentCount " + sentCountBeforeSend + " -> "
                    + FmccClient.queue().sentCount() + ", queued=" + FmccClient.queue().queuedCount()
                    + ", busy=" + FmccClient.queue().isBusy();
            return true;
        }
        return false;
    }

    /** In-world: the single queued text must be delivered and the queue must drain (not stay busy). */
    private static boolean waitQueueDrain(MinecraftClient mc) {
        if (mc.player == null) {
            // No player: ChatDelivery cannot send, so there is nothing to drain. The skip is already
            // recorded by verify-send-queued; the real drain assertion runs in-world.
            stepMessage = "title-screen environment: no queue to drain (ChatDelivery cannot send); "
                    + "covered by the in-world run";
            return true;
        }
        var queue = FmccClient.queue();
        require(queue != null, "FmccClient.queue() is null");
        if (queue.isBusy() || queue.queuedCount() > 0) {
            return false;
        }
        require(queue.sentCount() > sentCountBeforeSend, "queue drained but sentCount " + queue.sentCount()
                + " did not increase from " + sentCountBeforeSend);
        stepMessage = "queue drained after " + (sendClickTick < 0 ? "?" : (ticks - sendClickTick))
                + " ticks: sentCount=" + queue.sentCount() + ", queued=0, busy=false";
        return true;
    }

    /** Screenshot ~1 s after the send click, so chat lines and the status toast are visible. */
    private static boolean screenshotAfterSend(MinecraftClient mc) {
        int elapsed = sendClickTick < 0 ? 0 : ticks - sendClickTick;
        if (elapsed < 20) {
            return false;
        }
        String label = mc.player != null ? "main-send-in-world" : "main-after-send";
        screenshot(mc, label);
        stepMessage = "screenshot '" + label + "' " + elapsed + " ticks after the send click"
                + (mc.player != null ? " (in-world: chat lines + toast)" : " (title-screen not-in-world toast)");
        return true;
    }

    private static boolean deleteEntryA(MinecraftClient mc) {
        Screen screen = requireScreen(mc, FmccScreen.class);
        ConfigStore store = requireStore();
        FmccEntry entry = store.byId(entryAId).orElse(null);
        require(entry != null, "entry A disappeared before it could be deleted");
        ClickableWidget main = requireRowWidget(screen, entry.getTitle());
        ClickableWidget delete = requireRowWidgetAt(screen, rowY(main), translated("fmcc.menu.delete"));
        clickWidget(mc, delete, "delete button for entry A");
        stepMessage = "clicked '-' on row '" + entry.getTitle() + "' (store size before=" + store.size() + ")";
        return true;
    }

    private static boolean waitForEntryADeleted(MinecraftClient mc) {
        if (!(mc.currentScreen instanceof FmccScreen)) {
            return false;
        }
        ConfigStore store = requireStore();
        require(store.byId(entryAId).isEmpty(), "entry A still present after clicking delete");
        require(store.byId(entryBId).isEmpty(), "entry B should also be gone");
        require(store.size() == baselineEntries,
                "expected to be back at " + baselineEntries + " entries, got " + store.size()
                        + " (" + titles() + ")");
        stepMessage = "entry A deleted; store back to " + store.size() + " entries";
        return true;
    }

    private static boolean verifyStoreFile(MinecraftClient mc) {
        ConfigStore live = requireStore();
        ConfigStore reloaded = new ConfigStore(live.file());
        reloaded.load();
        require(reloaded.size() == baselineEntries, "on-disk config has " + reloaded.size()
                + " entries after the test, expected " + baselineEntries + " (" + live.file() + ")");
        require(reloaded.byId(entryAId).isEmpty() && reloaded.byId(entryBId).isEmpty(),
                "self-test entries were not removed from the persisted config");
        stepMessage = "persisted config " + live.file() + " reloads with " + reloaded.size()
                + " entries (no self-test leftovers)";
        return true;
    }

    private static boolean scenarioComplete(MinecraftClient mc) {
        stepMessage = "all scenario steps passed; writing report and stopping the client";
        return true;
    }

    // ================================================================ helpers

    private static boolean screenshot(MinecraftClient mc, String label) {
        if (mc.runDirectory == null) {
            log("screenshot " + label + " skipped: run directory is null");
            return true;
        }
        PENDING_SCREENSHOTS.incrementAndGet();
        try {
            ScreenshotRecorder.saveScreenshot(mc.runDirectory, mc.getFramebuffer(), message -> {
                JsonObject shot = new JsonObject();
                shot.addProperty("label", label);
                shot.addProperty("tick", ticks);
                String text = message == null ? "" : message.getString();
                shot.addProperty("message", text);
                String name = extractPngName(text);
                long size = -1L;
                if (name != null) {
                    Path path = new File(new File(mc.runDirectory, "screenshots"), name).toPath();
                    shot.addProperty("file", path.toString());
                    boolean exists = Files.isRegularFile(path);
                    shot.addProperty("exists", exists);
                    if (exists) {
                        try {
                            size = Files.size(path);
                        } catch (Throwable ignored) {
                            size = -1L;
                        }
                    }
                }
                shot.addProperty("sizeBytes", size);
                if (size >= 0 && size < MIN_EXPECTED_SCREENSHOT_BYTES) {
                    shot.addProperty("suspectSmallFrame", true);
                    logWarn("screenshot '" + label + "' is suspiciously small (" + size + " bytes < "
                            + MIN_EXPECTED_SCREENSHOT_BYTES
                            + ") - likely a splash/black frame, not the menu");
                } else if (size >= 0) {
                    shot.addProperty("suspectSmallFrame", false);
                }
                SCREENSHOTS.add(shot);
                PENDING_SCREENSHOTS.decrementAndGet();
                log("screenshot '" + label + "' saved: " + text
                        + (size >= 0 ? " (" + size + " bytes)" : ""));
            });
            log("screenshot '" + label + "' requested");
        } catch (Throwable throwable) {
            PENDING_SCREENSHOTS.decrementAndGet();
            logError("screenshot '" + label + "' failed: " + describe(throwable), throwable);
        }
        return true;
    }

    private static String extractPngName(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = PNG_NAME.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String translated(String key) {
        try {
            Text text = Text.translatable(key);
            String resolved = text.getString();
            return resolved == null || resolved.isEmpty() ? key : resolved;
        } catch (Throwable throwable) {
            return key;
        }
    }

    private static List<ClickableWidget> widgets(Screen screen) {
        List<ClickableWidget> widgets = new ArrayList<>();
        if (screen == null) {
            return widgets;
        }
        for (Element element : screen.children()) {
            if (element instanceof ClickableWidget widget && widget.visible) {
                widgets.add(widget);
            }
        }
        return widgets;
    }

    private static List<TextFieldWidget> fields(Screen screen) {
        List<TextFieldWidget> fields = new ArrayList<>();
        if (screen == null) {
            return fields;
        }
        for (Element element : screen.children()) {
            if (element instanceof TextFieldWidget field && field.visible) {
                fields.add(field);
            }
        }
        fields.sort(Comparator.comparingInt(TextFieldWidget::getY).thenComparingInt(TextFieldWidget::getX));
        return fields;
    }

    private static TextFieldWidget firstField(Screen screen) {
        List<TextFieldWidget> fields = fields(screen);
        return fields.isEmpty() ? null : fields.get(0);
    }

    private static String labelOf(ClickableWidget widget) {
        Text message = widget == null ? null : widget.getMessage();
        return message == null ? "" : message.getString();
    }

    private static ClickableWidget findByLabel(List<ClickableWidget> widgets, String label) {
        if (label == null) {
            return null;
        }
        for (ClickableWidget widget : widgets) {
            if (label.equals(labelOf(widget))) {
                return widget;
            }
        }
        return null;
    }

    private static ClickableWidget findByKey(Screen screen, String key, String... fallbacks) {
        List<ClickableWidget> widgets = widgets(screen);
        List<String> candidates = new ArrayList<>();
        String resolved = translated(key);
        if (resolved != null && !resolved.isBlank()) {
            candidates.add(resolved);
        }
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (fallback != null && !candidates.contains(fallback)) {
                    candidates.add(fallback);
                }
            }
        }
        for (String candidate : candidates) {
            ClickableWidget widget = findByLabel(widgets, candidate);
            if (widget != null) {
                return widget;
            }
        }
        return null;
    }

    private static ClickableWidget findAtRow(List<ClickableWidget> widgets, String label, int rowY) {
        if (label == null) {
            return null;
        }
        ClickableWidget best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ClickableWidget widget : widgets) {
            if (!label.equals(labelOf(widget))) {
                continue;
            }
            int distance = Math.abs(widget.getY() - rowY);
            if (distance <= 4 && distance < bestDistance) {
                best = widget;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static ClickableWidget findAtRowAny(List<ClickableWidget> widgets, int rowY, String... labels) {
        for (String label : labels) {
            ClickableWidget widget = findAtRow(widgets, label, rowY);
            if (widget != null) {
                return widget;
            }
        }
        return null;
    }

    private static ClickableWidget requirePlus(Screen screen) {
        ClickableWidget plus = findByLabel(widgets(screen), "+");
        if (plus == null) {
            plus = findByLabel(widgets(screen), "＋");
        }
        requireWidget(plus, "'+' button on " + screenName(screen));
        return plus;
    }

    private static ClickableWidget requireRowWidget(Screen screen, String labelOrTitle) {
        ClickableWidget widget = findByLabel(widgets(screen), labelOrTitle);
        requireWidget(widget, "widget labelled '" + labelOrTitle + "' on " + screenName(screen));
        return widget;
    }

    private static ClickableWidget requireRowWidgetAt(Screen screen, int rowY, String label) {
        ClickableWidget widget = findAtRow(widgets(screen), label, rowY);
        requireWidget(widget, "widget labelled '" + label + "' in row y=" + rowY
                + " on " + screenName(screen));
        return widget;
    }

    private static void requireWidget(ClickableWidget widget, String description) {
        if (widget == null) {
            Screen screen = client == null ? null : client.currentScreen;
            throw new IllegalStateException("missing " + description + "; screen=" + screenName(screen)
                    + "; widgets=" + widgetLabels(screen));
        }
    }

    private static Screen requireScreen(MinecraftClient mc, Class<? extends Screen> type) {
        Screen screen = mc.currentScreen;
        if (screen == null) {
            throw new IllegalStateException("no screen is open (expected " + type.getSimpleName() + ")");
        }
        if (!type.isInstance(screen)) {
            throw new IllegalStateException("expected " + type.getSimpleName() + " but current screen is "
                    + screenName(screen) + "; widgets=" + widgetLabels(screen));
        }
        return screen;
    }

    private static int rowY(ClickableWidget widget) {
        // Every row widget (send / configure / delete) shares the row's top y.
        return widget.getY();
    }

    private static void clickWidget(MinecraftClient mc, ClickableWidget widget, String description) {
        Screen screen = mc.currentScreen;
        require(screen != null, "no screen open while trying to click " + description);
        require(widget.visible, description + " is not visible");
        require(widget.active, description + " is disabled (active=false)");
        int x = centerX(widget);
        int y = centerY(widget);
        boolean handled = screen.mouseClicked(x, y, 0);
        try {
            screen.mouseReleased(x, y, 0);
        } catch (Throwable ignored) {
            // screens may be replaced synchronously by the click; release is best effort
        }
        if (!handled) {
            throw new IllegalStateException("click on " + description + " at (" + x + "," + y
                    + ") was not handled; screen=" + screenName(screen)
                    + "; widget=" + describeWidget(widget)
                    + "; widgets=" + widgetLabels(screen));
        }
    }

    private static void typeInto(TextFieldWidget field, String text) {
        field.setCursorToEnd(false);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            try {
                field.charTyped(character, 0);
            } catch (Throwable ignored) {
                // fall through to the programmatic fallback below
            }
        }
        if (!text.equals(field.getText())) {
            // Screens sync widgets to the model on tick; setText is the documented fallback used
            // by the task when synthetic typing is filtered (e.g. by a text predicate).
            field.setText(text);
            field.setCursorToEnd(true);
        }
        require(text.equals(field.getText()),
                "could not enter '" + text + "' into the text field (it contains '" + field.getText() + "')");
    }

    private static int centerX(ClickableWidget widget) {
        return widget.getX() + widget.getWidth() / 2;
    }

    private static int centerY(ClickableWidget widget) {
        return widget.getY() + widget.getHeight() / 2;
    }

    private static String describeWidget(ClickableWidget widget) {
        return labelOf(widget) + "[" + widget.getX() + "," + widget.getY() + " "
                + widget.getWidth() + "x" + widget.getHeight()
                + (widget.active ? "" : ", inactive") + "]";
    }

    private static String widgetLabels(Screen screen) {
        List<ClickableWidget> widgets = widgets(screen);
        List<String> labels = new ArrayList<>();
        for (ClickableWidget widget : widgets) {
            labels.add(describeWidget(widget));
        }
        return labels.isEmpty() ? "<none>" : String.join(", ", labels);
    }

    private static ConfigStore requireStore() {
        ConfigStore store = FmccClient.store();
        if (store == null) {
            throw new IllegalStateException("FmccClient.store() is null");
        }
        return store;
    }

    private static FmccEntry requireEntryA() {
        FmccEntry entry = entryAId == null ? null : requireStore().byId(entryAId).orElse(null);
        if (entry == null) {
            throw new IllegalStateException("entry A (id=" + entryAId + ") is missing; entries=" + titles());
        }
        return entry;
    }

    private static FmccEntry findByTitle(ConfigStore store, String title) {
        for (FmccEntry entry : store.entries()) {
            if (title.equals(entry.getTitle())) {
                return entry;
            }
        }
        return null;
    }

    private static String titles() {
        ConfigStore store = FmccClient.store();
        if (store == null) {
            return "<no store>";
        }
        List<String> titles = new ArrayList<>();
        for (FmccEntry entry : store.entries()) {
            titles.add(entry.getTitle());
        }
        return titles.toString();
    }

    private static String screenName(Screen screen) {
        return screen == null ? "null" : screen.getClass().getSimpleName();
    }

    private static void checkGeometry(boolean ok, String id, String expected, String actual) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("ok", ok);
        object.addProperty("expected", expected);
        object.addProperty("actual", actual);
        GEOMETRY.add(object);
        if (!ok) {
            GEOMETRY_FAILURES.add(id + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isBlank()
                ? "" : ": " + message);
    }

    private static void log(String message) {
        FmccClient.LOGGER.info(PREFIX + message);
    }

    private static void logWarn(String message) {
        FmccClient.LOGGER.warn(PREFIX + message);
    }

    private static void logError(String message, Throwable throwable) {
        if (throwable == null) {
            FmccClient.LOGGER.error(PREFIX + message);
        } else {
            FmccClient.LOGGER.error(PREFIX + message, throwable);
        }
    }
}
