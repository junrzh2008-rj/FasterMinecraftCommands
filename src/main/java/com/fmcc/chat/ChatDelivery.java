package com.fmcc.chat;

import net.minecraft.client.MinecraftClient;

/**
 * Minecraft-specific half of the send pipeline: routes a configured text either to the chat or,
 * when it starts with '/', to the command dispatcher. Works in singleplayer and multiplayer (the
 * server still enforces permissions, exactly like typing the command by hand).
 */
public final class ChatDelivery implements MessageQueue.Sink {
    @Override
    public boolean send(String raw) {
        ChatRouting.Routed routed = ChatRouting.route(raw);
        if (routed == null) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            return false;
        }
        if (routed.command()) {
            client.getNetworkHandler().sendChatCommand(routed.payload());
        } else {
            client.getNetworkHandler().sendChatMessage(routed.payload());
        }
        return true;
    }
}
