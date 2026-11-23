package com.nichirin.breathing;

import com.nichirin.NichirinMod;
import com.nichirin.item.WaterNichirinBladeItem;
import com.nichirin.network.BreathSyncPayload;
import com.nichirin.network.BreathingActionPayload;
import com.nichirin.network.HitPhysicsPayload;
import com.nichirin.network.WaterVfxPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端双流派呼吸法核心引擎（水之呼吸 & 日之呼吸·火之神神乐）：
 * 管理同一把日轮刀的流派切换、赫刀动态显现、呼吸气力槽、全集中增益与全 16 套人刀合一剑型！
 */
public final class BreathingManager {
    public static final float MAX_BREATH = 100.0F;
    private static final float PASSIVE_REGEN_PER_TICK = 0.42F;
    private static final float INHALE_REGEN_PER_TICK = 2.65F;
    private static final float TOTAL_CONC_DRAIN_PER_TICK = 0.24F;

    private static final Identifier TOTAL_CONC_SPEED_ID =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "total_conc_speed");
    private static final Identifier TOTAL_CONC_ATK_SPEED_ID =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "total_conc_attack_speed");
    private static final Identifier HAZE_SPEED_ID =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "haze_mirage_speed");

    private static final AttributeModifier TOTAL_CONC_SPEED_MOD =
            new AttributeModifier(TOTAL_CONC_SPEED_ID, 0.22, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier TOTAL_CONC_ATK_SPEED_MOD =
            new AttributeModifier(TOTAL_CONC_ATK_SPEED_ID, 0.20, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    private static final AttributeModifier HAZE_SPEED_MOD =
            new AttributeModifier(HAZE_SPEED_ID, 0.40, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

    private static final Map<UUID, PlayerBreathState> STATES = new ConcurrentHashMap<>();

    private BreathingManager() {}

    public static PlayerBreathState getState(UUID uuid) {
        return STATES.computeIfAbsent(uuid, k -> new PlayerBreathState());
    }

    public static void removePlayer(UUID uuid) { STATES.remove(uuid); }

    public static void clearAll() { STATES.clear(); }

    public static boolean isHoldingWaterBlade(Player player) {
        return WaterNichirinBladeItem.isWaterBlade(player.getMainHandItem());
    }

    /**
     * 处理客户端呼吸法指令包（含 X 键实时切换水/日呼吸流派）
     */
    public static void handleActionPayload(BreathingActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        PlayerBreathState state = getState(player.getUUID());
        ServerLevel level = player.level();
        boolean isSun = state.style == BreathingStyle.SUN;

        switch (payload.action()) {
            case BreathingActionPayload.ACTION_SWITCH_STYLE -> {
                if (!isHoldingWaterBlade(player)) return;
                // 当前专注将「水之呼吸 · 全十一型」100% 还原为标杆；日之呼吸保留底层资产待独立重构十三型！
                state.style = BreathingStyle.WATER;
                updateHeldBladeVisual(player, state);
                player.sendOverlayMessage(Component.translatable("message.nichirin.sun_wip_focus_water")
                        .withStyle(ChatFormatting.AQUA));
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.72F, 1.35F);
                syncPlayer(player, state);
            }
            case BreathingActionPayload.ACTION_CAST_FORM -> {
                if (!isHoldingWaterBlade(player)) return;
                state.style = BreathingStyle.WATER;
                WaterForm baseForm = WaterForm.fromOrdinal(payload.arg1());
                int ctx = payload.arg2();
                state.chargingMode = 0;
                state.chargeTicks = 0;
                tryCastForm(player, level, state, baseForm, ctx);
            }
            case BreathingActionPayload.ACTION_FLUX_RELEASE -> {
                state.fluxHolding = false;
            }
            case BreathingActionPayload.ACTION_START_CHARGE -> {
                if (!isHoldingWaterBlade(player)) return;
                int mode = Mth.clamp(payload.arg1(), 1, 2);
                state.chargingMode = mode;
                state.chargeTicks = 0;
                updateHeldBladeVisual(player, state); // 日之呼吸蓄力时立即点燃炽红赫刀！

                WaterVfxPayload.sendNear(
                        level, WaterVfxPayload.KIND_CHARGE_AURA, player.getId(),
                        player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), mode == 1 ? 4.4F : 5.0F, mode, 0.0F,
                        mode == 1 ? 36 : 16, state.totalConcentration, mode == 2, isSun
                );
                if (isSun) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLAZE_AMBIENT, SoundSource.PLAYERS, 0.85F, 0.95F);
                } else {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.AMBIENT_UNDERWATER_ENTER, SoundSource.PLAYERS, 0.85F, mode == 1 ? 1.15F : 0.72F);
                }
            }
            case BreathingActionPayload.ACTION_STOP_CHARGE -> {
                state.chargingMode = 0;
                state.chargeTicks = 0;
                updateHeldBladeVisual(player, state);
            }
            case BreathingActionPayload.ACTION_SET_INHALE -> {
                boolean inhale = payload.arg1() != 0 && isHoldingWaterBlade(player);
                if (inhale && !state.inhaling) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.75F, 1.25F);
                }
                state.inhaling = inhale;
                syncPlayer(player, state);
            }
            case BreathingActionPayload.ACTION_TOGGLE_TOTAL_CONCENTRATION -> {
                if (!isHoldingWaterBlade(player)) return;
                if (state.totalConcentration) {
                    setTotalConcentration(player, state, false);
                    player.sendOverlayMessage(Component.translatable("message.nichirin.total_conc_off")
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    if (state.breath < 18.0F && !player.getAbilities().instabuild) {
                        player.sendOverlayMessage(Component.translatable("message.nichirin.low_breath")
                                .withStyle(isSun ? ChatFormatting.GOLD : ChatFormatting.AQUA));
                        return;
                    }
                    setTotalConcentration(player, state, true);
                    announceTotalConcentration(player, level, state);
                    tryTriggerInstinctRead(player, level, state);
                }
                syncPlayer(player, state);
            }
            case BreathingActionPayload.ACTION_BASIC_SWING -> {
                if (!isHoldingWaterBlade(player)) return;
                long now = level.getGameTime();
                if (now - state.lastBasicSweepTick < 4L) return;
                state.lastBasicSweepTick = now;
                Vec3 fwd = horizontalLook(player);
                Vec3 center = player.position().add(fwd.scale(1.25)).add(0.0, 1.05, 0.0);
                BladeWorldPhysics.processBladeSweep(player, level, state, center, fwd,
                        state.totalConcentration ? 3.5F : 2.9F, 0.12, isSun, state.totalConcentration);
            }
            default -> {}
        }
    }

    private static void announceTotalConcentration(ServerPlayer player, ServerLevel level, PlayerBreathState state) {
        boolean isSun = state.style == BreathingStyle.SUN;
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_BREATH_PULSE, player.getId(),
                player.getX(), player.getY() + 0.9, player.getZ(),
                player.getYRot(), 4.0F, 1.0F, 0.0F,
                16, true, true, isSun
        );
        if (isSun) {
            player.sendOverlayMessage(Component.translatable("message.nichirin.red_blade_on")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.15F, 0.92F);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.95F, 1.15F);
        } else {
            player.sendOverlayMessage(Component.translatable("message.nichirin.total_conc_on")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 0.85F, 1.35F);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.4F);
        }
    }

    public static void tickPlayer(ServerPlayer player) {
        PlayerBreathState state = getState(player.getUUID());
        ServerLevel level = player.level();
        boolean holdingBlade = isHoldingWaterBlade(player);
        boolean isSun = state.style == BreathingStyle.SUN;

        if (state.globalRecoveryTicks > 0) state.globalRecoveryTicks--;
        if (state.fallImmuneTicks > 0) state.fallImmuneTicks--;
        if (state.dragonRushTicks > 0) state.dragonRushTicks--;
        if (state.instinctCounterWindowTicks > 0) state.instinctCounterWindowTicks--;
        if (state.serverHitstopTicks > 0.0F) {
            state.serverHitstopTicks = Math.max(0.0F, state.serverHitstopTicks - 1.0F);
        }
        tickFrozenTargets(level, state);
        for (int i = 0; i < state.formCooldowns.length; i++) {
            if (state.formCooldowns[i] > 0) state.formCooldowns[i]--;
        }
        if (player.tickCount % 40 == 0 && (!state.exposedTargets.isEmpty() || !state.threadCooldowns.isEmpty())) {
            long now = level.getGameTime();
            state.exposedTargets.entrySet().removeIf(e -> e.getValue() <= now);
            state.threadCooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        }

        if (!holdingBlade) {
            if (state.inhaling) state.inhaling = false;
            state.fluxHolding = false;
            if (state.chargingMode != 0) {
                state.chargingMode = 0;
                state.chargeTicks = 0;
            }
            if (state.totalConcentration) {
                setTotalConcentration(player, state, false);
            }
            if (state.fakeRainbowTicks > 0) {
                state.fakeRainbowTicks = 0;
                updateHazeSpeed(player, false);
            }
        } else {
            // 实时保持手持日轮刀模型与当前流派/赫刀状态同步
            updateHeldBladeVisual(player, state);
        }

        float prevBreath = state.breath;
        boolean wetEnv = player.isInWaterOrRain();
        float waterRegenMult = (!isSun && wetEnv) ? 1.35F : 1.0F;
        if (state.inhaling && holdingBlade) {
            state.breath = Math.min(MAX_BREATH, state.breath + INHALE_REGEN_PER_TICK * waterRegenMult);
            if (player.tickCount % 5 == 0) {
                Vec3 look = player.getLookAngle();
                level.sendParticles(isSun ? ParticleTypes.FLAME : ParticleTypes.CLOUD,
                        player.getX() + look.x * 0.45, player.getEyeY() - 0.15, player.getZ() + look.z * 0.45,
                        3, 0.18, 0.12, 0.18, 0.02);
                level.sendParticles(isSun ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
                        player.getX(), player.getY() + 0.8, player.getZ(),
                        isSun ? 2 : 5, 0.55, 0.45, 0.55, 0.04);
                WaterVfxPayload.sendNear(
                        level, WaterVfxPayload.KIND_BREATH_PULSE, player.getId(),
                        player.getX(), player.getY() + 0.25, player.getZ(),
                        player.getYRot(), 2.2F, 0.0F, 0.0F,
                        10, state.totalConcentration, false, isSun
                );
            }
            if (state.breath >= MAX_BREATH && !state.totalConcentration) {
                setTotalConcentration(player, state, true);
                announceTotalConcentration(player, level, state);
            }
        } else if (state.totalConcentration) {
            if (!player.getAbilities().instabuild) {
                state.breath = Math.max(0.0F, state.breath - TOTAL_CONC_DRAIN_PER_TICK);
            }
            if (player.tickCount % 4 == 0) {
                level.sendParticles(isSun ? ParticleTypes.FLAME : ParticleTypes.DRIPPING_WATER,
                        player.getX(), player.getY() + 1.0, player.getZ(),
                        2, 0.4, 0.5, 0.4, 0.02);
                // 旷野之息物理细节：雨中/水中高举炽红赫刀，高温刀锋不断蒸腾出细密白汽！
                if (isSun && wetEnv) {
                    level.sendParticles(ParticleTypes.CLOUD,
                            player.getX(), player.getY() + 1.15, player.getZ(),
                            3, 0.35, 0.35, 0.35, 0.03);
                    if (player.tickCount % 20 == 0) {
                        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                                SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.22F, 1.85F);
                    }
                }
            }
            if (state.breath <= 0.01F) {
                setTotalConcentration(player, state, false);
                player.sendOverlayMessage(Component.translatable("message.nichirin.total_conc_off")
                        .withStyle(ChatFormatting.GRAY));
            }
        } else {
            if (state.breath < MAX_BREATH && state.chargingMode == 0 && state.deadCalmTicks <= 0 && state.fakeRainbowTicks <= 0) {
                state.breath = Math.min(MAX_BREATH, state.breath + PASSIVE_REGEN_PER_TICK * waterRegenMult);
            }
        }

        if (state.chargingMode != 0) {
            state.chargeTicks++;
            if (state.chargeTicks % 6 == 0) {
                level.sendParticles(isSun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                        player.getX(), player.getY() + 0.9, player.getZ(),
                        8, 0.9, 0.5, 0.9, 0.06);
                if (state.chargingMode == 1) {
                    float pitch = 0.85F + Math.min(0.65F, state.chargeTicks * 0.018F);
                    level.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.65F, pitch);
                }
            }
        }

        // 水·拾壹之型 凪领域
        if (state.deadCalmTicks > 0) {
            tickDeadCalmDomain(player, level, state);
            state.deadCalmTicks--;
        }

        // 日·幻日虹 高速残影态
        if (state.fakeRainbowTicks > 0) {
            state.fakeRainbowTicks--;
            if (player.tickCount % 3 == 0) {
                level.sendParticles(ParticleTypes.FLAME,
                        player.getX(), player.getY() + 0.9, player.getZ(),
                        4, 0.35, 0.5, 0.35, 0.03);
            }
            if (state.fakeRainbowTicks == 0) {
                updateHazeSpeed(player, false);
            }
        }

        if (!state.activeTasks.isEmpty()) {
            // 使用快照遍历，允许终结技/连段招式在收尾瞬间安全派生二段 ActiveFormTask，杜绝 ConcurrentModificationException！
            List<ActiveFormTask> snapshot = new ArrayList<>(state.activeTasks);
            for (ActiveFormTask task : snapshot) {
                if (!task.tick(player, level, state)) {
                    state.activeTasks.remove(task);
                }
            }
        }

        state.syncTimer++;
        boolean changed = Math.abs(prevBreath - state.breath) > 0.35F
                || state.deadCalmTicks > 0 || state.fakeRainbowTicks > 0 || state.dirty;
        if (state.dirty || (changed && state.syncTimer >= 2) || state.syncTimer >= 20) {
            syncPlayer(player, state);
        }
    }

    private static void tickDeadCalmDomain(ServerPlayer player, ServerLevel level, PlayerBreathState state) {
        AABB domainBox = player.getBoundingBox().inflate(7.8, 4.5, 7.8);

        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, domainBox,
                p -> p.isAlive() && p.getOwner() != player && !p.onGround());
        for (Projectile proj : projectiles) {
            double px = proj.getX(), py = proj.getY(), pz = proj.getZ();
            proj.discard();

            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                    px, py, pz, player.getYRot(),
                    1.35F, 4.0F, 0.0F,
                    12, state.totalConcentration, false, false
            );
            level.sendParticles(ParticleTypes.SPLASH, px, py, pz, 8, 0.2, 0.2, 0.2, 0.08);
            level.playSound(null, px, py, pz, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.85F, 1.75F);
            level.playSound(null, px, py, pz, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.65F, 1.65F);

            state.breath = Math.min(MAX_BREATH, state.breath + 5.0F);
            state.dirty = true;
        }

        if (state.deadCalmTicks % 8 == 0) {
            AABB closeBox = player.getBoundingBox().inflate(4.8, 2.8, 4.8);
            float dmg = state.totalConcentration ? 7.2F : 5.5F;
            List<LivingEntity> intruders = level.getEntitiesOfClass(LivingEntity.class, closeBox,
                    e -> e.isAlive() && e != player && (e instanceof Enemy || e.getLastHurtByMob() == player));
            for (LivingEntity target : intruders) {
                target.setInvulnerableTime(0);
                if (target.hurtServer(level, level.damageSources().playerAttack(player), dmg)) {
                    Vec3 away = target.position().subtract(player.position());
                    Vec3 push = away.horizontalDistanceSqr() > 1.0E-4
                            ? new Vec3(away.x, 0, away.z).normalize().scale(0.35)
                            : Vec3.ZERO;
                    target.push(push.x, 0.14, push.z);
                    syncEntityVelocity(target);
                    WaterVfxPayload.sendNear(
                            level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                            target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ(),
                            player.getYRot(), 1.65F, 5.0F, 0.0F,
                            12, state.totalConcentration, false, false
                    );
                }
            }
        }
    }

    /** 水·拾壹之型 凪完美格挡弹反 */
    public static boolean tryDeadCalmParry(ServerPlayer player, Entity attacker) {
        PlayerBreathState state = getState(player.getUUID());
        if (state.deadCalmTicks <= 0 || !isHoldingWaterBlade(player)) return false;

        ServerLevel level = player.level();
        state.breath = Math.min(MAX_BREATH, state.breath + 22.0F);
        state.dirty = true;

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.3F, 1.45F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.1F, 1.55F);
        player.sendOverlayMessage(Component.translatable("message.nichirin.dead_calm_parry")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));

        if (attacker instanceof LivingEntity livingAttacker && livingAttacker != player
                && livingAttacker.isAlive() && livingAttacker.distanceToSqr(player) <= 144.0) {
            float counterDmg = state.totalConcentration ? 20.0F : 15.5F;
            Vec3 away = livingAttacker.position().subtract(player.position());
            Vec3 dir = away.horizontalDistanceSqr() > 1.0E-4
                    ? new Vec3(away.x, 0.0, away.z).normalize() : player.getLookAngle();
            // 弹反瞬间强制对攻击者拉出高光「隙之线」持续 3.5s，并触发直觉看破！
            long now = level.getGameTime();
            state.exposedTargets.put(livingAttacker.getId(), now + 75L);
            state.threadCooldowns.remove(livingAttacker.getId());
            applyNichirinStrike(player, level, state, livingAttacker, counterDmg,
                    new Vec3(dir.x * 0.95, 0.42, dir.z * 0.95), true, false, 2.0F, true);
            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_INTUITION_PULSE, player.getId(),
                    livingAttacker.getX(), livingAttacker.getY() + livingAttacker.getBbHeight() * 0.78, livingAttacker.getZ(),
                    player.getYRot(), (float) livingAttacker.getId(), 1.0F, 0.0F,
                    70, state.totalConcentration, true, false
            );

            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                    livingAttacker.getX(), livingAttacker.getY() + livingAttacker.getBbHeight() * 0.55, livingAttacker.getZ(),
                    player.getYRot(), 2.8F, 9.0F, 0.0F,
                    18, state.totalConcentration, true, false
            );
        } else {
            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                    player.getX(), player.getY() + 1.1, player.getZ(),
                    player.getYRot(), 2.6F, 7.0F, 0.0F,
                    16, state.totalConcentration, true, false
            );
        }
        syncPlayer(player, state);
        return true;
    }

    /** 日·幻日虹 残影完美闪避 + 炽炎反杀！ */
    public static boolean tryFakeRainbowDodge(ServerPlayer player, Entity attacker) {
        PlayerBreathState state = getState(player.getUUID());
        if (state.fakeRainbowTicks <= 0 || !isHoldingWaterBlade(player)) return false;

        ServerLevel level = player.level();
        state.breath = Math.min(MAX_BREATH, state.breath + 22.0F);
        state.dirty = true;

        // 闪避触发时向侧前方高速残影滑步
        Vec3 look = horizontalLook(player);
        Vec3 side = new Vec3(-look.z, 0.0, look.x);
        player.setDeltaMovement(side.x * 1.05 + look.x * 0.55, 0.24, side.z * 1.05 + look.z * 0.55);
        syncEntityVelocity(player);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.25F, 1.25F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.25F, 1.35F);
        player.sendOverlayMessage(Component.translatable("message.nichirin.haze_dodge")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        if (attacker instanceof LivingEntity living && living != player
                && living.isAlive() && living.distanceToSqr(player) <= 169.0) {
            float dmg = state.totalConcentration ? 23.5F : 18.0F;
            long now = level.getGameTime();
            state.exposedTargets.put(living.getId(), now + 75L);
            state.threadCooldowns.remove(living.getId());
            applyNichirinStrike(player, level, state, living, dmg,
                    new Vec3(look.x * 0.85, 0.48, look.z * 0.85), true, true, 2.0F, true);
            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_INTUITION_PULSE, player.getId(),
                    living.getX(), living.getY() + living.getBbHeight() * 0.78, living.getZ(),
                    player.getYRot(), (float) living.getId(), 1.0F, 0.0F,
                    70, state.totalConcentration, true, true
            );

            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                    living.getX(), living.getY() + living.getBbHeight() * 0.55, living.getZ(),
                    player.getYRot(), 3.0F, 10.0F, 0.0F,
                    18, state.totalConcentration, true, true
            );
        } else {
            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DEAD_CALM_COUNTER, player.getId(),
                    player.getX(), player.getY() + 1.1, player.getZ(),
                    player.getYRot(), 2.8F, 8.0F, 0.0F,
                    16, state.totalConcentration, true, true
            );
        }
        syncPlayer(player, state);
        return true;
    }

    private static void tryCastForm(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, WaterForm baseForm, int ctx
    ) {
        if (state.fluxActive) return;
        // 允许在残心后摇最后瞬间（<= 2 tick）无缝派生下一型；玖之型·水流飞沫允许几乎 0 延迟衔接！
        if (state.globalRecoveryTicks > (baseForm == WaterForm.NINTH_SPLASHING_FLOW ? 1 : 0)) return;

        boolean airborne = BreathingActionPayload.unpackAirborne(ctx) || (!player.onGround() && !player.isInWater());
        boolean sneak = BreathingActionPayload.unpackSneak(ctx) || player.isShiftKeyDown();
        int fwdSign = BreathingActionPayload.unpackFwdSign(ctx);
        int strafeSign = BreathingActionPayload.unpackStrafeSign(ctx);
        boolean curving = sneak || strafeSign != 0;

        WaterForm form = baseForm.resolveVariant(airborne, curving);

        int cd = state.formCooldowns[baseForm.ordinal()];
        if (cd > 0 && !player.getAbilities().instabuild) {
            player.sendOverlayMessage(Component.translatable("message.nichirin.on_cooldown")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        float cost = form.getBreathCost();
        if (state.totalConcentration) cost *= 0.85F;
        if (state.breath < cost && !player.getAbilities().instabuild) {
            player.sendOverlayMessage(Component.translatable("message.nichirin.low_breath")
                    .withStyle(ChatFormatting.AQUA));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.65F, 0.75F);
            return;
        }

        if (!player.getAbilities().instabuild) {
            state.breath = Math.max(0.0F, state.breath - cost);
            state.formCooldowns[baseForm.ordinal()] = baseForm.getCooldownTicks();
            ItemStack mainHand = player.getMainHandItem();
            if (!mainHand.isEmpty()) {
                mainHand.hurtAndBreak(form.isUltimate() ? 3 : 1, player, InteractionHand.MAIN_HAND);
            }
        }
        state.globalRecoveryTicks = form.getRecoveryTicks();
        state.lastCastFormOrdinal = form.ordinal();
        state.castCounter++;
        state.inhaling = false;
        state.dirty = true;

        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

        boolean tc = state.totalConcentration;
        boolean sun = false; // 当前版本 100% 专注纯净原版水之呼吸全十一型！

        // 水之呼吸高空水垫缓震直觉：下坠瞬间挥出水呼剑型，水浪自动吃掉下落动能免除摔伤！
        if (form != WaterForm.EIGHTH_WATERFALL_BASIN) {
            BladeWorldPhysics.tryWaterFallCushion(player, level, state);
        }

        switch (form) {
            case FIRST_SURFACE_SLASH -> castFirstSlash(player, level, state, tc, sun);
            case SECOND_WATER_WHEEL -> castSecondWheel(player, level, state, tc, sun);
            case SECOND_LATERAL_WHEEL -> castSecondLateral(player, level, state, tc, sun);
            case THIRD_FLOWING_DANCE -> castThirdFlowingDance(player, level, state, tc);
            case FOURTH_STRIKING_TIDE -> castFourthStrikingTide(player, level, state, tc);
            case FIFTH_BLESSED_RAIN -> castFifthBlessedRain(player, level, state, tc);
            case SIXTH_WHIRLPOOL -> castSixthVortex(player, level, state, tc, sun);
            case SEVENTH_DROP_RIPPLE -> castSeventhDropRipple(player, level, state, tc, false, 0);
            case SEVENTH_DROP_RIPPLE_CURVE -> castSeventhDropRipple(player, level, state, tc, true, strafeSign);
            case EIGHTH_WATERFALL_BASIN -> castEighthWaterfallBasin(player, level, state, tc, airborne);
            case NINTH_SPLASHING_FLOW -> castNinthSplashingFlow(player, level, state, tc, airborne, fwdSign, strafeSign);
            case TENTH_CONSTANT_FLUX -> castTenthConstantFlux(player, level, state, tc);
            case ELEVENTH_DEAD_CALM -> castEleventhDeadCalm(player, level, state, tc);
        }

        updateHeldBladeVisual(player, state);
        syncPlayer(player, state);
    }

    /** R-L-L：水·壹之型 水面斩 / 日·火之神神乐 圆舞（连续力学收紧加速曲线 + 140Hz 共享刀锋扫掠判定！） */
    private static void castFirstSlash(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean sun) {
        final float reach = (sun ? 5.3F : 4.8F) * (tc ? 1.20F : 1.0F);
        final float damage = (sun ? 14.2F : 11.8F) * (tc ? 1.28F : 1.0F);
        final int duration = 15;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_SLASH, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), reach, 4.0F, sun ? 250.0F : 228.0F,
                duration, tc, true, sun
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.72F, sun ? 1.15F : 1.32F);

        state.activeTasks.add(new ActiveFormTask() {
            float animTime = 0.0F;
            boolean burstTriggered = false;
            boolean wakeTriggered = false;
            Vec3 slashFwd = horizontalLook(player);
            Vec3 slashAnchor = player.position().add(0.0, 1.05, 0.0);
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float step = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = animTime / duration;
                animTime = Math.min(duration, animTime + step);
                float curT = animTime / duration;

                Vec3 fwd = horizontalLook(p);
                Vec3 vel = p.getDeltaMovement();
                // 连续力学曲线：0..3 平滑收紧重心阻尼 -> 3..7 钟形加速度前推 -> 7..15 残心自然惯性滑停！
                if (animTime < 3.0F) {
                    p.setDeltaMovement(vel.x * 0.62, Math.max(vel.y * 0.65, -0.05), vel.z * 0.62);
                    syncEntityVelocity(p);
                } else if (animTime <= 7.2F) {
                    if (!burstTriggered) {
                        burstTriggered = true;
                        slashFwd = fwd;
                        slashAnchor = p.position().add(0.0, 1.05, 0.0);
                        Vec3 sweepCenter = slashAnchor.add(fwd.scale(1.45));
                        BladeWorldPhysics.processBladeSweep(p, lvl, st, sweepCenter, fwd, reach, sun ? -0.45 : -0.35, sun, true);
                        lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.38F, sun ? 0.78F : 0.94F);
                    }
                    if (st.serverHitstopTicks > 0.0F) {
                        p.setDeltaMovement(vel.x * 0.22, vel.y * 0.25, vel.z * 0.22);
                    } else {
                        float u = Mth.clamp((animTime - 3.0F) / 4.2F, 0.0F, 1.0F);
                        float accel = Mth.sin(u * Mth.PI) * (sun ? 0.56F : 0.48F);
                        p.setDeltaMovement(
                                Mth.clamp(vel.x * 0.74 + fwd.x * accel, -1.35, 1.35),
                                Math.max(vel.y, 0.08),
                                Mth.clamp(vel.z * 0.74 + fwd.z * accel, -1.35, 1.35)
                        );
                    }
                    syncEntityVelocity(p);
                }

                // 140Hz 共享刀锋连续子步扫描：刀先破空一闪切开敌群！
                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                if (curT >= 0.20F && prevT <= 0.75F) {
                    runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_SURFACE_SLASH, prevT, curT, 0,
                            tc, sun, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 16,
                            reach + 1.2, 0.65, damage * 0.84F, 0.68, 0.24, 1.55F, true);
                }

                // 刀锋挥过后慢 2 tick 的「尾流浪涛/烈焰拍击余波」：顺着刀弧外缘轰然拍飞刚才被切开的敌人！
                if (!wakeTriggered && animTime >= 5.6F) {
                    wakeTriggered = true;
                    Vec3 wakeCenter = slashAnchor.add(slashFwd.scale(reach * 0.52));
                    lvl.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                            wakeCenter.x, wakeCenter.y, wakeCenter.z, 26, reach * 0.42, 0.45, reach * 0.42, 0.16);
                    lvl.playSound(null, wakeCenter.x, wakeCenter.y, wakeCenter.z,
                            sun ? SoundEvents.FIRECHARGE_USE : SoundEvents.TRIDENT_RIPTIDE_1.value(),
                            SoundSource.PLAYERS, 1.22F, sun ? 0.94F : 1.12F);
                    float wakeDmg = damage * 0.28F;
                    AABB wakeBox = new AABB(
                            slashAnchor.x - reach - 0.8, slashAnchor.y - 1.6, slashAnchor.z - reach - 0.8,
                            slashAnchor.x + reach + 0.8, slashAnchor.y + 2.2, slashAnchor.z + reach + 0.8
                    );
                    for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, wakeBox,
                            e -> e.isAlive() && e != p)) {
                        Vec3 rel = new Vec3(target.getX() - slashAnchor.x, 0.0, target.getZ() - slashAnchor.z);
                        double dist = rel.length();
                        if (dist > reach + 0.85) continue;
                        Vec3 outDir = dist > 0.25 ? rel.scale(1.0 / dist) : slashFwd;
                        if (dist > 1.0 && outDir.dot(slashFwd) < (sun ? -0.45 : -0.30)) continue;
                        Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.62, target.getZ());
                        applyNichirinStrike(p, lvl, st, target, wakeDmg, hitPt, outDir,
                                new Vec3(outDir.x * 0.92, 0.34, outDir.z * 0.92),
                                true, sun, 1.35F, false, false);
                    }
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < 11.5F;
            }
        });
    }

    /** R-R-L (地面)：水·贰之型 水车 / 日·碧罗之天（平滑腾空跃弧 + 140Hz 纵翻 395° 刀锋扫掠！） */
    private static void castSecondWheel(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean sun) {
        final float radius = (sun ? 3.9F : 3.5F) * (tc ? 1.20F : 1.0F);
        final float hitDmg = (sun ? 7.2F : 6.2F) * (tc ? 1.28F : 1.0F);
        final int duration = 18;
        state.fallImmuneTicks = 38;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_WHEEL, player.getId(),
                player.getX(), player.getY() + 1.1, player.getZ(),
                player.getYRot(), radius, 0.0F, 7.2F,
                duration, tc, false, sun
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.68F, 1.25F);

        state.activeTasks.add(new ActiveFormTask() {
            float animTime = 0.0F;
            boolean launched = false;
            Vec3 launchForward = horizontalLook(player);
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float step = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = animTime / duration;
                animTime = Math.min(duration, animTime + step);
                float curT = animTime / duration;

                Vec3 vel = p.getDeltaMovement();
                if (animTime < 2.0F) {
                    p.setDeltaMovement(vel.x * 0.58, Math.min(vel.y, 0.04), vel.z * 0.58);
                    syncEntityVelocity(p);
                } else if (animTime <= 5.5F) {
                    if (!launched) {
                        launched = true;
                        launchForward = horizontalLook(p);
                        BladeWorldPhysics.processBladeSweep(p, lvl, st, p.position().add(0, 1.0, 0), launchForward, radius, -1.0, sun, false);
                        lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                sun ? SoundEvents.BLAZE_SHOOT : SoundEvents.TRIDENT_RIPTIDE_3.value(),
                                SoundSource.PLAYERS, 1.25F, sun ? 0.90F : 1.14F);
                    }
                    if (st.serverHitstopTicks <= 0.0F) {
                        float u = Mth.clamp((animTime - 2.0F) / 3.5F, 0.0F, 1.0F);
                        float thrust = (1.0F - u * 0.45F) * (sun ? 0.68F : 0.64F);
                        double targetVy = u < 0.45F ? (sun ? 0.58 : 0.52) : vel.y;
                        p.setDeltaMovement(
                                Mth.lerp(0.55, vel.x, launchForward.x * (1.20 + thrust)),
                                targetVy,
                                Mth.lerp(0.55, vel.z, launchForward.z * (1.20 + thrust))
                        );
                        p.resetFallDistance();
                        syncEntityVelocity(p);
                    }
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                if (curT >= 0.12F) {
                    runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_WATER_WHEEL, prevT, curT, 0,
                            tc, sun, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 4,
                            radius + 1.4, 0.78, hitDmg, 0.68, 0.25, curT > 0.65F ? 1.55F : 1.15F, curT > 0.65F);
                    if (Math.floor(animTime) != Math.floor(animTime - step) && ((int) animTime) % 3 == 0) {
                        lvl.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                                p.getX(), p.getY() + 1.0, p.getZ(), 12, 0.9, 0.8, 0.9, 0.12);
                    }
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < 15.0F;
            }
        });
    }

    /** R-R-L (空中)：水·横水车 / 日·烈日红镜（平滑浮空蓄势 + 140Hz 415° 水平旋刃切扫！） */
    private static void castSecondLateral(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean sun) {
        final float radius = (sun ? 5.3F : 4.9F) * (tc ? 1.18F : 1.0F);
        final float hitDmg = (sun ? 9.0F : 7.6F) * (tc ? 1.26F : 1.0F);
        final int duration = 18;

        Vec3 vel = player.getDeltaMovement();
        player.setDeltaMovement(vel.x * 0.55, 0.26, vel.z * 0.55);
        player.resetFallDistance();
        syncEntityVelocity(player);
        state.fallImmuneTicks = 32;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_WHEEL, player.getId(),
                player.getX(), player.getY() + 0.95, player.getZ(),
                player.getYRot(), radius, 1.0F, 0.0F,
                duration, tc, true, sun
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.65F, 1.32F);

        state.activeTasks.add(new ActiveFormTask() {
            float animTime = 0.0F;
            boolean burstSound = false;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float step = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = animTime / duration;
                animTime = Math.min(duration, animTime + step);
                float curT = animTime / duration;

                if (animTime >= 2.0F && !burstSound) {
                    burstSound = true;
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, p.position().add(0, 1.0, 0), horizontalLook(p), radius, -1.0, sun, true);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            sun ? SoundEvents.FIRECHARGE_USE : SoundEvents.TRIDENT_RIPTIDE_2.value(),
                            SoundSource.PLAYERS, 1.25F, sun ? 1.08F : 1.30F);
                    lvl.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                            p.getX(), p.getY() + 1.0, p.getZ(), 20, radius * 0.5, 0.35, radius * 0.5, 0.14);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                if (curT >= 0.12F) {
                    runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_LATERAL_WHEEL, prevT, curT, 0,
                            tc, sun, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 5,
                            radius + 1.2, 0.72, hitDmg, 0.85, 0.28, curT > 0.55F ? 1.60F : 1.25F, curT > 0.55F);
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < 14.0F;
            }
        });
    }

    /** R-L-R (水)：参之型·流流舞（三段弧线舞步 + 140Hz 交替挑斩/劈斩/横扫真实刀锋判定！） */
    private static void castThirdFlowingDance(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        state.fallImmuneTicks = 28;
        tryTriggerInstinctRead(player, level, state);
        state.activeTasks.add(new ActiveFormTask() {
            int stepIdx = 0;
            float stepTime = 0.0F;
            final int stepDur = 7;
            boolean stepStarted = false;
            float stepYaw = player.getYRot();
            Vec3 stepDir = horizontalLook(player);
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                if (!stepStarted) {
                    stepStarted = true;
                    hitCd.clear();
                    float yawOffset = stepIdx == 0 ? -24.0F : (stepIdx == 1 ? 24.0F : 0.0F);
                    stepYaw = p.getYRot() + yawOffset;
                    stepDir = Vec3.directionFromRotation(0.0F, stepYaw).normalize();
                    prevOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_FLOWING_STEP, p.getId(),
                            p.getX(), p.getY() + 0.12, p.getZ(),
                            stepYaw, 4.6F, yawOffset, stepIdx,
                            stepDur, tc, stepIdx == 2, false
                    );
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, p.position().add(stepDir.scale(2.0)).add(0, 1.0, 0),
                            stepDir, 3.8F, -0.25, false, stepIdx == 2);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.08F, 1.15F + stepIdx * 0.14F);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.85F, 1.3F + stepIdx * 0.12F);
                }

                float dt = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = stepTime / stepDur;
                stepTime = Math.min(stepDur, stepTime + dt);
                float curT = stepTime / stepDur;

                // 平滑舞步推力：前段轻灵冲出、后段顺势收刹折返！
                if (stepTime <= 3.5F && st.serverHitstopTicks <= 0.0F) {
                    float u = stepTime / 3.5F;
                    double spd = Mth.lerp(u, 1.48, 0.65);
                    p.setDeltaMovement(stepDir.x * spd, u < 0.4F ? 0.16 : p.getDeltaMovement().y * 0.7, stepDir.z * spd);
                    p.resetFallDistance();
                    syncEntityVelocity(p);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float dmg = (stepIdx == 2 ? 10.2F : 7.2F) * (tc ? 1.25F : 1.0F);
                runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_FLOWING_DANCE, prevT, curT, stepIdx,
                        tc, false, prevOrigin, curOrigin, stepYaw, stepYaw, hitCd, 10,
                        5.2, 0.72, dmg, stepIdx == 2 ? 0.82 : 0.62, stepIdx == 0 ? 0.36 : 0.22,
                        stepIdx == 2 ? 1.65F : 1.20F, stepIdx == 2);
                prevOrigin = curOrigin;

                if (stepTime >= 5.2F && stepIdx < 2) {
                    stepIdx++;
                    stepTime = 0.0F;
                    stepStarted = false;
                    return true;
                }
                return stepTime < stepDur;
            }
        });
    }

    /** R-L-R (日)：火之神神乐·炎舞（两段平滑猛进重斩 + 140Hz 共享刀锋斜劈/横扫！） */
    private static void castSunFlameDance(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        state.fallImmuneTicks = 28;
        tryTriggerInstinctRead(player, level, state);
        state.activeTasks.add(new ActiveFormTask() {
            int phase = 0; // 0 -> step 1 斜劈, 1 -> step 2 宽幅横扫
            float stepTime = 0.0F;
            final int stepDur = 8;
            boolean stepStarted = false;
            float stepYaw = player.getYRot();
            Vec3 stepDir = horizontalLook(player);
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                int animStep = phase == 0 ? 1 : 2;
                if (!stepStarted) {
                    stepStarted = true;
                    hitCd.clear();
                    stepYaw = p.getYRot();
                    stepDir = horizontalLook(p);
                    prevOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_FLOWING_STEP, p.getId(),
                            p.getX(), p.getY() + 0.15, p.getZ(),
                            stepYaw, 5.2F, phase == 0 ? 18.0F : 0.0F, animStep,
                            stepDur, tc, phase == 1, true
                    );
                    Vec3 hitCenter = p.position().add(stepDir.scale(2.2)).add(0.0, 1.0, 0.0);
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, hitCenter, stepDir, 4.4F, -0.25, true, phase == 1);
                    lvl.sendParticles(ParticleTypes.FLAME, hitCenter.x, hitCenter.y, hitCenter.z, 24, 1.6, 0.8, 1.6, 0.14);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.15F, phase == 0 ? 1.1F : 0.85F);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.25F, phase == 0 ? 1.05F : 0.78F);
                }

                float dt = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = stepTime / stepDur;
                stepTime = Math.min(stepDur, stepTime + dt);
                float curT = stepTime / stepDur;

                if (stepTime <= 4.0F && st.serverHitstopTicks <= 0.0F) {
                    float u = stepTime / 4.0F;
                    double peakSpd = phase == 0 ? 1.62 : 1.22;
                    double spd = Mth.lerp(u, peakSpd, 0.55);
                    p.setDeltaMovement(stepDir.x * spd, u < 0.4F ? 0.18 : p.getDeltaMovement().y * 0.7, stepDir.z * spd);
                    p.resetFallDistance();
                    syncEntityVelocity(p);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float dmg = (phase == 0 ? 12.0F : 15.5F) * (tc ? 1.28F : 1.0F);
                runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_FLOWING_DANCE, prevT, curT, animStep,
                        tc, true, prevOrigin, curOrigin, stepYaw, stepYaw, hitCd, 10,
                        5.8, 0.78, dmg, phase == 1 ? 0.98 : 0.70, phase == 1 ? 0.42 : 0.24,
                        phase == 1 ? 1.85F : 1.40F, phase == 1);
                prevOrigin = curOrigin;

                if (stepTime >= 6.0F && phase == 0) {
                    phase = 1;
                    stepTime = 0.0F;
                    stepStarted = false;
                    return true;
                }
                return stepTime < stepDur;
            }
        });
    }

    /** R-R-R (地面)：水·陆之型 扭转漩涡 / 日·灼骨炎阳（平滑旋升力学 + 140Hz 螺旋刀锋绞杀！） */
    private static void castSixthVortex(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean sun) {
        boolean waterBoost = !sun && player.isInWaterOrRain();
        final float radius = (sun ? 5.6F : 5.0F) * (tc ? 1.18F : 1.0F) * (waterBoost ? 1.30F : 1.0F);
        final float height = (sun ? 7.8F : 7.0F) * (tc ? 1.16F : 1.0F) * (waterBoost ? 1.20F : 1.0F);
        final float tickDmg = (sun ? 5.6F : 4.8F) * (tc ? 1.25F : 1.0F) * (waterBoost ? 1.25F : 1.0F);
        final int duration = 27;

        state.fallImmuneTicks = 36;
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WHIRLPOOL, player.getId(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), radius, height, 0.0F,
                duration, tc, waterBoost || sun, sun
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.80F, 0.92F);

        state.activeTasks.add(new ActiveFormTask() {
            float animTime = 0.0F;
            boolean erupted = false;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float step = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = animTime / duration;
                animTime = Math.min(duration, animTime + step);
                float curT = animTime / duration;

                Vec3 vel = p.getDeltaMovement();
                if (animTime < 3.0F) {
                    p.setDeltaMovement(vel.x * 0.55, Math.min(vel.y, 0.04), vel.z * 0.55);
                    syncEntityVelocity(p);
                    prevOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                    prevYaw = p.getYRot();
                    return true;
                }
                if (!erupted) {
                    erupted = true;
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, p.position().add(0, 1.0, 0), horizontalLook(p), radius, -1.0, sun, true);
                    if (sun) {
                        BladeWorldPhysics.spawnThermalUpdraft(lvl, p.getX(), p.getY(), p.getZ(), 4.6F, 8.5F, 55);
                    }
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            sun ? SoundEvents.BLAZE_SHOOT : SoundEvents.TRIDENT_RIPTIDE_3.value(),
                            SoundSource.PLAYERS, 1.38F, 0.76F);
                }
                // 平滑向上浮升推力（3..10 tick 轻盈旋升）
                if (animTime <= 10.0F && st.serverHitstopTicks <= 0.0F) {
                    float liftU = (animTime - 3.0F) / 7.0F;
                    p.setDeltaMovement(vel.x * 0.65, Mth.lerp(liftU, 0.24, 0.04), vel.z * 0.65);
                    syncEntityVelocity(p);
                }

                double cx = p.getX(), cy = p.getY(), cz = p.getZ();
                AABB box = new AABB(cx - radius - 1.5, cy - 1.0, cz - radius - 1.5,
                        cx + radius + 1.5, cy + height, cz + radius + 1.5);
                // 原版扭转漩涡攻防一体：高速水涡直接绞碎飞来箭矢与投射物！
                for (Projectile proj : lvl.getEntitiesOfClass(Projectile.class, box,
                        pr -> pr.isAlive() && pr.getOwner() != p && !pr.onGround())) {
                    lvl.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                            proj.getX(), proj.getY(), proj.getZ(), 8, 0.2, 0.2, 0.2, 0.08);
                    proj.discard();
                }
                // 持续流体/热浪漩涡牵引力
                for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p)) {
                    double dx = cx - target.getX(), dz = cz - target.getZ();
                    double dist = Math.hypot(dx, dz);
                    if (dist > 0.4 && dist <= radius + 1.6) {
                        double nx = dx / dist, nz = dz / dist;
                        if (sun) target.push(-nx * 0.05 - nz * 0.22, 0.06, -nz * 0.05 + nx * 0.22);
                        else target.push(nx * 0.16 - nz * 0.24, 0.06, nz * 0.16 + nx * 0.24);
                        syncEntityVelocity(target);
                    }
                }

                // 140Hz 螺旋刀锋连续扫掠：随刀尖螺旋盘升逐个切飞敌怪！
                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_WHIRLPOOL, prevT, curT, 0,
                        tc, sun, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 4,
                        radius + 1.5, 0.92, tickDmg, 0.56, 0.28, curT > 0.70F ? 1.60F : 1.15F, curT > 0.70F);

                if (Math.floor(animTime) != Math.floor(animTime - step) && ((int) animTime) % 4 == 0) {
                    lvl.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                            cx, cy + 1.4, cz, 22, radius * 0.5, 1.8, radius * 0.5, 0.16);
                    lvl.playSound(null, cx, cy, cz,
                            sun ? SoundEvents.FIRECHARGE_USE : SoundEvents.PLAYER_ATTACK_SWEEP,
                            SoundSource.PLAYERS, 0.85F, 1.05F + (animTime - 3.0F) * 0.02F);
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                if (animTime >= duration) {
                    // 符合直觉的漩涡收尾爆发：高速拧紧的水压骤然向外弹爆，掀起一圈清澈水压冲击波将卷入敌怪离心甩飞！
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            cx, cy + 0.9, cz, p.getYRot(),
                            radius * 1.18F, 0.0F, 1.0F,
                            14, tc, true, sun
                    );
                    lvl.playSound(null, cx, cy, cz,
                            SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 1.25F, 1.12F);
                    for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p)) {
                        double dx = target.getX() - cx, dz = target.getZ() - cz;
                        double dist = Math.hypot(dx, dz);
                        if (dist <= radius + 1.4) {
                            Vec3 out = dist > 0.2 ? new Vec3(dx / dist, 0.0, dz / dist) : horizontalLook(p);
                            Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ());
                            applyNichirinStrike(p, lvl, st, target, tickDmg * 1.35F, hitPt,
                                    out.add(0.0, 0.45, 0.0).normalize(),
                                    new Vec3(out.x * 1.08, 0.62, out.z * 1.08),
                                    true, sun, 1.85F, true, false);
                        }
                    }
                    return false;
                }
                return true;
            }
        });
    }

    /**
     * 肆之型 · 打潮：如海潮连绵不断拍打礁石般的四段高低起伏连续重斩！
     * 第 1 拍右下挑浪 -> 第 2 拍左上劈浪 -> 第 3 拍宽幅回卷 -> 第 4 拍登顶压海拍岸，炸出扇形水压爆浪！
     */
    private static void castFourthStrikingTide(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        state.fallImmuneTicks = 32;
        tryTriggerInstinctRead(player, level, state);
        state.activeTasks.add(new ActiveFormTask() {
            int stepIdx = 0;
            float stepTime = 0.0F;
            boolean stepStarted = false;
            float stepYaw = player.getYRot();
            Vec3 stepDir = horizontalLook(player);
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            boolean finalBurstSent = false;
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);
                final int stepDur = (stepIdx == 3) ? 9 : 6;

                if (!stepStarted) {
                    stepStarted = true;
                    hitCd.clear();
                    stepYaw = p.getYRot();
                    stepDir = horizontalLook(p);
                    prevOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_STRIKING_TIDE, p.getId(),
                            p.getX(), p.getY() + 1.0, p.getZ(),
                            stepYaw, 4.4F + stepIdx * 0.25F, (float) stepIdx, stepIdx == 3 ? 1.0F : 0.0F,
                            stepDur, tc, stepIdx == 3, false
                    );
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, p.position().add(stepDir.scale(2.0)).add(0, 1.0, 0),
                            stepDir, 4.4F, -0.35, false, stepIdx == 3);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.15F, 1.02F + stepIdx * 0.10F);
                    lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                            stepIdx == 3 ? SoundEvents.TRIDENT_RIPTIDE_3.value() : SoundEvents.TRIDENT_RIPTIDE_1.value(),
                            SoundSource.PLAYERS, stepIdx == 3 ? 1.28F : 0.90F, 1.08F + stepIdx * 0.08F);
                }

                float dt = st.serverHitstopTicks > 0.0F ? 0.18F : 1.0F;
                float prevT = stepTime / stepDur;
                stepTime = Math.min(stepDur, stepTime + dt);
                float curT = stepTime / stepDur;

                // 潮汐前压步伐：每一拍稳健前踏压进，第 4 拍略微跃起压斩！
                if (stepTime <= 3.2F && st.serverHitstopTicks <= 0.0F) {
                    double spd = stepIdx == 3 ? 0.88 : 0.72;
                    double vy = stepIdx == 3 && stepTime <= 1.5F ? 0.24 : Math.max(p.getDeltaMovement().y * 0.75, -0.10);
                    p.setDeltaMovement(stepDir.x * spd, vy, stepDir.z * spd);
                    syncEntityVelocity(p);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float dmg = (stepIdx == 3 ? 12.2F : (6.8F + stepIdx * 1.1F)) * (tc ? 1.26F : 1.0F);
                runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_STRIKING_TIDE, prevT, curT, stepIdx,
                        tc, false, prevOrigin, curOrigin, stepYaw, stepYaw, hitCd, 10,
                        5.2, 0.80, dmg, stepIdx == 3 ? 1.05 : 0.42, stepIdx == 3 ? 0.48 : 0.16,
                        stepIdx == 3 ? 1.90F : 1.20F, stepIdx == 3);
                prevOrigin = curOrigin;

                // 第 4 拍重劈落点：符合直觉的「潮汐拍岸」扇形水压爆浪！
                if (stepIdx == 3 && !finalBurstSent && curT >= 0.56F) {
                    finalBurstSent = true;
                    Vec3 crashPt = resolveForwardImpactPoint(lvl, p, stepDir, 2.6);
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            crashPt.x, p.getY() + 0.35, crashPt.z,
                            stepYaw, 4.4F, 0.0F, 1.0F,
                            14, tc, true, false
                    );
                    lvl.sendParticles(ParticleTypes.SPLASH, crashPt.x, p.getY() + 0.8, crashPt.z, 36, 1.8, 0.6, 1.8, 0.22);
                    lvl.playSound(null, crashPt.x, p.getY(), crashPt.z,
                            SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 0.85F, 1.18F);
                }

                if (stepIdx < 3 && stepTime >= 4.6F) {
                    stepIdx++;
                    stepTime = 0.0F;
                    stepStarted = false;
                    return true;
                }
                return stepTime < stepDur;
            }
        });
    }

    /**
     * 伍之型 · 乾天的慈雨：水之呼吸最温柔悲悯的一刀！
     * 轻盈凌空滑翔挥出甘霖细雨弧斩；对残血（<=38% HP）或露出「隙之线」破绽的目标造成极高慈雨无痛处决倍率，
     * 命中不粗暴乱飞，如细雨轻拂安详消散，并平心静气回复呼吸值！
     */
    private static void castFifthBlessedRain(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        state.fallImmuneTicks = 34;
        final int duration = 16;
        final float reach = 5.0F * (tc ? 1.18F : 1.0F);
        final Vec3 fwd = horizontalLook(player);

        // 轻盈优雅地微跃向前滑翔
        player.setDeltaMovement(fwd.x * 0.82, Math.max(player.getDeltaMovement().y, 0.42), fwd.z * 0.82);
        player.resetFallDistance();
        syncEntityVelocity(player);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_BLESSED_RAIN, player.getId(),
                player.getX(), player.getY() + 1.2, player.getZ(),
                player.getYRot(), reach, 0.0F, 0.0F,
                duration, tc, false, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.05F, 1.52F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.WEATHER_RAIN, SoundSource.PLAYERS, 0.72F, 1.28F);

        state.activeTasks.add(new ActiveFormTask() {
            float animTime = 0.0F;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.10, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float prevT = animTime / duration;
                animTime = Math.min(duration, animTime + 1.0F);
                float curT = animTime / duration;

                // 轻柔滑翔弧线
                if (animTime <= 8.0F) {
                    Vec3 v = p.getDeltaMovement();
                    p.setDeltaMovement(v.x * 0.92, Math.max(v.y, -0.05), v.z * 0.92);
                    p.resetFallDistance();
                    syncEntityVelocity(p);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.10, p.getZ());
                float curYaw = p.getYRot();
                if (curT >= 0.16F && prevT <= 0.86F) {
                    AABB broad = new AABB(
                            curOrigin.x - reach - 1.2, curOrigin.y - 2.2, curOrigin.z - reach - 1.2,
                            curOrigin.x + reach + 1.2, curOrigin.y + 2.6, curOrigin.z + reach + 1.2
                    );
                    List<LivingEntity> cands = lvl.getEntitiesOfClass(LivingEntity.class, broad, e -> e.isAlive() && e != p);
                    if (!cands.isEmpty()) {
                        long now = lvl.getGameTime();
                        List<BladeKinematics.BladeContact> contacts = BladeKinematics.sweepBladeContacts(
                                BladeKinematics.ANIM_BLESSED_RAIN, prevT, curT, 0, tc, false,
                                prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 16, 0.90
                        );
                        for (BladeKinematics.BladeContact c : contacts) {
                            LivingEntity target = c.target();
                            boolean mercy = (target.getHealth() / Math.max(1.0F, target.getMaxHealth()) <= 0.38F)
                                    || isOpeningThreadOpen(p, st, target, now);
                            float dmg = (mercy ? 24.0F : 10.5F) * (tc ? 1.25F : 1.0F);
                            // 慈雨：几乎无粗暴击飞，如甘霖拂面般安详终结！
                            boolean hit = applyNichirinStrike(p, lvl, st, target, dmg, c.contactPoint(), c.cutTangent(),
                                    new Vec3(0.0, 0.04, 0.0), true, false, mercy ? 1.55F : 0.95F, false, false, 0.8F);
                            if (hit) {
                                WaterVfxPayload.sendNear(
                                        lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                                        target.getX(), target.getY() + target.getBbHeight() * 0.60, target.getZ(),
                                        p.getYRot(), mercy ? 3.2F : 2.1F, 0.0F, 2.0F,
                                        18, tc, mercy, false
                                );
                                if (mercy && (!target.isAlive() || target.getHealth() <= 0.0F)) {
                                    st.breath = Math.min(MAX_BREATH, st.breath + 15.0F);
                                    st.dirty = true;
                                    lvl.playSound(null, target.getX(), target.getY(), target.getZ(),
                                            SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.25F, 1.85F);
                                }
                            }
                        }
                    }
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    /**
     * 柒之型 · 雫波纹突刺（及变招「雫波纹突刺·曲」）：
     * 水之呼吸最快最精准的一点突破突刺！剑尖刺穿瞬间在命中点荡开 3 层垂直于刺击方向的 3D 立体同心水波纹与穿透水爆！
     */
    private static void castSeventhDropRipple(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean curved, int strafeSign
    ) {
        state.fallImmuneTicks = 24;
        final int duration = 12;
        final float thrustDmg = (curved ? 14.5F : 16.5F) * (tc ? 1.28F : 1.0F);
        final float pitchDeg = Mth.clamp(player.getXRot(), -32.0F, 32.0F);
        final Vec3 aimDir = Vec3.directionFromRotation(pitchDeg * 0.65F, player.getYRot()).normalize();
        final Vec3 right = new Vec3(-aimDir.z, 0.0, aimDir.x);
        final Vec3 sideVec = right.lengthSqr() > 1.0E-4 ? right.normalize() : new Vec3(1.0, 0.0, 0.0);
        final double curveSide = strafeSign != 0 ? -strafeSign : 1.0;

        // 广播突刺起手与刀尖同心波纹特效（立即启动 ANIM_DROP_RIPPLE_THRUST！）
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_RIPPLE_THRUST, player.getId(),
                player.getX() + aimDir.x * 2.2, player.getY() + 1.08 + aimDir.y * 1.6, player.getZ() + aimDir.z * 2.2,
                player.getYRot(), 2.6F * (tc ? 1.15F : 1.0F), pitchDeg * 0.65F, curved ? 3.0F : 0.0F,
                duration, tc, true, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.25F, 1.52F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.90F, 1.88F);

        state.activeTasks.add(new ActiveFormTask() {
            int ticks = 0;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            boolean ripplePopped = false;
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                decrementHitCooldowns(hitCd);

                float prevT = ticks / (float) duration;
                ticks++;
                float curT = ticks / (float) duration;

                // 极速穿刺步法（1..5 tick 骤然前刺，曲变招带侧向弧线绕盾！）
                if (ticks <= 5 && st.serverHitstopTicks <= 0.0F) {
                    float u = ticks / 5.0F;
                    double fwdSpd = Mth.lerp(u, 1.78, 0.72);
                    double sideSpd = curved ? Mth.sin(u * Mth.PI) * 0.68 * curveSide : 0.0;
                    p.setDeltaMovement(
                            aimDir.x * fwdSpd + sideVec.x * sideSpd,
                            Mth.clamp(aimDir.y * fwdSpd * 0.7 + 0.06, -0.35, 0.45),
                            aimDir.z * fwdSpd + sideVec.z * sideSpd
                    );
                    p.resetFallDistance();
                    syncEntityVelocity(p);
                } else if (ticks == 6) {
                    Vec3 v = p.getDeltaMovement();
                    p.setDeltaMovement(v.x * 0.32, Math.max(v.y * 0.5, -0.05), v.z * 0.32);
                    syncEntityVelocity(p);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                if (curT >= 0.08F && prevT <= 0.72F) {
                    BladeWorldPhysics.processBladeSweep(p, lvl, st, curOrigin.add(aimDir.scale(2.4)), aimDir, 3.2F, 0.15, false, true);
                    AABB box = new AABB(
                            Math.min(prevOrigin.x, curOrigin.x) - 5.5, curOrigin.y - 2.2, Math.min(prevOrigin.z, curOrigin.z) - 5.5,
                            Math.max(prevOrigin.x, curOrigin.x) + 5.5, curOrigin.y + 2.8, Math.max(prevOrigin.z, curOrigin.z) + 5.5
                    );
                    List<LivingEntity> cands = lvl.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p);
                    if (!cands.isEmpty()) {
                        List<BladeKinematics.BladeContact> contacts = BladeKinematics.sweepBladeContacts(
                                BladeKinematics.ANIM_DROP_RIPPLE_THRUST, prevT, curT, curved ? 1 : 0, tc, false,
                                prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 16, 0.78
                        );
                        for (BladeKinematics.BladeContact c : contacts) {
                            Vec3 hitPt = c.contactPoint();
                            // 刺击命中：在刺穿点荡开 3D 垂直同心水波纹 + 穿透水花爆裂！
                            applyNichirinStrike(p, lvl, st, c.target(), thrustDmg, hitPt, aimDir,
                                    new Vec3(aimDir.x * 1.08, 0.34, aimDir.z * 1.08),
                                    true, false, 2.05F, true, true);
                            ripplePopped = true;
                            WaterVfxPayload.sendNear(
                                    lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                                    hitPt.x, hitPt.y, hitPt.z,
                                    curYaw, 3.5F * (tc ? 1.18F : 1.0F), pitchDeg * 0.65F, 0.0F,
                                    16, tc, false, false
                            );
                            lvl.sendParticles(ParticleTypes.SPLASH, hitPt.x, hitPt.y, hitPt.z, 26, 0.4, 0.4, 0.4, 0.24);
                            lvl.playSound(null, hitPt.x, hitPt.y, hitPt.z,
                                    SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 1.30F, 1.38F);
                        }
                    }
                }
                // 即使没刺中实体，在剑尖刺至最远端瞬间也会激起空气中一圈清澈水滴波纹！
                if (ticks == 5 && !ripplePopped) {
                    Vec3 tipPt = curOrigin.add(aimDir.scale(4.2));
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            tipPt.x, tipPt.y, tipPt.z,
                            curYaw, 2.8F, pitchDeg * 0.65F, 0.0F,
                            14, tc, false, false
                    );
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return ticks < duration;
            }
        });
    }

    /**
     * 捌之型 · 泷壶（100% 原版重做：彻底取消向上喷魔法柱！）
     * - 地面施放：自动轻盈拔高跃起约 3 格 -> 空中高举刀锋 -> 垂直瀑布劈斩入地！
     * - 空中施放：借真实高度差垂直加速压劈入地（越高瀑布重压威力越大！）
     * - 触地瞬间：自上而下倾泻厚重 3D 瀑布水帘 + 符合直觉的「瀑布砸潭」贴地环形激浪水爆！
     */
    private static void castEighthWaterfallBasin(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc, boolean startedAirborne
    ) {
        state.fallImmuneTicks = 48;
        final Vec3 fwd = horizontalLook(player);

        if (!startedAirborne) {
            // 地面起手：纵身干脆拔起约 3 格高空蓄刀，方便随时随地丝滑使出原版「泷壶」！
            player.setDeltaMovement(fwd.x * 0.42, 0.74, fwd.z * 0.42);
        } else {
            player.setDeltaMovement(player.getDeltaMovement().x * 0.35 + fwd.x * 0.25, 0.24, player.getDeltaMovement().z * 0.35 + fwd.z * 0.25);
        }
        player.resetFallDistance();
        syncEntityVelocity(player);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATERFALL_DIVE_START, player.getId(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), 0.0F, 0.0F, 0.0F,
                22, tc, false, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_INHALE, SoundSource.PLAYERS, 0.90F, 1.18F);

        final double peakRefY = player.getY() + (startedAirborne ? 0.5 : 3.2);
        final int apexTicks = startedAirborne ? 3 : 5;

        state.activeTasks.add(new ActiveFormTask() {
            int ticks = 0;
            double maxHeightY = peakRefY;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                ticks++;
                p.resetFallDistance();
                decrementHitCooldowns(hitCd);
                if (p.getY() > maxHeightY) maxHeightY = p.getY();

                if (ticks <= apexTicks) {
                    if (startedAirborne) {
                        double vy = Math.max(0.0, 0.22 - ticks * 0.075);
                        p.setDeltaMovement(p.getDeltaMovement().x * 0.58, vy, p.getDeltaMovement().z * 0.58);
                    } else {
                        double vy = Math.max(0.04, 0.64 - ticks * 0.13);
                        p.setDeltaMovement(fwd.x * 0.38, vy, fwd.z * 0.38);
                    }
                    syncEntityVelocity(p);
                    if (ticks == apexTicks) {
                        lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.30F, 0.80F);
                    }
                    prevOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                    prevYaw = p.getYRot();
                    return true;
                }

                // 下坠途中用 140Hz 垂直刀刃切扫空中目标
                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                if (!p.onGround() && ticks < 28) {
                    int diveT = ticks - apexTicks;
                    double curVy = -Math.min(2.25, 1.05 + diveT * 0.42);
                    p.setDeltaMovement(fwd.x * 0.42, curVy, fwd.z * 0.42);
                    syncEntityVelocity(p);
                    runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_WATERFALL_DIVE, 0.25F, 0.85F, 0,
                            tc, false, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 8,
                            4.2, 0.85, 8.5F * (tc ? 1.25F : 1.0F), 0.35, -0.55, 1.45F, false);
                    lvl.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.6, p.getZ(), 10, 0.45, 0.6, 0.45, 0.08);
                    prevOrigin = curOrigin;
                    prevYaw = curYaw;
                    return true;
                }

                // 触地瞬间：真实落差势能转化为自上而下倾泻砸潭的毁灭重劈！
                double dropHeight = Math.max(2.5, maxHeightY - p.getY());
                float energyScale = Mth.clamp(1.0F + (float) (dropHeight - 3.0) * 0.05F, 0.96F, 1.70F);
                final float radius = 5.6F * (tc ? 1.18F : 1.0F) * Mth.lerp(0.60F, 1.0F, energyScale);
                final float cascadeHeight = Math.min(11.0F, (float) (dropHeight + 3.2));
                final float totalDmg = 21.5F * (tc ? 1.26F : 1.0F) * Mth.lerp(0.70F, 1.0F, energyScale);

                Vec3 curFwd = horizontalLook(p);
                Vec3 impactPt = resolveForwardImpactPoint(lvl, p, curFwd, 2.0);
                double fx = impactPt.x, fy = p.getY(), fz = impactPt.z;
                p.setDeltaMovement(p.getDeltaMovement().x * 0.15, 0.0, p.getDeltaMovement().z * 0.15);
                syncEntityVelocity(p);

                BladeWorldPhysics.processBladeSweep(p, lvl, st, new Vec3(fx, fy + 0.8, fz), curFwd, radius, -1.0, false, true);

                AABB hitBox = new AABB(fx - radius, fy - 1.5, fz - radius, fx + radius, fy + 4.8, fz + radius);
                for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, hitBox, e -> e.isAlive() && e != p)) {
                    double dist = Math.hypot(target.getX() - fx, target.getZ() - fz);
                    if (dist <= radius + 0.5) {
                        Vec3 away = new Vec3(target.getX() - fx, 0.0, target.getZ() - fz);
                        Vec3 outDir = away.horizontalDistanceSqr() > 1.0E-4 ? away.normalize() : curFwd;
                        Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.58, target.getZ());
                        // 瀑布压砸受力：贴近爆心先向下重压再被水浪向外冲飞！
                        applyNichirinStrike(p, lvl, st, target, totalDmg, hitPt,
                                new Vec3(outDir.x * 0.85, -0.35, outDir.z * 0.85).normalize(),
                                new Vec3(outDir.x * 1.18, 0.62, outDir.z * 1.18),
                                true, false, 2.25F, true, true);
                    }
                }

                // 发送原版「自上而下倾泻瀑布水帘 + 贴地水压激浪冲击环」（0 向上魔法柱！）
                WaterVfxPayload.sendNear(
                        lvl, WaterVfxPayload.KIND_WATERFALL_BASIN, p.getId(),
                        fx, fy, fz,
                        p.getYRot(), radius, cascadeHeight, 0.0F,
                        22, tc, true, false
                );
                WaterVfxPayload.sendNear(
                        lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                        fx, fy + 0.18, fz,
                        p.getYRot(), radius * 1.25F, 0.0F, 1.0F,
                        16, tc, true, false
                );
                lvl.sendParticles(ParticleTypes.SPLASH, fx, fy + 0.9, fz, 55, radius * 0.50, 0.8, radius * 0.50, 0.24);
                lvl.playSound(null, fx, fy, fz, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.38F, 0.92F);
                lvl.playSound(null, fx, fy, fz, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.35F, 0.82F);
                lvl.playSound(null, fx, fy, fz, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.40F, 0.85F);
                return false;
            }
        });
    }

    /**
     * 玖之型 · 水流飞沫·乱：
     * 极短接地时间的全向点水碎步轻功身法！按 WASD 方向极速踏水掠进（空中/水面皆可借力变向），
     * 错身顺带一记干脆掠斩，并立刻重置后摇让你 0 延迟无缝衔接任意下一型！
     */
    private static void castNinthSplashingFlow(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc,
            boolean airborne, int fwdSign, int strafeSign
    ) {
        state.fallImmuneTicks = 26;
        Vec3 fwd = horizontalLook(player);
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x).normalize();

        // 根据玩家按下的 WASD 计算精准全向点水突进方向（未按方向键默认斜前掠进）
        double fW = (fwdSign == 0 && strafeSign == 0) ? 1.0 : fwdSign;
        double sW = -strafeSign; // 左正右负转成 right 方向
        Vec3 dashDir = fwd.scale(fW).add(right.scale(sW));
        dashDir = dashDir.lengthSqr() > 1.0E-4 ? dashDir.normalize() : fwd;

        float dashYaw = (float) (Mth.atan2(-dashDir.x, dashDir.z) * Mth.RAD_TO_DEG);
        double dashSpeed = (tc ? 1.56 : 1.38) * (player.isInWaterOrRain() ? 1.12 : 1.0);
        double vy = airborne ? 0.34 : 0.22;
        player.setDeltaMovement(dashDir.x * dashSpeed, vy, dashDir.z * dashSpeed);
        player.resetFallDistance();
        syncEntityVelocity(player);

        // 起步脚下点水飞沫波纹 + 掠斩特效
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SPLASH_STEP, player.getId(),
                player.getX(), player.getY() + 0.12, player.getZ(),
                dashYaw, dashYaw, airborne ? 1.0F : 0.0F, (float) (player.tickCount & 1),
                7, tc, airborne, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.95F, 1.55F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 0.80F, 1.48F);

        final Vec3 finalDashDir = dashDir;
        state.activeTasks.add(new ActiveFormTask() {
            int ticks = 0;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) return false;
                ticks++;
                decrementHitCooldowns(hitCd);

                if (ticks == 3) {
                    // 碎步二段点水涟漪！
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            p.getX(), p.getY() + 0.10, p.getZ(),
                            dashYaw, 1.85F, 0.0F, 1.0F,
                            10, tc, false, false
                    );
                    lvl.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.15, p.getZ(), 12, 0.35, 0.12, 0.35, 0.12);
                }

                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();
                float prevT = (ticks - 1) / 7.0F;
                float curT = ticks / 7.0F;
                runSweptBladeHits(p, lvl, st, BladeKinematics.ANIM_SPLASH_FLOW, prevT, curT, (int) (p.tickCount & 1),
                        tc, false, prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 12,
                        3.8, 0.72, 7.2F * (tc ? 1.22F : 1.0F), 0.48, 0.18, 1.05F, false);
                BladeWorldPhysics.processBladeSweep(p, lvl, st, curOrigin.add(finalDashDir.scale(1.4)), finalDashDir, 3.0F, 0.0, false, false);

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return ticks < 6;
            }
        });
    }

    /**
     * 拾之型 · 生生流转（100% 还原动画第 19 集核心机制：连续回旋叠势，越转越快越大，水流化龙！）
     * - 按住右键持续回旋突进（自由鼠标转向！），每完整旋转一圈（1 -> 2 -> 3 -> 4 圈）：
     *   转速递增、斩击威力倍增、背后追随的 3D 浮世绘东方水龙从湍流雏形一路长成怒吼巨龙！
     * - 松开右键（或完成第 4 圈极势）瞬间：借当前叠加的最高旋转动能挥出极其干脆的「生生流转·龙尾斩首」，
     *   水龙顺着最后一刀穿斩而出，在刀锋切点炸开符合直觉的高压水爆冲击波（0 魔法阵、0 柱子、0 脏泥块！）
     */
    private static void castTenthConstantFlux(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        state.fluxActive = true;
        state.fluxHolding = true;
        state.fallImmuneTicks = 64;
        state.dragonRushTicks = 42;

        // 广播第 1 圈起手水龙生成（随旋转圈数 1..4 实时成长变长变壮！）
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_DRAGON, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), 24.0F, 1.0F, Mth.clamp(player.getXRot(), -32.0F, 28.0F),
                44, tc, false, false
        );
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_DRAGON_SPIN_STAGE, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), 1.0F, 8.0F, 1.0F,
                8, tc, false, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.30F, 0.96F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.65F, 1.55F);

        state.activeTasks.add(new ActiveFormTask() {
            int spinStage = 1;           // 当前第几圈（1..4）
            int cycleTick = 0;           // 本圈已过 tick
            int cycleLen = 8;            // 每圈长度随叠势缩短加速：8 -> 7 -> 6 -> 6
            int totalTicks = 0;
            int wallImpactTicks = 0;
            Vec3 prevOrigin = new Vec3(player.getX(), player.getY() + 1.05, player.getZ());
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> bladeHitCd = new HashMap<>();
            final Map<Integer, Integer> wakeHitCd = new HashMap<>();
            final Set<Integer> bladeCutIds = new HashSet<>();
            final Deque<DragonWakeSlice> wakeQueue = new ArrayDeque<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (!p.isAlive() || !isHoldingWaterBlade(p)) {
                    st.fluxActive = false;
                    st.fluxHolding = false;
                    st.dragonRushTicks = 0;
                    return false;
                }
                decrementHitCooldowns(bladeHitCd);
                decrementHitCooldowns(wakeHitCd);

                float pitch = Mth.clamp(p.getXRot(), -34.0F, 26.0F);
                Vec3 fwd = Vec3.directionFromRotation(pitch * 0.60F, p.getYRot()).normalize();
                Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
                right = right.lengthSqr() > 1.0E-4 ? right.normalize() : new Vec3(1.0, 0.0, 0.0);

                if (totalTicks >= 4 && p.horizontalCollision) wallImpactTicks++;
                else wallImpactTicks = 0;

                // 核心原版物理：每多转一圈，推进速度与旋转威能严格递增！
                float speedMult = switch (spinStage) {
                    case 1 -> 0.96F;
                    case 2 -> 1.14F;
                    case 3 -> 1.30F;
                    default -> 1.44F;
                };
                float dmgMult = switch (spinStage) {
                    case 1 -> 1.00F;
                    case 2 -> 1.35F;
                    case 3 -> 1.75F;
                    default -> 2.25F;
                };

                double coilAngle = totalTicks * (0.58 + spinStage * 0.08);
                double lateral = Math.cos(coilAngle) * (0.20 + spinStage * 0.04);
                double vy = fwd.y * speedMult + 0.05 + Math.sin(coilAngle) * 0.06;
                if (p.horizontalCollision && wallImpactTicks < 2) vy = Math.max(vy, 0.34);

                p.setDeltaMovement(
                        fwd.x * speedMult + right.x * lateral,
                        Mth.clamp(vy, -0.42, 0.56),
                        fwd.z * speedMult + right.z * lateral
                );
                p.resetFallDistance();
                syncEntityVelocity(p);
                st.dragonRushTicks = 10;

                // 第一段：140Hz 刀锋回旋切开前路！
                float prevT = Mth.clamp(cycleTick / (float) cycleLen, 0.0F, 1.0F);
                float curT = Mth.clamp((cycleTick + 1) / (float) cycleLen, 0.0F, 1.0F);
                float stepDmg = 6.4F * dmgMult * (tc ? 1.26F : 1.0F);
                float hitRadius = 3.5F + spinStage * 0.42F;
                Vec3 curOrigin = new Vec3(p.getX(), p.getY() + 1.05, p.getZ());
                float curYaw = p.getYRot();

                AABB broadBox = new AABB(
                        Math.min(prevOrigin.x, curOrigin.x) - hitRadius - 1.5,
                        Math.min(prevOrigin.y, curOrigin.y) - 2.4,
                        Math.min(prevOrigin.z, curOrigin.z) - hitRadius - 1.5,
                        Math.max(prevOrigin.x, curOrigin.x) + hitRadius + 1.5,
                        Math.max(prevOrigin.y, curOrigin.y) + 3.6,
                        Math.max(prevOrigin.z, curOrigin.z) + hitRadius + 1.5
                );
                List<LivingEntity> cands = lvl.getEntitiesOfClass(LivingEntity.class, broadBox, e -> e.isAlive() && e != p);
                if (!cands.isEmpty()) {
                    List<BladeKinematics.BladeContact> contacts = BladeKinematics.sweepBladeContacts(
                            BladeKinematics.ANIM_CONSTANT_FLUX, prevT, curT, spinStage, tc, false,
                            prevOrigin, curOrigin, prevYaw, curYaw, cands, bladeHitCd, 4, 0.92
                    );
                    for (BladeKinematics.BladeContact c : contacts) {
                        bladeCutIds.add(c.target().getId());
                        Vec3 cutDir = c.cutTangent();
                        Vec3 holdKnock = new Vec3(cutDir.x * 0.24 + fwd.x * 0.16, 0.18, cutDir.z * 0.24 + fwd.z * 0.16);
                        applyNichirinStrike(p, lvl, st, c.target(), stepDmg * 0.56F, c.contactPoint(), cutDir, holdKnock,
                                true, false, 1.15F + spinStage * 0.18F, false, true, 0.95F);
                    }
                }
                prevOrigin = curOrigin;
                prevYaw = curYaw;

                // 第二段：延迟 3 tick 身后不断壮大的东方水龙顺轨尾流碾击！
                wakeQueue.addLast(new DragonWakeSlice(curOrigin, fwd, right, coilAngle, hitRadius + 0.45F, stepDmg * 0.54F, totalTicks));
                if (wakeQueue.size() >= 3) {
                    DragonWakeSlice slice = wakeQueue.removeFirst();
                    executeDragonWakeCrush(p, lvl, st, slice, wakeHitCd, bladeCutIds, tc, false, spinStage);
                }

                cycleTick++;
                totalTicks++;

                // 判断是否完成当前这一圈！
                boolean cycleComplete = cycleTick >= cycleLen;
                boolean hitWall = wallImpactTicks >= 2;
                if (cycleComplete || hitWall) {
                    boolean canContinue = !hitWall && st.fluxHolding && spinStage < 4
                            && (p.getAbilities().instabuild || st.breath >= 5.5F);
                    if (canContinue) {
                        // 叠势进阶！下一圈更迅猛、更致命、水龙更修长霸气！
                        if (!p.getAbilities().instabuild) {
                            st.breath = Math.max(0.0F, st.breath - (tc ? 4.8F : 5.8F));
                            st.dirty = true;
                        }
                        spinStage++;
                        cycleTick = 0;
                        cycleLen = (spinStage == 2) ? 7 : 6;
                        bladeHitCd.clear();

                        WaterVfxPayload.sendNear(
                                lvl, WaterVfxPayload.KIND_DRAGON_SPIN_STAGE, p.getId(),
                                p.getX(), p.getY() + 1.05, p.getZ(),
                                p.getYRot(), (float) spinStage, (float) cycleLen, speedMult,
                                cycleLen, tc, spinStage >= 3, false
                        );
                        lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.18F, 1.05F + spinStage * 0.14F);
                        lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                SoundEvents.TRIDENT_RIPTIDE_2.value(), SoundSource.PLAYERS, 1.15F, 0.96F + spinStage * 0.12F);
                        if (spinStage >= 3) {
                            lvl.playSound(null, p.getX(), p.getY(), p.getZ(),
                                    SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.90F + spinStage * 0.10F, 1.48F - spinStage * 0.08F);
                        }
                        return true;
                    }

                    // ====== 收尾：顺势借最高旋转势能斩出「生生流转 · 龙尾断首斩」+ 符合直觉的高压水爆！ ======
                    st.fluxActive = false;
                    st.fluxHolding = false;
                    st.dragonRushTicks = 0;
                    st.globalRecoveryTicks = 10;

                    final int finalStage = spinStage;
                    final Vec3 finFwd = horizontalLook(p);
                    final Vec3 strikePt = resolveForwardImpactPoint(lvl, p, finFwd, 2.4);
                    final float burstRadius = 4.6F + finalStage * 0.85F;
                    final float finDmg = (13.5F + finalStage * 4.8F) * (tc ? 1.28F : 1.0F);

                    // 顺势急刹前倾一刀斩断（绝不乱蹦乱飞，视野 100% 锁定你斩断的目标！）
                    p.setDeltaMovement(finFwd.x * 0.52, Math.max(p.getDeltaMovement().y * 0.35, 0.04), finFwd.z * 0.52);
                    syncEntityVelocity(p);

                    BladeWorldPhysics.processBladeSweep(p, lvl, st, strikePt, finFwd, burstRadius, -0.65, false, true);

                    AABB finBox = new AABB(
                            strikePt.x - burstRadius, strikePt.y - 2.0, strikePt.z - burstRadius,
                            strikePt.x + burstRadius, strikePt.y + 4.2, strikePt.z + burstRadius
                    );
                    for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, finBox, e -> e.isAlive() && e != p)) {
                        if (Math.hypot(target.getX() - strikePt.x, target.getZ() - strikePt.z) > burstRadius + 0.7) continue;
                        Vec3 away = new Vec3(target.getX() - strikePt.x, 0.0, target.getZ() - strikePt.z);
                        Vec3 out = away.lengthSqr() > 1.0E-4 ? away.normalize() : finFwd;
                        Vec3 swirl = new Vec3(-out.z, 0.0, out.x);
                        Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.62, target.getZ());
                        Vec3 launchDir = out.scale(0.68).add(swirl.scale(0.42)).add(0.0, 0.58, 0.0).normalize();
                        applyNichirinStrike(p, lvl, st, target, finDmg, hitPt, launchDir,
                                new Vec3(out.x * (0.78 + finalStage * 0.12) + swirl.x * 0.42, 0.58 + finalStage * 0.12,
                                        out.z * (0.78 + finalStage * 0.12) + swirl.z * 0.42),
                                true, false, 1.80F + finalStage * 0.25F, true, true);
                    }

                    // 触发符合直觉的「生生流转收尾龙弧穿斩 + 高压水爆冲击波」（随圈数 1..4 递进！）
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_SUN_DRAGON_FINALE, p.getId(),
                            strikePt.x, p.getY(), strikePt.z,
                            p.getYRot(), burstRadius, 0.0F, (float) finalStage,
                            38, tc, finalStage >= 3, false
                    );
                    WaterVfxPayload.sendNear(
                            lvl, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            strikePt.x, strikePt.y, strikePt.z,
                            p.getYRot(), burstRadius * 1.15F, 0.0F, 1.0F,
                            16, tc, true, false
                    );
                    lvl.sendParticles(ParticleTypes.SPLASH, strikePt.x, strikePt.y + 0.5, strikePt.z,
                            34 + finalStage * 12, burstRadius * 0.42, 1.2, burstRadius * 0.42, 0.24);
                    lvl.playSound(null, strikePt.x, strikePt.y, strikePt.z,
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.42F, 0.86F);
                    lvl.playSound(null, strikePt.x, strikePt.y, strikePt.z,
                            SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1.38F, 0.80F);
                    if (finalStage >= 2) {
                        lvl.playSound(null, strikePt.x, strikePt.y, strikePt.z,
                                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.15F + finalStage * 0.10F, 1.16F - finalStage * 0.06F);
                    }
                    if (finalStage >= 3) {
                        lvl.playSound(null, strikePt.x, strikePt.y, strikePt.z,
                                SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 1.15F, 1.18F);
                    }

                    // 排空最后 1~2 个延迟尾流切片
                    final Deque<DragonWakeSlice> remWakes = new ArrayDeque<>(wakeQueue);
                    if (!remWakes.isEmpty()) {
                        st.activeTasks.add(new ActiveFormTask() {
                            @Override
                            public boolean tick(ServerPlayer sp, ServerLevel sl, PlayerBreathState sst) {
                                if (remWakes.isEmpty()) return false;
                                executeDragonWakeCrush(sp, sl, sst, remWakes.removeFirst(), wakeHitCd, bladeCutIds, tc, false, finalStage);
                                return !remWakes.isEmpty();
                            }
                        });
                    }
                    return false;
                }
                return true;
            }
        });
    }

    /** 巨龙顺轨尾流二段碾杀（在玩家身后 3 tick 处的龙首坐标炸开狂暴水浪/烈焰并将沿途敌人螺旋掀飞！） */
    private static void executeDragonWakeCrush(
            ServerPlayer player, ServerLevel level, PlayerBreathState state,
            DragonWakeSlice slice, Map<Integer, Integer> wakeHitCd, Set<Integer> bladeCutIds,
            boolean tc, boolean sun, int chargeTier
    ) {
        double r = slice.radius;
        Vec3 pos = slice.pos;
        BladeWorldPhysics.processBladeSweep(player, level, state, pos, slice.fwd, (float) r, -0.75, sun, true);

        AABB box = new AABB(pos.x - r, pos.y - 2.0, pos.z - r, pos.x + r, pos.y + 3.2, pos.z + r);
        int hits = 0;
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != player)) {
            int tid = target.getId();
            if (wakeHitCd.getOrDefault(tid, 0) > 0) continue;
            if (Math.hypot(target.getX() - pos.x, target.getZ() - pos.z) > r + target.getBbWidth() * 0.5) continue;

            wakeHitCd.put(tid, 5);
            hits++;
            boolean wasBladeCut = bladeCutIds.remove(tid);
            float dmg = slice.damage * (wasBladeCut ? 1.24F : 1.0F);

            // 巨龙盘旋掀飞矢量：沿龙势向前冲撞 + 向左右螺旋甩开 + 高空抛飞！
            Vec3 away = new Vec3(target.getX() - pos.x, 0.0, target.getZ() - pos.z);
            Vec3 sidePush = away.lengthSqr() > 0.04 ? away.normalize() : slice.right.scale(Math.cos(slice.coilAngle) >= 0 ? 1.0 : -1.0);
            Vec3 launchDir = slice.fwd.scale(0.68).add(sidePush.scale(0.54)).add(0.0, 0.62, 0.0).normalize();
            Vec3 knock = new Vec3(
                    slice.fwd.x * 1.05 + sidePush.x * 0.72,
                    wasBladeCut ? 0.88 : 0.72,
                    slice.fwd.z * 1.05 + sidePush.z * 0.72
            );
            Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.55, target.getZ());
            // applyAttackerHitstop = false：身后巨龙轰然碾飞敌人，但绝不卡顿拖慢前方正在疾驰的剑士！
            applyNichirinStrike(player, level, state, target, dmg, hitPt, launchDir, knock,
                    true, sun, wasBladeCut ? 2.15F : 1.75F, true, false);
        }

        if (hits > 0 || slice.stepIdx % 2 == 0) {
            WaterVfxPayload.sendNear(
                    level, WaterVfxPayload.KIND_DRAGON_WAKE_IMPACT, player.getId(),
                    pos.x, pos.y, pos.z,
                    (float) (Mth.atan2(-slice.fwd.x, slice.fwd.z) * Mth.RAD_TO_DEG),
                    slice.radius, 0.85F + chargeTier * 0.22F, (float) hits,
                    14, tc, hits > 0, sun
            );
            level.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                    pos.x, pos.y, pos.z, hits > 0 ? 28 : 14, r * 0.35, 0.9, r * 0.35, 0.20);
        }
        if (hits > 0) {
            level.playSound(null, pos.x, pos.y, pos.z,
                    sun ? SoundEvents.BLAZE_SHOOT : SoundEvents.TRIDENT_RIPTIDE_3.value(),
                    SoundSource.PLAYERS, 1.32F, sun ? 0.74F : 0.88F);
            level.playSound(null, pos.x, pos.y, pos.z,
                    SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.62F, sun ? 1.08F : 1.32F);
        }
    }

    /** 射线安全前推爆心坐标（保证终点爆发落在刀锋前方绝佳观赏位，且遇到山壁自动停在墙前不穿模！） */
    private static Vec3 resolveForwardImpactPoint(ServerLevel level, ServerPlayer player, Vec3 fwd, double desiredDist) {
        Vec3 hFwd = new Vec3(fwd.x, 0.0, fwd.z);
        if (hFwd.lengthSqr() < 1.0E-4) hFwd = horizontalLook(player);
        else hFwd = hFwd.normalize();
        Vec3 start = player.position().add(0.0, 1.0, 0.0);
        Vec3 end = start.add(hFwd.scale(desiredDist));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            double hitDist = hit.getLocation().distanceTo(start);
            double safeDist = Math.max(1.1, hitDist - 0.85);
            return start.add(hFwd.scale(safeDist));
        }
        return end;
    }

    /** 潜行+长按右键 (水)：拾壹之型·凪 */
    private static void castEleventhDeadCalm(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        int duration = tc ? 60 : 50;
        state.deadCalmTicks = duration;
        player.setDeltaMovement(0.0, Math.min(0.0, player.getDeltaMovement().y), 0.0);
        syncEntityVelocity(player);
        tryTriggerInstinctRead(player, level, state);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_DEAD_CALM_DOMAIN, player.getId(),
                player.getX(), player.getY() + 0.06, player.getZ(),
                player.getYRot(), 7.8F, 0.0F, 0.0F,
                duration, tc, true, false
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMBIENT_UNDERWATER_EXIT, SoundSource.PLAYERS, 1.1F, 0.65F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.25F, 0.95F);
    }

    /** 潜行+长按右键 (日)：火之神神乐·幻日虹（原地留下烈焰残影火阵 + 高速滑步闪避反杀态！） */
    private static void castSunFakeRainbow(ServerPlayer player, ServerLevel level, PlayerBreathState state, boolean tc) {
        int duration = tc ? 65 : 55;
        state.fakeRainbowTicks = duration;
        state.fallImmuneTicks = 24;
        updateHazeSpeed(player, true);
        tryTriggerInstinctRead(player, level, state);

        final double mirageX = player.getX();
        final double mirageY = player.getY();
        final double mirageZ = player.getZ();

        BladeWorldPhysics.spawnThermalUpdraft(level, mirageX, mirageY, mirageZ, 4.2F, 8.0F, duration);

        // 在原地展开炽烈日轮残影光阵 + 自身瞬间向侧后方高速残影闪身！
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_DEAD_CALM_DOMAIN, player.getId(),
                mirageX, mirageY + 0.06, mirageZ,
                player.getYRot(), 6.8F, 0.0F, 0.0F,
                duration, tc, true, true
        );

        Vec3 look = horizontalLook(player);
        Vec3 side = new Vec3(-look.z, 0.0, look.x);
        player.setDeltaMovement(-look.x * 1.15 + side.x * 0.72, 0.28, -look.z * 1.15 + side.z * 0.72);
        syncEntityVelocity(player);

        level.playSound(null, mirageX, mirageY, mirageZ,
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.15F, 1.35F);
        level.playSound(null, mirageX, mirageY, mirageZ,
                SoundEvents.BREEZE_SLIDE, SoundSource.PLAYERS, 1.1F, 1.4F);

        // 原地残影每 9 tick 灼烧扑向残影的敌怪
        state.activeTasks.add(new ActiveFormTask() {
            int ticks = 0;

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lvl, PlayerBreathState st) {
                if (ticks % 9 == 0) {
                    AABB box = new AABB(
                            mirageX - 4.2, mirageY - 1.0, mirageZ - 4.2,
                            mirageX + 4.2, mirageY + 3.2, mirageZ + 4.2
                    );
                    float dmg = tc ? 7.5F : 5.8F;
                    for (LivingEntity target : lvl.getEntitiesOfClass(LivingEntity.class, box,
                            e -> e.isAlive() && e != p && (e instanceof Enemy || e.getLastHurtByMob() == p))) {
                        target.setInvulnerableTime(0);
                        target.igniteForSeconds(4.0F);
                        target.hurtServer(lvl, lvl.damageSources().playerAttack(p), dmg);
                    }
                    lvl.sendParticles(ParticleTypes.FLAME, mirageX, mirageY + 1.1, mirageZ, 12, 0.7, 0.8, 0.7, 0.05);
                }
                ticks++;
                return ticks < duration;
            }
        });
    }

    private static void damageArc(
            ServerPlayer player, ServerLevel level, PlayerBreathState state,
            double cx, double cy, double cz, Vec3 forward, float radius, double minDot,
            float damage, double knockHoriz, double knockY, boolean resetIframes, boolean sun,
            float weight, boolean heavy
    ) {
        AABB box = new AABB(cx - radius, cy - 1.8, cz - radius, cx + radius, cy + 2.2, cz + radius);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != player)) {
            Vec3 toTarget = new Vec3(target.getX() - player.getX(), 0.0, target.getZ() - player.getZ());
            double dist = toTarget.length();
            if (dist > radius + target.getBbWidth() * 0.5) continue;
            double dot = dist < 0.8 ? 1.0 : toTarget.normalize().dot(forward);
            if (dot < minDot) continue;
            Vec3 pushDir = dist > 0.1 ? toTarget.normalize() : forward;
            applyNichirinStrike(player, level, state, target, damage,
                    new Vec3(pushDir.x * knockHoriz, knockY, pushDir.z * knockHoriz),
                    resetIframes, sun, weight, heavy);
        }
    }

    // ==================== 鬼灭战斗直觉核心：隙之线 / 看破 / 统一命中实感管线 ====================

    /**
     * 感知周身杀气并触发「看破！」（在敌方扑袭/弹射物临身瞬间闪避、开全集中或踏出流流舞/凪/幻日虹）：
     * - 给予 0.5s 完美霸体闪避窗 + 瞬间回气 + 强制对该攻击者敞开 3.5s 高光「隙之线」！
     */
    public static void tryTriggerInstinctRead(ServerPlayer player, ServerLevel level, PlayerBreathState state) {
        if (state.instinctCounterWindowTicks > 0) return;
        long now = level.getGameTime();
        LivingEntity threat = null;

        // 1. 侦测朝向玩家飞来的敌方弹射物
        AABB projBox = player.getBoundingBox().inflate(9.5, 4.5, 9.5);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, projBox,
                pr -> pr.isAlive() && !pr.onGround() && pr.getOwner() != player)) {
            Vec3 toPlayer = player.position().add(0, 1.0, 0).subtract(proj.position());
            Vec3 vel = proj.getDeltaMovement();
            if (vel.lengthSqr() > 0.04 && toPlayer.lengthSqr() > 0.1 && vel.normalize().dot(toPlayer.normalize()) > 0.55) {
                if (proj.getOwner() instanceof LivingEntity owner && owner.isAlive() && owner != player) {
                    threat = owner;
                }
                state.instinctCounterWindowTicks = 10;
                break;
            }
        }

        // 2. 侦测近身锁定玩家并正在挥击/猛扑的敌怪
        if (threat == null) {
            AABB mobBox = player.getBoundingBox().inflate(5.5, 2.8, 5.5);
            double bestD2 = Double.MAX_VALUE;
            for (LivingEntity ent : level.getEntitiesOfClass(LivingEntity.class, mobBox,
                    e -> e.isAlive() && e != player && (e instanceof Enemy || e instanceof Mob))) {
                boolean aggressive = ent.isSwinging()
                        || (ent instanceof Mob m && m.getTarget() == player && ent.distanceToSqr(player) <= 24.0);
                if (aggressive) {
                    double d2 = ent.distanceToSqr(player);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        threat = ent;
                    }
                }
            }
        }

        if (threat == null && state.instinctCounterWindowTicks <= 0) {
            return;
        }

        state.instinctCounterWindowTicks = 10;
        state.breath = Math.min(MAX_BREATH, state.breath + 16.0F);
        state.dirty = true;

        boolean sun = state.style == BreathingStyle.SUN;
        int threatId = -1;
        double tx = player.getX(), ty = player.getY() + 1.1, tz = player.getZ();
        if (threat != null) {
            threatId = threat.getId();
            tx = threat.getX();
            ty = threat.getY() + threat.getBbHeight() * 0.78;
            tz = threat.getZ();
            state.exposedTargets.put(threatId, now + 70L);
            state.threadCooldowns.remove(threatId);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.15F, 1.85F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_SLIDE, SoundSource.PLAYERS, 0.95F, 1.55F);
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_INTUITION_PULSE, player.getId(),
                tx, ty, tz, player.getYRot(), (float) threatId, 1.0F, 0.0F,
                70, state.totalConcentration, true, sun
        );
    }

    /** 判断目标此刻是否向该剑士露出了可斩断的「隙之线」破绽 */
    public static boolean isOpeningThreadOpen(
            ServerPlayer player, PlayerBreathState state, LivingEntity target, long now
    ) {
        if (target == null || !target.isAlive() || target == player) return false;
        if (state.threadCooldowns.getOrDefault(target.getId(), 0L) > now) return false;
        if (state.exposedTargets.getOrDefault(target.getId(), 0L) > now) return true;
        if (target.getHealth() <= target.getMaxHealth() * 0.38F || target.getHealth() <= 10.0F) return true;
        return target.isSwinging()
                || (target instanceof Mob mob && mob.getTarget() == player && mob.distanceToSqr(player) <= 22.0);
    }

    /** 140Hz 共享刀锋连续子步扫描统一入口：按真实刀锋扫到敌人的先后顺序逐个命中，并赋予精确 3D 刀锋切线冲量！ */
    private static void runSweptBladeHits(
            ServerPlayer player, ServerLevel level, PlayerBreathState state,
            int animType, float prevT, float curT, int paramInt, boolean tc, boolean sun,
            Vec3 prevOrigin, Vec3 curOrigin, float prevYaw, float curYaw,
            Map<Integer, Integer> hitCooldowns, int rehitCdTicks,
            double searchRadius, double bladeThickness,
            float damage, double tangentForce, double extraUp, float weight, boolean heavy
    ) {
        AABB broadBox = new AABB(
                Math.min(prevOrigin.x, curOrigin.x) - searchRadius,
                Math.min(prevOrigin.y, curOrigin.y) - 2.6,
                Math.min(prevOrigin.z, curOrigin.z) - searchRadius,
                Math.max(prevOrigin.x, curOrigin.x) + searchRadius,
                Math.max(prevOrigin.y, curOrigin.y) + 4.2,
                Math.max(prevOrigin.z, curOrigin.z) + searchRadius
        );
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, broadBox,
                e -> e.isAlive() && e != player);
        if (candidates.isEmpty()) return;

        List<BladeKinematics.BladeContact> contacts = BladeKinematics.sweepBladeContacts(
                animType, prevT, curT, paramInt, tc, sun,
                prevOrigin, curOrigin, prevYaw, curYaw,
                candidates, hitCooldowns, rehitCdTicks, bladeThickness
        );
        for (BladeKinematics.BladeContact c : contacts) {
            // 将刀锋真实 3D 切线矢量与微量外推合成极具物理直觉的击飞方向：横斩顺刀横飞、上挑浮空、下劈砸地！
            Vec3 away = c.target().position().subtract(player.position());
            Vec3 radial = away.horizontalDistanceSqr() > 1.0E-4
                    ? new Vec3(away.x, 0.0, away.z).normalize() : Vec3.ZERO;
            Vec3 cutDir = c.cutTangent().scale(0.76).add(radial.scale(0.24));
            if (cutDir.lengthSqr() > 1.0E-5) cutDir = cutDir.normalize();
            else cutDir = c.cutTangent();

            Vec3 knock = new Vec3(
                    cutDir.x * tangentForce,
                    Math.max(extraUp, cutDir.y * tangentForce + 0.16),
                    cutDir.z * tangentForce
            );
            applyNichirinStrike(player, level, state, c.target(), damage,
                    c.contactPoint(), cutDir, knock, true, sun, weight, heavy);
        }
    }

    private static void decrementHitCooldowns(Map<Integer, Integer> hitCd) {
        if (hitCd.isEmpty()) return;
        hitCd.entrySet().removeIf(e -> {
            int nv = e.getValue() - 1;
            if (nv <= 0) return true;
            e.setValue(nv);
            return false;
        });
    }

    /** 局部双向顿帧：受击敌人短暂在刀锋咬合处凝滞 2 tick，卡肉结束瞬间豁然释放精确刀锋切线击飞！ */
    private static void tickFrozenTargets(ServerLevel level, PlayerBreathState state) {
        if (state.frozenTargets.isEmpty()) return;
        List<TargetHitFreeze> snap = new ArrayList<>(state.frozenTargets);
        for (TargetHitFreeze f : snap) {
            Entity ent = level.getEntity(f.entityId);
            if (!(ent instanceof LivingEntity target) || !target.isAlive()) {
                state.frozenTargets.remove(f);
                continue;
            }
            f.ticksLeft--;
            if (f.ticksLeft > 0) {
                Vec3 v = target.getDeltaMovement();
                target.setDeltaMovement(v.x * 0.08, Math.min(v.y * 0.12, 0.02), v.z * 0.08);
                syncEntityVelocity(target);
            } else {
                target.setDeltaMovement(f.releaseVelocity);
                syncEntityVelocity(target);
                state.frozenTargets.remove(f);
            }
        }
    }

    public static boolean applyNichirinStrike(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, LivingEntity target,
            float baseDamage, Vec3 knockVec, boolean resetIframes, boolean sun, float weight, boolean heavy
    ) {
        Vec3 hitPt = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.60, target.getZ());
        Vec3 cutDir = (knockVec != null && knockVec.lengthSqr() > 1.0E-4)
                ? knockVec.normalize()
                : horizontalLook(player);
        return applyNichirinStrike(player, level, state, target, baseDamage, hitPt, cutDir, knockVec, resetIframes, sun, weight, heavy);
    }

    public static boolean applyNichirinStrike(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, LivingEntity target,
            float baseDamage, Vec3 hitPoint, Vec3 cutTangent, Vec3 knockVec,
            boolean resetIframes, boolean sun, float weight, boolean heavy
    ) {
        return applyNichirinStrike(player, level, state, target, baseDamage, hitPoint, cutTangent, knockVec,
                resetIframes, sun, weight, heavy, true, -1.0F);
    }

    private static boolean applyNichirinStrike(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, LivingEntity target,
            float baseDamage, Vec3 hitPoint, Vec3 cutTangent, Vec3 knockVec,
            boolean resetIframes, boolean sun, float weight, boolean heavy, boolean applyAttackerHitstop
    ) {
        return applyNichirinStrike(player, level, state, target, baseDamage, hitPoint, cutTangent, knockVec,
                resetIframes, sun, weight, heavy, applyAttackerHitstop, -1.0F);
    }

    /**
     * 统一呼吸法剑型命中结算（精确 3D 接触点 + 刀锋切线矢量 + 双向顿刀卡肉 + 活体受身 & 斩杀布娃娃同步！）：
     * 支持 applyAttackerHitstop=false，使「刀先破空 -> 身后巨龙/尾浪二段碾压」在身后狂轰滥炸时丝毫不拖慢前面疾驰的剑士！
     */
    private static boolean applyNichirinStrike(
            ServerPlayer player, ServerLevel level, PlayerBreathState state, LivingEntity target,
            float baseDamage, Vec3 hitPoint, Vec3 cutTangent, Vec3 knockVec,
            boolean resetIframes, boolean sun, float weight, boolean heavy,
            boolean applyAttackerHitstop, float customHitstopTicks
    ) {
        if (!target.isAlive() || target == player) return false;
        long now = level.getGameTime();
        boolean threadBreak = isOpeningThreadOpen(player, state, target, now);
        float momentumMult = BladeWorldPhysics.computeMomentumMultiplier(player);
        float steamBonus = BladeWorldPhysics.triggerWaterFireChemistry(player, level, target, sun);
        float finalDmg = baseDamage * momentumMult + steamBonus;
        if (threadBreak) {
            finalDmg *= (state.totalConcentration ? 1.60F : 1.45F);
            if (target.getHealth() - finalDmg <= 3.5F && target.getHealth() - finalDmg > 0.0F) {
                finalDmg += 4.5F;
            }
        }

        if (resetIframes) target.setInvulnerableTime(0);
        if (sun) target.igniteForSeconds(heavy ? 6.5F : 4.5F);

        boolean hurt;
        state.inFormStrike = true;
        try {
            hurt = target.hurtServer(level, level.damageSources().playerAttack(player), finalDmg);
        } finally {
            state.inFormStrike = false;
        }
        if (!hurt) return false;

        BladeWorldPhysics.playMaterialHitResonance(level, target, sun);

        boolean lethal = !target.isAlive() || target.getHealth() <= 0.0F;
        float baseStop = customHitstopTicks >= 0.0F
                ? customHitstopTicks
                : (lethal ? 3.5F : (threadBreak ? 3.0F : (heavy ? 2.2F : 1.6F)));
        float attackerStop = applyAttackerHitstop ? baseStop : 0.0F;
        if (applyAttackerHitstop && attackerStop > 0.0F) {
            state.serverHitstopTicks = Math.max(state.serverHitstopTicks, Math.min(2.6F, attackerStop * 0.78F));
        }

        if (knockVec != null && knockVec.lengthSqr() > 1.0E-5 && !lethal) {
            double kScale = Mth.lerp(0.65F, 1.0F, momentumMult);
            Vec3 launchVel = new Vec3(knockVec.x * kScale, knockVec.y, knockVec.z * kScale);
            if (applyAttackerHitstop && (heavy || threadBreak)) {
                // 局部双向顿帧：敌人被刀咬住 2 tick 后豁然沿刀锋切线飞出！
                target.setDeltaMovement(0.0, 0.02, 0.0);
                syncEntityVelocity(target);
                state.frozenTargets.removeIf(f -> f.entityId == target.getId());
                state.frozenTargets.add(new TargetHitFreeze(target.getId(), threadBreak ? 3 : 2, launchVel));
            } else {
                // 二段巨龙碾压 / 轻斩：瞬间直接爆发冲量将敌怪高抛甩飞！
                target.setDeltaMovement(launchVel);
                syncEntityVelocity(target);
            }
        }
        if (!lethal && (heavy || threadBreak)) {
            target.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS, threadBreak ? 22 : 14, threadBreak ? 2 : 1, false, false, false
            ));
        }

        if (threadBreak) {
            state.threadCooldowns.put(target.getId(), now + 28L);
            state.exposedTargets.remove(target.getId());
            state.breath = Math.min(MAX_BREATH, state.breath + 12.0F);
            state.dirty = true;
        } else if (!lethal) {
            state.exposedTargets.put(target.getId(), now + 38L);
        }

        Vec3 effHit = (threadBreak || lethal)
                ? new Vec3(hitPoint.x, Math.max(hitPoint.y, target.getY() + target.getBbHeight() * 0.74), hitPoint.z)
                : hitPoint;
        sendHitPhysicsFeedback(player, level, state, target, effHit, cutTangent, sun,
                threadBreak ? Math.max(weight, 2.0F) : weight, attackerStop, heavy || threadBreak, threadBreak, lethal);
        return true;
    }

    /** 普通左键平A命中同样接入「刀锋切线矢量 + 局部卡肉 + 活体受身 & 斩杀布娃娃」！ */
    public static void handleVanillaBladeHit(ServerPlayer attacker, LivingEntity victim, LivingIncomingDamageEvent event) {
        PlayerBreathState state = getState(attacker.getUUID());
        if (state.inFormStrike) return;

        ServerLevel level = attacker.level();
        boolean isSun = state.style == BreathingStyle.SUN;
        long now = level.getGameTime();
        float momentumMult = BladeWorldPhysics.computeMomentumMultiplier(attacker);
        float steamBonus = BladeWorldPhysics.triggerWaterFireChemistry(attacker, level, victim, isSun);
        float dmg = event.getAmount() * momentumMult + steamBonus;

        if (state.totalConcentration) {
            dmg *= (isSun ? 1.30F : 1.20F);
            if (isSun) {
                victim.igniteForSeconds(4.0F);
            }
            state.breath = Math.min(MAX_BREATH, state.breath + 4.5F);
            level.sendParticles(isSun ? ParticleTypes.FLAME : ParticleTypes.SPLASH,
                    victim.getX(), victim.getY() + victim.getBbHeight() * 0.55, victim.getZ(),
                    10, 0.35, 0.35, 0.35, 0.12);
        } else {
            state.breath = Math.min(MAX_BREATH, state.breath + 2.5F);
        }
        BladeWorldPhysics.playMaterialHitResonance(level, victim, isSun);

        boolean threadBreak = isOpeningThreadOpen(attacker, state, victim, now);
        if (threadBreak) {
            dmg *= (state.totalConcentration ? 1.60F : 1.45F);
            if (victim.getHealth() - dmg <= 3.5F && victim.getHealth() - dmg > 0.0F) {
                dmg += 4.5F;
            }
            state.threadCooldowns.put(victim.getId(), now + 28L);
            state.exposedTargets.remove(victim.getId());
            state.breath = Math.min(MAX_BREATH, state.breath + 12.0F);
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 2, false, false, false));
        }

        event.setAmount(dmg);
        state.dirty = true;
        boolean lethal = dmg >= victim.getHealth();

        // 计算普通交替斜斩的真实 3D 刀锋切线矢量（右上→左下 / 左上→右下交替！）
        state.vanillaSlashParity = !state.vanillaSlashParity;
        Vec3 fwd = horizontalLook(attacker);
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
        double sideSign = state.vanillaSlashParity ? -1.0 : 1.0;
        Vec3 cutDir = fwd.scale(0.55).add(right.scale(sideSign * 0.80)).add(0.0, -0.22, 0.0).normalize();
        Vec3 hitPt = new Vec3(victim.getX(), victim.getY() + victim.getBbHeight() * (threadBreak || lethal ? 0.76 : 0.60), victim.getZ());

        float stopTicks = lethal ? 3.2F : (threadBreak ? 2.8F : 1.7F);
        sendHitPhysicsFeedback(attacker, level, state, victim, hitPt, cutDir, isSun,
                threadBreak ? 2.0F : 1.05F, stopTicks, threadBreak, threadBreak, lethal);
    }

    private static void sendHitPhysicsFeedback(
            ServerPlayer attacker, ServerLevel level, PlayerBreathState state, LivingEntity target,
            Vec3 hitPoint, Vec3 cutTangent, boolean sun, float force, float hitstopTicks,
            boolean heavy, boolean threadBreak, boolean lethal
    ) {
        long now = level.getGameTime();
        if (state.lastHitFeedbackTick != now) {
            state.lastHitFeedbackTick = now;
            state.hitFeedbackCountThisTick = 0;
        }
        state.hitFeedbackCountThisTick++;
        if (state.hitFeedbackCountThisTick > 5 && !threadBreak && !lethal) {
            return;
        }

        Vec3 dir = cutTangent != null && cutTangent.lengthSqr() > 1.0E-5 ? cutTangent.normalize() : horizontalLook(attacker);
        HitPhysicsPayload.sendNear(
                level, attacker.getId(), target.getId(),
                hitPoint.x, hitPoint.y, hitPoint.z,
                (float) dir.x, (float) dir.y, (float) dir.z,
                force, hitstopTicks,
                threadBreak, lethal, heavy, sun, state.totalConcentration
        );

        if (state.hitFeedbackCountThisTick <= 2 || threadBreak || lethal) {
            if (threadBreak) {
                level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                        SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.35F, 1.95F);
                level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                        SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.15F, 1.50F);
                level.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.ELECTRIC_SPARK,
                        hitPoint.x, hitPoint.y, hitPoint.z, 18, 0.45, 0.35, 0.45, 0.16);
            } else if (heavy) {
                level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                        SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.05F, sun ? 0.90F : 1.05F);
            } else {
                level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                        SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.85F, 1.18F);
            }
            if (lethal) {
                level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                        SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.15F, 1.38F);
            }
        }
    }

    private static Vec3 horizontalLook(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 h = new Vec3(look.x, 0.0, look.z);
        return h.lengthSqr() > 1.0E-4 ? h.normalize() : Vec3.directionFromRotation(0.0F, player.getYRot());
    }

    public static void syncEntityVelocity(Entity entity) {
        entity.needsSync = true;
        entity.syncVelocity = true;
    }

    public static void updateHeldBladeVisual(ServerPlayer player, PlayerBreathState state) {
        ItemStack stack = player.getMainHandItem();
        if (!WaterNichirinBladeItem.isWaterBlade(stack)) return;
        boolean redBlade = state.style == BreathingStyle.SUN
                && (state.totalConcentration || state.chargingMode == 1 || state.dragonRushTicks > 0);
        WaterNichirinBladeItem.syncBladeModelVariant(stack, state.style, redBlade);
    }

    public static void setTotalConcentration(ServerPlayer player, PlayerBreathState state, boolean active) {
        state.totalConcentration = active;
        state.dirty = true;
        AttributeInstance moveAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance atkSpeedAttr = player.getAttribute(Attributes.ATTACK_SPEED);
        if (active) {
            if (moveAttr != null) moveAttr.addOrUpdateTransientModifier(TOTAL_CONC_SPEED_MOD);
            if (atkSpeedAttr != null) atkSpeedAttr.addOrUpdateTransientModifier(TOTAL_CONC_ATK_SPEED_MOD);
        } else {
            if (moveAttr != null) moveAttr.removeModifier(TOTAL_CONC_SPEED_ID);
            if (atkSpeedAttr != null) atkSpeedAttr.removeModifier(TOTAL_CONC_ATK_SPEED_ID);
        }
        updateHeldBladeVisual(player, state);
    }

    public static void updateHazeSpeed(ServerPlayer player, boolean active) {
        AttributeInstance moveAttr = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (moveAttr == null) return;
        if (active) moveAttr.addOrUpdateTransientModifier(HAZE_SPEED_MOD);
        else moveAttr.removeModifier(HAZE_SPEED_ID);
    }

    public static void syncPlayer(ServerPlayer player, PlayerBreathState state) {
        state.syncTimer = 0;
        state.dirty = false;
        int specialTicks = state.style == BreathingStyle.SUN ? state.fakeRainbowTicks : state.deadCalmTicks;
        PacketDistributor.sendToPlayer(player, new BreathSyncPayload(
                (byte) state.style.getId(),
                state.breath,
                MAX_BREATH,
                state.totalConcentration,
                state.inhaling,
                specialTicks,
                state.globalRecoveryTicks,
                state.lastCastFormOrdinal,
                state.castCounter
        ));
    }

    public interface ActiveFormTask {
        boolean tick(ServerPlayer player, ServerLevel level, PlayerBreathState state);
    }

    public static final class PlayerBreathState {
        public BreathingStyle style = BreathingStyle.WATER;
        public float breath = MAX_BREATH;
        public boolean totalConcentration = false;
        public boolean inhaling = false;
        public int chargingMode = 0;
        public int chargeTicks = 0;
        public int deadCalmTicks = 0;
        public int fakeRainbowTicks = 0;
        public int globalRecoveryTicks = 0;
        public int fallImmuneTicks = 0;
        public int dragonRushTicks = 0;
        /** 拾之型·生生流转 是否正处于连续回旋叠势中 & 右键是否仍按住 */
        public boolean fluxActive = false;
        public boolean fluxHolding = false;
        public int instinctCounterWindowTicks = 0;
        public float serverHitstopTicks = 0.0F;
        public boolean vanillaSlashParity = false;
        public boolean inFormStrike = false;
        public long lastHitFeedbackTick = -1L;
        public int hitFeedbackCountThisTick = 0;
        public long lastBasicSweepTick = -10L;
        public final Map<Integer, Long> exposedTargets = new HashMap<>();
        public final Map<Integer, Long> threadCooldowns = new HashMap<>();
        public final List<TargetHitFreeze> frozenTargets = new ArrayList<>();
        public int lastCastFormOrdinal = -1;
        public int castCounter = 0;
        public int syncTimer = 0;
        public boolean dirty = true;
        public final int[] formCooldowns = new int[WaterForm.values().length];
        public final List<ActiveFormTask> activeTasks = new ArrayList<>();
    }

    private static final class TargetHitFreeze {
        final int entityId;
        int ticksLeft;
        final Vec3 releaseVelocity;

        TargetHitFreeze(int entityId, int ticksLeft, Vec3 releaseVelocity) {
            this.entityId = entityId;
            this.ticksLeft = ticksLeft;
            this.releaseVelocity = releaseVelocity;
        }
    }

    /** 巨龙顺轨尾流延迟碾压切片（记录玩家 3 tick 前的精准轨迹点、龙势切线与伤害参数） */
    private record DragonWakeSlice(
            Vec3 pos, Vec3 fwd, Vec3 right, double coilAngle, float radius, float damage, int stepIdx
    ) {}
}
