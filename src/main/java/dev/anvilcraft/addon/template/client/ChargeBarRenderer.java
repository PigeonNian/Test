package dev.anvilcraft.addon.template.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import dev.anvilcraft.addon.template.item.DoomFistItem;

@OnlyIn(Dist.CLIENT)
public class ChargeBarRenderer {

    private static final int BAR_WIDTH = 60;
    private static final int BAR_HEIGHT = 6;
    private static final int BAR_SPACING = 2;
    private static final int CELL_WIDTH = (BAR_WIDTH - 2 * BAR_SPACING) / 3; // 3 segments with spacing
    private static final int CHARGE_DURATION_TICKS = 30; // 1.5 seconds at 20 ticks per second

    public static void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) return;

        // Check if player is using the doom fist item
        ItemStack usingItem = player.getUseItem();
        if (!(usingItem.getItem() instanceof DoomFistItem)) return;

        // Calculate charge percentage based on use duration up to 1.5 seconds (30 ticks)
        int maxUseDuration = CHARGE_DURATION_TICKS; // Only consider first 1.5 seconds for charging
        int remainingUseDuration = player.getUseItemRemainingTicks();
        int totalUseDuration = ((DoomFistItem) usingItem.getItem()).getUseDuration(usingItem, player);
        
        // Calculate how much time has passed in the charging phase
        int elapsedChargeTime = Math.max(0, totalUseDuration - remainingUseDuration);
        int effectiveElapsed = Math.min(elapsedChargeTime, CHARGE_DURATION_TICKS);
        float chargePercentage = (float) effectiveElapsed / CHARGE_DURATION_TICKS;
        
        // Only render if charging
        if (chargePercentage > 0 && elapsedChargeTime <= CHARGE_DURATION_TICKS) {
            // Draw the charge bar below the crosshair
            int crosshairX = screenWidth / 2;
            int crosshairY = screenHeight / 2;
            
            // Position the bar below the crosshair
            int barX = crosshairX - BAR_WIDTH / 2;
            int barY = crosshairY + 20; // 20 pixels below crosshair
            
            drawChargeBar(guiGraphics, barX, barY, chargePercentage);
        }
    }

    private static void drawChargeBar(GuiGraphics guiGraphics, int x, int y, float chargePercentage) {
        // Draw background (empty bar)
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.5F);
        
        // Draw the 3 segments background
        for (int i = 0; i < 3; i++) {
            int segmentX = x + i * (CELL_WIDTH + BAR_SPACING);
            guiGraphics.fill(segmentX, y, segmentX + CELL_WIDTH, y + BAR_HEIGHT, 0xFF373737);
        }
        
        // Calculate how many segments are filled based on charge percentage
        float filledSegments = chargePercentage * 3;
        
        // Draw filled segments
        for (int i = 0; i < 3; i++) {
            int segmentX = x + i * (CELL_WIDTH + BAR_SPACING);
            if (i < filledSegments) {
                // This segment is completely filled
                int color = getColorForSegment(i, chargePercentage);
                guiGraphics.fill(segmentX, y, segmentX + CELL_WIDTH, y + BAR_HEIGHT, color);
            } else if (i <= filledSegments) {
                // This segment is partially filled
                float partialFill = filledSegments - i;
                if (partialFill > 0) {
                    int fillWidth = (int)(CELL_WIDTH * partialFill);
                    int color = getColorForSegment(i, chargePercentage);
                    guiGraphics.fill(segmentX, y, segmentX + fillWidth, y + BAR_HEIGHT, color);
                }
            }
        }
        
        // Draw border around the entire bar
        guiGraphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y, 0xFF000000); // Top border
        guiGraphics.fill(x - 1, y + BAR_HEIGHT, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xFF000000); // Bottom border
        guiGraphics.fill(x - 1, y - 1, x, y + BAR_HEIGHT + 1, 0xFF000000); // Left border
        guiGraphics.fill(x + BAR_WIDTH, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xFF000000); // Right border
        
        RenderSystem.disableBlend();
    }

    private static int getColorForSegment(int segmentIndex, float chargePercentage) {
        // Change color based on how charged the item is
        if (chargePercentage < 0.33f) {
            // First segment - red
            return 0xFFFF3333;
        } else if (chargePercentage < 0.66f) {
            // Second segment - yellow
            return 0xFFFFFF33;
        } else {
            // Third segment - green
            return 0xFF33FF33;
        }
    }
}