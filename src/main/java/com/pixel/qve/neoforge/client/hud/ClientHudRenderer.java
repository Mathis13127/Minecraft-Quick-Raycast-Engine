package com.pixel.qve.neoforge.client.hud;

import com.pixel.qve.neoforge.network.ClientboundRaycastHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated client-side HUD renderer for Quick Voxel Engine telemetry.
 * Displays a sleek, left-aligned, non-jittering cyberpunk/aerospace telemetry card.
 */
public final class ClientHudRenderer {

    private ClientHudRenderer() {}

    /**
     * Renders the telemetry HUD overlay layer.
     *
     * @param guiGraphics  The GUI graphics context
     * @param deltaTracker Delta tick tracker
     */
    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        ClientHudState state = ClientHudState.INSTANCE;
        if (!state.isActive() || !state.isVisible()) {
            return;
        }

        ClientboundRaycastHudPayload payload = state.getLatestPayload();
        if (payload == null || !payload.active()) {
            return;
        }

        // Auto-fadeout if no payload received in the last 2 seconds
        if (System.currentTimeMillis() - state.getLastReceivedMs() > 2000) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }

        Font font = mc.font;
        long elapsedNs = payload.elapsedNs();
        String latencyStr;
        if (elapsedNs < 1_000) {
            latencyStr = String.format("§a%d ns", elapsedNs);
        } else if (elapsedNs < 1_000_000) {
            latencyStr = String.format("§a%.1f µs", elapsedNs / 1000.0);
        } else {
            latencyStr = String.format("§e%.2f ms", elapsedNs / 1_000_000.0);
        }

        List<String> lines = new ArrayList<>();
        // Line 1: Header + Latency + Max Reach
        lines.add(String.format("§6§l[QVE TELEMETRY] §r§8│ §7Latency: %s §8│ §7Reach: §f%,.0fm", latencyStr, payload.maxDistance()));

        if (payload.hit()) {
            // Line 2: Target distance + Hit Face + Coordinates
            lines.add(String.format("§7Target: §e%,.1fm §8│ §7Face: §d%s §8│ §7Pos: §f[%d, %d, %d]",
                    payload.distance(),
                    payload.face(),
                    payload.blockX(), payload.blockY(), payload.blockZ()
            ));

            // Line 3: Block Registry Name + 32-bit ID
            lines.add(String.format("§7Block:  §b%s §8(ID: §f%d§8)",
                    payload.blockName(),
                    payload.blockId()
            ));

            // Line 4: BlockStates (if present)
            String props = payload.properties();
            if (props != null && !props.isEmpty()) {
                lines.add(String.format("§7States: §e%s", props));
            }
        } else {
            // Air / Miss line
            lines.add(String.format("§7Status: §aClear line of sight §8(Air > %,.0fm)", payload.maxDistance()));
            lines.add("§8No obstacle detected along crosshair vector");
        }

        // Compute layout dimensions
        int maxTextWidth = 210;
        for (String line : lines) {
            int w = font.width(line);
            if (w > maxTextWidth) {
                maxTextWidth = w;
            }
        }

        int x = 8;
        int y = 8;
        int paddingX = 8;
        int paddingY = 6;
        int lineHeight = 11;
        int cardWidth = maxTextWidth + paddingX * 2;
        int cardHeight = lines.size() * lineHeight + paddingY * 2;

        // Dark translucent slate background (0xCC0B1329)
        guiGraphics.fill(x, y, x + cardWidth, y + cardHeight, 0xCC0B1329);

        // Glowing cyber-amber vertical accent line on the left (3px)
        guiGraphics.fill(x, y, x + 3, y + cardHeight, 0xFFFF9900);

        // Subtle top glass highlight
        guiGraphics.fill(x + 3, y, x + cardWidth, y + 1, 0x40FFFFFF);

        // Subtle bottom border shadow
        guiGraphics.fill(x + 3, y + cardHeight - 1, x + cardWidth, y + cardHeight, 0x30000000);

        // Render text lines strictly left-aligned at x + paddingX + 2
        for (int i = 0; i < lines.size(); i++) {
            guiGraphics.drawString(font, lines.get(i), x + paddingX + 2, y + paddingY + i * lineHeight, 0xFFFFFFFF, false);
        }
    }
}
