package com.fmcc;

import com.fmcc.chat.ChatDelivery;
import com.fmcc.chat.MessageQueue;
import com.fmcc.config.ConfigStore;
import com.fmcc.selftest.SelfTest;
import com.fmcc.ui.FmccScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: owns the config store, the throttled send queue and the hotkey.
 * <p>
 * Client only by design: nothing is ever registered on a dedicated server, so the mod cannot
 * affect server ticks or other players.
 */
public final class FmccClient implements ClientModInitializer {
    public static final String MOD_ID = "fmcc";
    public static final Logger LOGGER = LoggerFactory.getLogger("FMCC");

    /** Default hotkey (Tab), rebindable in Options -> Controls -> FasterMinecraftCommands. */
    public static final String KEY_OPEN = "key.fmcc.open";
    public static final String KEY_CATEGORY = "category.fmcc";

    private static ConfigStore store;
    private static MessageQueue queue;
    private static KeyBinding openKey;
    /**
     * Debounce for the hotkey toggle: one physical key press can reach both the open screen's
     * {@code keyPressed} (which closes it) and the next client tick's {@code wasPressed()} (which
     * would immediately reopen it), so toggles closer than this window are ignored.
     */
    private static long lastToggleMs;

    @Override
    public void onInitializeClient() {
        store = new ConfigStore(FmccPaths.configFile());
        store.load();
        queue = new MessageQueue(new ChatDelivery());

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_OPEN, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_TAB, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(FmccClient::onEndClientTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (queue != null) {
                queue.clear();
            }
        });
        // Config edits are kept in memory and flushed on structural actions / screen close;
        // this is the last-chance flush when the player quits while a screen is still open.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (store != null) {
                store.saveIfDirty();
            }
        });

        if (SelfTest.enabled()) {
            SelfTest.init();
        }
        LOGGER.info("FMCC ready (config: {}, {} entries)", store.file(), store.size());
    }

    private static void onEndClientTick(MinecraftClient client) {
        if (queue != null) {
            queue.tick();
        }
        if (openKey != null) {
            boolean pressed = false;
            while (openKey.wasPressed()) {
                pressed = true;
            }
            if (pressed) {
                openMenu(client);
            }
        }
        if (SelfTest.enabled()) {
            SelfTest.tick(client);
        }
    }

    /** Opens the menu when in-game; a second press while it is open closes it again. */
    public static void openMenu(MinecraftClient client) {
        if (client == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastToggleMs < 180L) {
            return;
        }
        if (client.currentScreen instanceof FmccScreen) {
            lastToggleMs = now;
            client.setScreen(null);
            return;
        }
        if (client.currentScreen == null) {
            lastToggleMs = now;
            client.setScreen(new FmccScreen());
        }
    }

    /** Closes the menu because the player pressed the hotkey again (same debounce window). */
    public static void closeMenuFromHotkey(MinecraftClient client) {
        lastToggleMs = System.currentTimeMillis();
        MinecraftClient target = client != null ? client : MinecraftClient.getInstance();
        if (target != null) {
            target.setScreen(null);
        }
    }

    public static ConfigStore store() {
        return store;
    }

    public static MessageQueue queue() {
        return queue;
    }

    /** @return the registered FMCC key binding (used by the self-test and diagnostics). */
    public static KeyBinding openKey() {
        return openKey;
    }

    /** Plays a short client-side confirmation; no packets, no log spam. */
    public static void statusFeedback(String translationKey, Object... args) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(net.minecraft.text.Text.translatable(translationKey, args), true);
        }
    }
}
