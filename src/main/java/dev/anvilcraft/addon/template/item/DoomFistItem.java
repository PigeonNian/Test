package dev.anvilcraft.addon.template.item;

import dev.anvilcraft.addon.template.AnvilCraftAddonTemplate;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = AnvilCraftAddonTemplate.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class DoomFistItem extends Item {
    public static final Map<UUID, Integer> DURATIONS = new HashMap<>();
    public DoomFistItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        player.startUsingItem(usedHand);
        return InteractionResultHolder.success(player.getItemInHand(usedHand));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 80;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public void onUseTick(Level level, LivingEntity livingEntity, ItemStack stack, int remainingUseDuration) {
        super.onUseTick(level, livingEntity, stack, remainingUseDuration);
        if (remainingUseDuration >= 40) return;
        if (livingEntity instanceof Player player) {
            player.stopUsingItem();
            player.getCooldowns().addCooldown(this, 40);
            this.effect(player, this.getUseDuration(stack, livingEntity) - remainingUseDuration);
        }

    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity livingEntity, int timeCharged) {
        super.releaseUsing(stack, level, livingEntity, timeCharged);
        if (livingEntity instanceof Player player) {
            player.getCooldowns().addCooldown(this, 80);
            this.effect(player, this.getUseDuration(stack, livingEntity) - timeCharged);
        }
    }

    public void effect(Player player, int useDuration) {
        if (useDuration <= 5) {
            player.setDeltaMovement(
                player.getForward()
                    .multiply(1.0, 0.0, 1.0)
                    .normalize()
                    .scale(2.5)
                    .add(0.0, 0.0, 0.0)
            );
            DURATIONS.put(player.getUUID(), useDuration);
            player.addTag("doom_fist");
            player.setNoGravity(true); // 突刺过程中移除重力
            return;
        }
        int maxDuration = Math.min(30, useDuration);
        player.setDeltaMovement(
            player.getForward()
                .multiply(1.0, 0.0, 1.0)
                .normalize()
                .scale(2.5 * (maxDuration / 30.0 + 1))
                .add(0.0, 0.0, 0.0)
        );
        DURATIONS.put(player.getUUID(), maxDuration);
        player.addTag("doom_fist");
        player.setNoGravity(true); // 突刺过程中移除重力
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (!player.getTags().contains("doom_fist")) return;
        if (player.getDeltaMovement().lengthSqr() <= 0.01) {
            player.removeTag("doom_fist");
            player.setNoGravity(false); // 突刺结束时重新启用重力
            return;
        }
        Level level = player.level();
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox());
        if (entities.isEmpty() || entities.size() == 1.0) {
            // 在滞空冲刺时添加类似地面的摩擦力效果
            if (!player.onGround()) {
                Vec3 motion = player.getDeltaMovement();
                // 模拟地面摩擦力，逐渐减少水平移动速度
                // 但保持y方向动量不变，仅影响水平移动
                double friction = 0.91; // 类似地面摩擦系数
                double xMotion = motion.x * friction;
                double zMotion = motion.z * friction;
                // 确保不会完全停止，保留最小移动速度
                if (Math.abs(xMotion) > 0.01 || Math.abs(zMotion) > 0.01) {
                    player.setDeltaMovement(xMotion, motion.y, zMotion);
                }
            }
            return;
        }
        Vec3 deltaMovement = player.getDeltaMovement();
        player.removeTag("doom_fist");
        player.setNoGravity(false); // 突刺结束时重新启用重力
        player.setDeltaMovement(Vec3.ZERO);
        if(level.isClientSide()) return;
        Integer duration = DURATIONS.remove(player.getUUID());
        if (duration == null || duration <= 15) duration = 15;
        for (LivingEntity entity : entities) {
            if(entity.equals(player))continue;
            entity.hurt(player.damageSources().playerAttack(player), 10.0f * duration/30.0f);
            entity.setDeltaMovement(deltaMovement.scale(0.7).add(0.0, 0.1, 0.0));
        }
    }
}
