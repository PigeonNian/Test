package dev.anvilcraft.addon.template.item;

import dev.anvilcraft.addon.template.AnvilCraftAddonTemplate;
import dev.anvilcraft.addon.template.init.AddonSounds;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DoomFistItem extends Item {
    // 蓄力到最大速度的时间（ticks）
    private static final int MAX_CHARGE_TIME = 30; // 1.5秒（30 ticks）
    // 允许的最大蓄力时间（ticks），超过后自动释放
    private static final int MAX_HOLD_TIME = 40; // 2.0秒（40 ticks）
    // 冷却时间（ticks）
    private static final int COOLDOWN_TIME = 80; // 4秒（80 ticks）
    // 突进无摩擦力持续时间（ticks）
    private static final int DASH_DURATION = 5; // 0.25秒（5 ticks）
    // 最小和最大速度
    private static final double MIN_SPEED = 1.5;
    private static final double MAX_SPEED = 3.0;
    // 最小和最大伤害（1颗心 = 2点伤害）
    private static final float MIN_DAMAGE = 5.0f; // 2.5颗心
    private static final float MAX_DAMAGE = 10.0f; // 5颗心
    // 最小和最大击退强度
    private static final double MIN_KNOCKBACK = 1.5;
    private static final double MAX_KNOCKBACK = 3.5;
    // 撞墙额外伤害（1颗心 = 2点伤害）
    private static final float MIN_WALL_DAMAGE = 5.0f; // 2.5颗心
    private static final float MAX_WALL_DAMAGE = 10.0f; // 5颗心
    // 晕眩时间（ticks）
    private static final int MIN_STUN_DURATION = 10; // 0.5秒
    private static final int MAX_STUN_DURATION = 30; // 1.5秒
    // 检测范围
    private static final double DETECTION_RANGE = 4.0;
    // 检测盒子膨胀大小
    private static final double DETECTION_INFLATE = 1.5;
    
    // 跟踪突进状态：玩家UUID -> 突进信息
    private static final Map<UUID, DashInfo> dashingPlayers = new ConcurrentHashMap<>();
    
    // 跟踪是否播放了蓄力音效：玩家UUID -> 是否播放
    private static final Map<UUID, Boolean> chargeAudioPlayed = new ConcurrentHashMap<>();
    
    // 跟踪被击退生物的信息：生物UUID -> 击退信息
    private static final Map<UUID, KnockbackInfo> knockbackEntities = new ConcurrentHashMap<>();
    
    // 击退信息类
    private static class KnockbackInfo {
        Vec3 lastPosition;
        Vec3 knockbackVelocity;
        float wallDamage;
        int stunDuration; // 晕眩时长
        Player attacker;
        int ticksRemaining; // 剩余监测时间
        
        KnockbackInfo(Vec3 lastPosition, Vec3 knockbackVelocity, float wallDamage, int stunDuration, Player attacker) {
            this.lastPosition = lastPosition;
            this.knockbackVelocity = knockbackVelocity;
            this.wallDamage = wallDamage;
            this.stunDuration = stunDuration;
            this.attacker = attacker;
            this.ticksRemaining = 10; // 监测10个tick
        }
    }
    
    // 突进信息类
    private static class DashInfo {
        Vec3 velocity;
        int remainingTicks;
        float damage;
        double knockback; // 击退强度
        float wallDamage; // 撞墙伤害
        int stunDuration; // 晕眩时长
        Player player;
        Set<UUID> hitEntities; // 记录已经击中的生物,避免重复伤害
        
        DashInfo(Vec3 velocity, int remainingTicks, float damage, double knockback, float wallDamage, int stunDuration, Player player) {
            this.velocity = velocity;
            this.remainingTicks = remainingTicks;
            this.damage = damage;
            this.knockback = knockback;
            this.wallDamage = wallDamage;
            this.stunDuration = stunDuration;
            this.player = player;
            this.hitEntities = new java.util.HashSet<>();
        }
    }
    
    public DoomFistItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        // 检查是否在冷却中
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(player.getItemInHand(usedHand));
        }
        
        // 只在服务端生成随机数并记录，确保客户端和服务端同步
        if (!level.isClientSide()) {
            boolean playedAudio = player.getRandom().nextFloat() < 0.5f;
            chargeAudioPlayed.put(player.getUUID(), playedAudio);
            
            // 在服务端播放音效
            if (playedAudio) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), 
                    AddonSounds.DOOM_FIST_CHARGE.get(), SoundSource.PLAYERS, 1.3f, 1.0f);
            }
        }
        
        // 开始蓄力
        player.startUsingItem(usedHand);
        return InteractionResultHolder.consume(player.getItemInHand(usedHand));
    }
    
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        // 计算已使用时间
        int useDuration = this.getUseDuration(stack, entity) - remainingUseDuration;
        
        // 达到最大持有时间（2.0秒）时自动释放并执行突进
        if (useDuration >= MAX_HOLD_TIME) {
            if (entity instanceof Player player) {
                // 执行突进
                performDash(player, level, stack, useDuration);
            }
            entity.stopUsingItem();
        }
    }
    
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!(entity instanceof Player player)) {
            return;
        }
        
        // 计算蓄力时间（蓄力1秒达到最大速度）
        int useDuration = this.getUseDuration(stack, entity) - timeLeft;
        
        // 执行突进
        performDash(player, level, stack, useDuration);
    }
    
    /**
     * 执行突进动作
     */
    private void performDash(Player player, Level level, ItemStack stack, int useDuration) {
        float chargeRatio = Math.min((float) useDuration / MAX_CHARGE_TIME, 1.0f);
        
        // 停止所有玩家音效
        player.stopUsingItem();
        
        // 判断是否播放突刺音效（只在服务端执行）
        if (!level.isClientSide()) {
            Boolean playedCharge = chargeAudioPlayed.remove(player.getUUID());
            boolean shouldPlayDash = false;
            
            if (playedCharge != null && playedCharge) {
                // 播放了蓄力音效，只有蓄力时间>=1.5秒才播放突刺音效
                if (useDuration >= MAX_CHARGE_TIME) {
                    shouldPlayDash = true;
                }
            } else {
                // 没有播放蓄力音效，突刺音效一定播放
                shouldPlayDash = true;
            }
            
            if (shouldPlayDash) {
                level.playSound(null, player.getX(), player.getY(), player.getZ(), 
                    AddonSounds.DOOM_FIST_DASH.get(), SoundSource.PLAYERS, 1.95f, 1.0f);
            }
        }
        
        // 根据蓄力时间计算速度（从MIN_SPEED到MAX_SPEED线性增长）
        double dashSpeed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * chargeRatio;
        
        // 根据蓄力时间计算伤害（从MIN_DAMAGE到MAX_DAMAGE线性增长）
        float damage = MIN_DAMAGE + (MAX_DAMAGE - MIN_DAMAGE) * chargeRatio;
        
        // 根据蓄力时间计算击退强度（从MIN_KNOCKBACK到MAX_KNOCKBACK线性增长）
        double knockback = MIN_KNOCKBACK + (MAX_KNOCKBACK - MIN_KNOCKBACK) * chargeRatio;
        
        // 根据蓄力时间计算撞墙伤害（从MIN_WALL_DAMAGE到MAX_WALL_DAMAGE线性增长）
        float wallDamage = MIN_WALL_DAMAGE + (MAX_WALL_DAMAGE - MIN_WALL_DAMAGE) * chargeRatio;
        
        // 根据蓄力时间计算晕眩时长（从MIN_STUN_DURATION到MAX_STUN_DURATION线性增长）
        int stunDuration = (int) (MIN_STUN_DURATION + (MAX_STUN_DURATION - MIN_STUN_DURATION) * chargeRatio);
        
        // 获取玩家当前朝向
        Vec3 lookDirection = player.getLookAngle();
        
        // 提取水平方向分量（忽略垂直方向）
        Vec3 horizontalDirection = new Vec3(lookDirection.x, 0.0, lookDirection.z);
        
        // 归一化水平方向向量，确保无论视角朝向如何，水平动量大小一致
        Vec3 normalizedHorizontal = horizontalDirection.normalize();
        
        // 应用计算出的突进速度
        Vec3 dashVector = normalizedHorizontal.scale(dashSpeed);
        
        // 设置玩家的移动向量
        player.setDeltaMovement(dashVector);
        
        // 设置玩家为无重力状态，持续一段时间以减少摩擦力影响
        player.setNoGravity(true);
        
        // 记录突进状态，用于每个tick维持速度并检测碰撞
        dashingPlayers.put(player.getUUID(), new DashInfo(dashVector, DASH_DURATION, damage, knockback, wallDamage, stunDuration, player));
        
        // 如果在服务端，使用延迟任务恢复重力
        if (!level.isClientSide()) {
            restoreGravityAfterDelay(player, level);
        }
        
        // 设置冷却时间（6秒 = 120 ticks）
        player.getCooldowns().addCooldown(this, COOLDOWN_TIME);
        
        if (!level.isClientSide()) {
            AnvilCraftAddonTemplate.LOGGER.info("火箭重拳 - 蓄力时间: {}ticks, 蓄力比例: {}, 速度: {}, 伤害: {}, 击退: {}, 撞墙伤害: {}, 晕眩: {}ticks, 冷却: 6秒",
                useDuration, chargeRatio, dashSpeed, damage, knockback, wallDamage, stunDuration);
        }
    }
    
    /**
     * 延迟恢复玩家重力
     */
    private void restoreGravityAfterDelay(Player player, Level level) {
        // 使用新线程延迟执行
        new Thread(() -> {
            try {
                // 等待突进持续时间
                Thread.sleep(DASH_DURATION * 50); // tick转毫秒
                // 在主线程中恢复重力
                level.getServer().execute(() -> {
                    if (player != null && !player.isRemoved()) {
                        player.setNoGravity(false);
                        // 清除突进状态
                        dashingPlayers.remove(player.getUUID());
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 静态方法，用于在玩家tick时调用，维持突进速度并检测碰撞
     */
    public static void tickDashingPlayer(Player player) {
        UUID uuid = player.getUUID();
        DashInfo info = dashingPlayers.get(uuid);
        
        if (info != null) {
            // 每个tick重新设置速度，无视空气阻力
            player.setDeltaMovement(info.velocity);
            
            // 每个tick都检测并攻击路径上的生物
            boolean hitEntity = detectAndAttackEntities(player, info);
            
            // 如果是第一次击中生物，立即停止突刺
            if (hitEntity) {
                player.setDeltaMovement(0.0,0.0,0.0);
                stopDash(player, uuid);
                return;
            }
            
            info.remainingTicks--;
            
            // 时间到达，清除状态
            if (info.remainingTicks <= 0) {
                stopDash(player, uuid);
            }
        }
    }
    
    /**
     * 检测并攻击面前的生物
     * @return 是否本次击中了新的生物
     */
    private static boolean detectAndAttackEntities(Player player, DashInfo info) {
        Level level = player.level();
        if (level.isClientSide()) {
            return false; // 只在服务端处理
        }
        
        // 获取玩家位置(使用眼睛高度)
        Vec3 playerPos = player.getEyePosition();
        Vec3 lookDirection = player.getLookAngle();
        // 使用水平方向进行检测
        Vec3 horizontalLook = new Vec3(lookDirection.x, 0.0, lookDirection.z).normalize();
        
        // 创建以玩家为中心的检测范围AABB
        AABB detectionBox = player.getBoundingBox().inflate(DETECTION_RANGE, 2.0, DETECTION_RANGE);
        
        // 获取范围内的所有生物实体
        List<LivingEntity> entities = level.getEntitiesOfClass(
            LivingEntity.class,
            detectionBox,
            entity -> entity != player && entity.isAlive()
        );
        
        boolean hitNewEntity = false;
        
        // 对每个生物造成伤害和击退
        for (LivingEntity entity : entities) {
            // 检查是否已经击中过这个生物
            if (info.hitEntities.contains(entity.getUUID())) {
                continue; // 已经击中过，跳过
            }
            
            // 获取到实体中心的向量
            Vec3 entityCenter = entity.getBoundingBox().getCenter();
            Vec3 toEntity = entityCenter.subtract(playerPos);
            double distance = toEntity.length();
            
            // 如果距离太近(小于0.5格),直接判定为击中
            if (distance < 1.5) {
                performHit(player, entity, info, level, 1.0);
                info.hitEntities.add(entity.getUUID()); // 记录已击中
                hitNewEntity = true;
                break; // 击中后立即返回
            }
            
            // 计算水平方向
            Vec3 toEntityHorizontal = new Vec3(toEntity.x, 0.0, toEntity.z);
            double horizontalDistance = toEntityHorizontal.length();
            
            // 距离检查
            if (horizontalDistance > DETECTION_RANGE) {
                continue;
            }
            
            // 归一化并计算点积
            Vec3 toEntityNormalized = toEntityHorizontal.normalize();
            double dotProduct = horizontalLook.dot(toEntityNormalized);
            
            // 放宽判定角度,从0.5降到0.3
            if (dotProduct > 0.3) { // 在玩家前方更宽的锥形范围内
                performHit(player, entity, info, level, dotProduct);
                info.hitEntities.add(entity.getUUID()); // 记录已击中
                hitNewEntity = true;
                break; // 击中后立即返回
            }
        }
        
        return hitNewEntity;
    }
    
    /**
     * 执行击中效果
     */
    private static void performHit(Player player, LivingEntity entity, DashInfo info, Level level, double dotProduct) {
        // 造成伤害
        DamageSource damageSource = level.damageSources().playerAttack(player);
        entity.hurt(damageSource, info.damage);
        
        // 播放击中音效
        if (!level.isClientSide()) {
            level.playSound(null, entity.getX(), entity.getY(), entity.getZ(), 
                SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 
                0.8f, 1.2f);
        }
        
        // 使用玩家冲刺方向作为击退方向(与玩家冲刺方向相同)
        Vec3 knockbackDirection = new Vec3(info.velocity.x, 0.0, info.velocity.z).normalize();
        
        // 强制击退(纯水平方向,清除垂直动量)，使用蓄力计算出的击退强度
        Vec3 knockbackVelocity = knockbackDirection.scale(info.knockback);
        entity.setDeltaMovement(knockbackVelocity.x, 0, knockbackVelocity.z);
        
        // 记录被击退生物的信息，用于检测撞墙
        knockbackEntities.put(entity.getUUID(), new KnockbackInfo(
            entity.position(),
            knockbackVelocity,
            info.wallDamage,
            info.stunDuration,
            player
        ));
        
        AnvilCraftAddonTemplate.LOGGER.info("火箭重拳击中 {} - 伤害: {}, 击退: {}, 撞墙伤害: {}, 点积: {}", 
            entity.getName().getString(), info.damage, info.knockback, info.wallDamage, dotProduct);
    }
    
    /**
     * 停止突进
     */
    private static void stopDash(Player player, UUID uuid) {
        dashingPlayers.remove(uuid);
        player.setNoGravity(false);
        // 完全清除所有动量,将玩家固定在原地
        player.setDeltaMovement(0, 0, 0);
    }
    
    /**
     * 检测被击退生物是否撞墙
     */
    public static void tickKnockbackEntity(LivingEntity entity) {
        UUID uuid = entity.getUUID();
        KnockbackInfo info = knockbackEntities.get(uuid);
        
        if (info != null && !entity.level().isClientSide()) {
            // 移除受伤冷却,确保撞墙伤害能立即生效
            entity.invulnerableTime = 0;
            entity.hurtTime = 0;
            
            // 检测是否撞墙:利用horizontalCollision标志直接判断
            if (entity.horizontalCollision) {
                // 撞墙了!造成额外伤害
                DamageSource damageSource = entity.level().damageSources().playerAttack(info.attacker);
                entity.hurt(damageSource, info.wallDamage);
                
                // 播放撞墙音效
                entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(), 
                    SoundEvents.ANVIL_LAND, SoundSource.PLAYERS,
                    1.0f, 0.8f);
                
                // 施加晕眩效果（缓慢255和虚弱组合）
                entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, info.stunDuration, 255, false, false));
                entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, info.stunDuration, 255, false, false));
                
                AnvilCraftAddonTemplate.LOGGER.info("{} 撞墙 - 额外伤害: {}, 晕眩: {}ticks", 
                    entity.getName().getString(), info.wallDamage, info.stunDuration);
                
                // 清除追踪
                knockbackEntities.remove(uuid);
            } else {
                // 减少剩余时间
                info.ticksRemaining--;
                
                // 时间到了,清除追踪
                if (info.ticksRemaining <= 0) {
                    knockbackEntities.remove(uuid);
                }
            }
        }
    }
    
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        // 物品可使用的最大时间（ticks）
        return 72000; // 足够长的时间，让玩家可以一直按住
    }
    
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // 使用弓的动画
            return UseAnim.BRUSH;
    }
}
