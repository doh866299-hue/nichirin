package com.nichirin.breathing;

import com.nichirin.network.BreathingActionPayload;
import com.nichirin.network.WaterVfxPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端 · 日之呼吸（火之神神乐）全十三型战斗引擎
 * <p>
 * <ul>
 *   <li>1~12 型严格还原漫画 192 话正统招式与机制，与水之呼吸彻底区分（绝非换皮）</li>
 *   <li>第 13 型「日之圆环」：点按或按住右键将 1→12 型高速无缝串联循环，燃起炽红赫刀、减耗增伤、满环回气！</li>
 *   <li>「灼骨」系统：日呼重击 / 灼骨炎阳 / 赫刀命中目标大幅抑制生命回复（-75%），克制亡灵与高回复敌人</li>
 * </ul>
 */
public final class SunBreathingForms {
    /** 处于「灼骨」禁疗抑制状态的目标映射：entityId -> 失效游戏刻 */
    private static final Map<Integer, Long> SCORCHED_TARGETS = new ConcurrentHashMap<>();

    private SunBreathingForms() {}

    public static void clearAll() {
        SCORCHED_TARGETS.clear();
    }

    /** 给目标施加「灼骨」效果（显著削弱其生命回复能力，还原日之呼吸抑制再生的原作特性） */
    public static void applyScorch(LivingEntity target, ServerLevel level, long durationTicks) {
        if (target == null || !target.isAlive()) return;
        long until = level.getGameTime() + Math.max(20L, durationTicks);
        SCORCHED_TARGETS.merge(target.getId(), until, Math::max);
    }

    /** 监听生物治疗事件：处于「灼骨」期间，生命回复削减 75%！ */
    public static void handleScorchedHeal(LivingHealEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        Long until = SCORCHED_TARGETS.get(entity.getId());
        if (until == null) return;
        long now = entity.level().getGameTime();
        if (now >= until) {
            SCORCHED_TARGETS.remove(entity.getId());
            return;
        }
        event.setAmount(event.getAmount() * 0.25F);
    }

    /** 判断玩家当前日之日轮刀是否点燃「炽红赫刀」 */
    public static boolean isRedBladeActive(BreathingManager.PlayerBreathState state) {
        return state.style == BreathingStyle.SUN
                && (state.totalConcentration || state.sunCycleStep > 0 || state.dragonRushTicks > 0 || state.chargingMode == 1);
    }

    /** 服务端每 tick 驱动日之呼吸冷却、第十三型圆环连舞自动续招、幻日虹残像诱敌与赫刀粒子 */
    public static void tickSunPlayer(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean holdingSunBlade
    ) {
        for (int i = 0; i < state.sunFormCooldowns.length; i++) {
            if (state.sunFormCooldowns[i] > 0) state.sunFormCooldowns[i]--;
        }
        if (state.sunBurstBusyTicks > 0) {
            state.sunBurstBusyTicks--;
        }

        // 定期清理已失效的灼骨记录
        if (player.tickCount % 60 == 0 && !SCORCHED_TARGETS.isEmpty()) {
            long now = level.getGameTime();
            SCORCHED_TARGETS.entrySet().removeIf(e -> e.getValue() <= now);
        }

        // 幻日虹原地残像诱敌 & 靠近灼伤
        if (state.sunAfterimageTicks > 0 && state.sunAfterimagePos != null) {
            state.sunAfterimageTicks--;
            Vec3 decPos = state.sunAfterimagePos;
            if (state.sunAfterimageTicks % 4 == 0) {
                level.sendParticles(ParticleTypes.FLAME, decPos.x, decPos.y + 1.0, decPos.z, 3, 0.28, 0.45, 0.28, 0.02);
                AABB lureBox = new AABB(decPos.x - 8.5, decPos.y - 2.5, decPos.z - 8.5,
                        decPos.x + 8.5, decPos.y + 3.5, decPos.z + 8.5);
                for (Mob mob : level.getEntitiesOfClass(Mob.class, lureBox, m -> m.isAlive() && m instanceof Enemy)) {
                    // 误导敌人面向残像并对其短暂减速迷惑，贴近残像者触发灼热虹焰反灼并暴露隙之线！
                    mob.getLookControl().setLookAt(decPos.x, decPos.y + 1.1, decPos.z);
                    double d2 = mob.position().distanceToSqr(decPos);
                    if (d2 <= 6.25 && state.sunAfterimageTicks % 8 == 0) {
                        mob.igniteForSeconds(3.0F);
                        applyScorch(mob, level, 80L);
                        mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 24, 1, false, false, false));
                        state.exposedTargets.put(mob.getId(), level.getGameTime() + 50L);
                        level.sendParticles(ParticleTypes.LAVA, mob.getX(), mob.getY() + 0.9, mob.getZ(), 4, 0.3, 0.3, 0.3, 0.05);
                    }
                }
            }
            if (state.sunAfterimageTicks == 0) {
                state.sunAfterimagePos = null;
            }
        }

        if (!holdingSunBlade) {
            if (state.sunCycleStep > 0 || state.sunCycleHolding) {
                resetSunCycle(state);
                state.dirty = true;
            }
            return;
        }

        // 赫刀激活时：持续飘散炽热余烬火星
        if (isRedBladeActive(state) && player.tickCount % 5 == 0) {
            Vec3 fwd = BreathingManager.horizontalLook(player);
            Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x);
            Vec3 bladePt = player.position().add(0.0, 1.08, 0.0).add(fwd.scale(0.65)).add(right.scale(0.32));
            level.sendParticles(ParticleTypes.FLAME, bladePt.x, bladePt.y, bladePt.z, 2, 0.14, 0.14, 0.14, 0.015);
        }

        // 第十三型 · 日之圆环：窗口倒计时与按住右键自动无缝衔接下一招！
        if (state.sunCycleWindowTicks > 0) {
            state.sunCycleWindowTicks--;
            if (state.sunCycleHolding && state.globalRecoveryTicks <= 0 && state.sunBurstBusyTicks <= 0) {
                // 按住右键自动顺畅衔接下一招（1→12→1 循环！）
                tryCastSunForm(player, level, state, SunForm.THIRTEENTH_SUN_CYCLE, state.sunCycleHoldCtx, true);
            } else if (state.sunCycleWindowTicks == 0) {
                resetSunCycle(state);
                state.dirty = true;
            }
        } else if (state.sunCycleHolding && state.globalRecoveryTicks <= 0 && state.sunBurstBusyTicks <= 0) {
            tryCastSunForm(player, level, state, SunForm.THIRTEENTH_SUN_CYCLE, state.sunCycleHoldCtx, true);
        }
    }

    public static void resetSunCycle(BreathingManager.PlayerBreathState state) {
        state.sunCycleStep = 0;
        state.sunCycleNextIndex = 0;
        state.sunCycleWindowTicks = 0;
        state.sunCycleComboCount = 0;
        state.sunCycleHolding = false;
    }

    /**
     * 日之呼吸统一释放入口（支持 1~12 单招自由施放 + 第 13 槽位「第十三型·日之圆环」无缝循环连舞！）
     */
    public static void tryCastSunForm(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            SunForm selectedForm, int ctx, boolean autoChain
    ) {
        if (state.sunBurstBusyTicks > 0) return;

        boolean isThirteenth = (selectedForm == SunForm.THIRTEENTH_SUN_CYCLE);
        if (!isThirteenth && state.globalRecoveryTicks > 0) return;
        if (isThirteenth && state.globalRecoveryTicks > 0) return;

        boolean airborne = BreathingActionPayload.unpackAirborne(ctx) || (!player.onGround() && !player.isInWater());
        boolean sneak = BreathingActionPayload.unpackSneak(ctx) || player.isShiftKeyDown();
        boolean sprinting = BreathingActionPayload.unpackSprinting(ctx) || player.isSprinting();
        int fwdSign = BreathingActionPayload.unpackFwdSign(ctx);
        int strafeSign = BreathingActionPayload.unpackStrafeSign(ctx);

        SunForm actualBase;
        SunForm resolvedForm;
        float cycleDamageMult = 1.0F;
        float cost;
        int recoveryTicks;
        boolean inCycleMode = false;

        if (isThirteenth) {
            inCycleMode = true;
            // 若圆环已超时，自动从第 1 型「圆舞」起手；否则无缝衔接下一顺位 1→12→1！
            if (state.sunCycleWindowTicks <= 0) {
                state.sunCycleNextIndex = 0;
                state.sunCycleComboCount = 0;
            }
            int idx = Math.floorMod(state.sunCycleNextIndex, SunForm.CYCLE_ORDER.length);
            actualBase = SunForm.getCycleStep(idx);
            resolvedForm = actualBase.resolveVariant(sprinting || sneak);

            // 圆环连舞越舞越流畅：耗气递减（最低 55%），威力逐段叠加（每招 +4%，最高 +48%）
            float discount = Math.max(0.55F, 0.84F - state.sunCycleComboCount * 0.03F);
            cost = Math.max(7.5F, actualBase.getBreathCost() * discount);
            if (state.totalConcentration) cost *= 0.85F;
            cycleDamageMult = 1.0F + Math.min(0.48F, state.sunCycleComboCount * 0.04F);
            // 极速流畅衔接，不卡手！
            recoveryTicks = Math.max(5, Math.round(actualBase.getRecoveryTicks() * 0.62F));
        } else {
            // 切出单独施放 1~12 型时收起圆环计数
            if (state.sunCycleStep > 0) {
                resetSunCycle(state);
            }
            actualBase = selectedForm;
            resolvedForm = actualBase.resolveVariant(sprinting || sneak);

            int cd = state.sunFormCooldowns[actualBase.ordinal()];
            if (cd > 0 && !player.getAbilities().instabuild) {
                player.sendOverlayMessage(Component.translatable("message.nichirin.on_cooldown")
                        .withStyle(ChatFormatting.GRAY));
                return;
            }
            cost = resolvedForm.getBreathCost();
            if (state.totalConcentration) cost *= 0.85F;
            recoveryTicks = resolvedForm.getRecoveryTicks();
        }

        if (state.breath < cost && !player.getAbilities().instabuild) {
            if (inCycleMode) {
                resetSunCycle(state);
            }
            if (!autoChain) {
                player.sendOverlayMessage(Component.translatable("message.nichirin.low_breath")
                        .withStyle(ChatFormatting.GOLD));
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.65F, 0.72F);
            }
            BreathingManager.syncPlayer(player, state);
            return;
        }

        if (!player.getAbilities().instabuild) {
            state.breath = Math.max(0.0F, state.breath - cost);
            if (!inCycleMode) {
                state.sunFormCooldowns[actualBase.ordinal()] = actualBase.getCooldownTicks();
            }
            ItemStack mainHand = player.getMainHandItem();
            if (!mainHand.isEmpty()) {
                mainHand.hurtAndBreak(resolvedForm.isUltimate() ? 3 : 1, player, InteractionHand.MAIN_HAND);
            }
        }

        // 推进第十三型圆环计数与满环共鸣奖励
        if (inCycleMode) {
            int stepNum = Math.floorMod(state.sunCycleNextIndex, SunForm.CYCLE_ORDER.length) + 1; // 1..12
            state.sunCycleStep = stepNum;
            state.sunCycleNextIndex = (state.sunCycleNextIndex + 1) % SunForm.CYCLE_ORDER.length;
            state.sunCycleComboCount++;
            state.sunCycleWindowTicks = 36; // 1.8 秒丝滑衔接窗口
            state.sunCycleHoldCtx = ctx;

            // 完成一整圈 12 招（炎舞闭环！）触发「破晓圆环 · 日轮圆满」强力回气与脉动！
            if (stepNum == 12) {
                state.breath = Math.min(BreathingManager.MAX_BREATH, state.breath + 28.0F);
                WaterVfxPayload.sendNear(
                        level, WaterVfxPayload.KIND_BREATH_PULSE, player.getId(),
                        player.getX(), player.getY() + 0.9, player.getZ(),
                        player.getYRot(), 5.6F, 1.0F, 0.0F,
                        18, true, true, true
                );
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.85F, 1.35F);
                player.sendOverlayMessage(Component.translatable("message.nichirin.sun_cycle_complete")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            }
        }

        state.globalRecoveryTicks = recoveryTicks;
        state.lastCastFormOrdinal = resolvedForm.ordinal();
        state.castCounter++;
        state.inhaling = false;
        state.dirty = true;

        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

        boolean tc = state.totalConcentration || inCycleMode;

        switch (resolvedForm) {
            case FIRST_DANCE -> castFirstDance(player, level, state, tc, cycleDamageMult);
            case FIRST_DANCING_FLASH -> castFirstDancingFlash(player, level, state, tc, cycleDamageMult);
            case SECOND_CLEAR_BLUE_SKY -> castSecondClearBlueSky(player, level, state, tc, cycleDamageMult);
            case THIRD_RAGING_SUN -> castThirdRagingSun(player, level, state, tc, cycleDamageMult);
            case FOURTH_FAKE_RAINBOW -> castFourthFakeRainbow(player, level, state, tc, fwdSign, strafeSign);
            case FIFTH_FIRE_WHEEL -> castFifthFireWheel(player, level, state, tc, airborne, cycleDamageMult);
            case SIXTH_BURNING_BONES -> castSixthBurningBones(player, level, state, tc, cycleDamageMult);
            case SEVENTH_SUNFLOWER_THRUST -> castSeventhSunflowerThrust(player, level, state, tc, cycleDamageMult);
            case EIGHTH_SOLAR_HEAT_HAZE -> castEighthSolarHeatHaze(player, level, state, tc, cycleDamageMult);
            case NINTH_SETTING_SUN -> castNinthSettingSun(player, level, state, tc, cycleDamageMult);
            case TENTH_BENEFICENT_RADIANCE -> castTenthBeneficentRadiance(player, level, state, tc, airborne, cycleDamageMult);
            case ELEVENTH_DRAGON_SUN_HALO -> castEleventhDragonSunHalo(player, level, state, tc, cycleDamageMult, inCycleMode);
            case TWELFTH_FLAME_DANCE -> castTwelfthFlameDance(player, level, state, tc, cycleDamageMult);
            case THIRTEENTH_SUN_CYCLE -> castFirstDance(player, level, state, tc, cycleDamageMult);
        }

        BreathingManager.updateHeldBladeVisual(player, state);
        BreathingManager.syncPlayer(player, state);
    }

    // ==================== 1. 圆舞 ====================
    private static void castFirstDance(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 5.4F * (tc ? 1.18F : 1.0F);
        final float damage = 14.5F * (tc ? 1.26F : 1.0F) * mult;
        final int duration = 14;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_SLASH, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), reach, 6.0F, 255.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.95F, 1.05F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.10F, 0.95F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            boolean burstDone = false;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (!burstDone && curT >= 0.22F) {
                    burstDone = true;
                    Vec3 fwd = BreathingManager.horizontalLook(p);
                    p.push(fwd.x * 0.42, 0.04, fwd.z * 0.42);
                    BreathingManager.syncEntityVelocity(p);
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, fwd, reach, -0.35, true, true);
                    lv.sendParticles(ParticleTypes.FLAME, curOrigin.x + fwd.x * 1.6, curOrigin.y, curOrigin.z + fwd.z * 1.6,
                            18, 1.1, 0.35, 1.1, 0.08);
                }

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_SURFACE_SLASH, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 16,
                        reach + 1.2, 0.62, damage, 0.72, 0.24, 1.45F, true
                );
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 1·衍生. 圆舞一闪（融合雷呼瞬步！） ====================
    private static void castFirstDancingFlash(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 6.0F * (tc ? 1.16F : 1.0F);
        final float damage = 18.2F * (tc ? 1.26F : 1.0F) * mult;
        final int duration = 12;

        Vec3 dashDir = BreathingManager.horizontalLook(player);
        player.setDeltaMovement(dashDir.x * 1.78, Math.max(0.10, player.getDeltaMovement().y * 0.25 + 0.10), dashDir.z * 1.78);
        BreathingManager.syncEntityVelocity(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 18);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_DANCING_FLASH, reach, 0.0F,
                duration, tc, true, true
        );
        // 雷步瞬闪炸响 + 烈焰怒燃双重音效
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.45F, 1.85F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.15F, 1.28F);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 0.9, player.getZ(),
                22, 0.6, 0.4, 0.6, 0.22);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (animTime <= 4.0F) {
                    lv.sendParticles(ParticleTypes.FLAME, curOrigin.x, curOrigin.y, curOrigin.z, 8, 0.45, 0.35, 0.45, 0.07);
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, dashDir, reach, -0.45, true, true);
                }

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_SUN_DANCING_FLASH, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 16,
                        reach + 1.4, 0.72, damage, 0.92, 0.30, 1.85F, true
                );
                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 2. 碧罗之天（360° 垂直升天挑斩！） ====================
    private static void castSecondClearBlueSky(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 4.9F * (tc ? 1.15F : 1.0F);
        final float damage = 15.8F * (tc ? 1.24F : 1.0F) * mult;
        final int duration = 16;

        Vec3 fwd = BreathingManager.horizontalLook(player);
        player.setDeltaMovement(fwd.x * 0.56, 0.52, fwd.z * 0.56);
        BreathingManager.syncEntityVelocity(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 24);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_CLEAR_BLUE_SKY, reach, 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.05F, 1.12F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.15F, 1.15F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (animTime <= 6.0F) {
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, BreathingManager.horizontalLook(p), reach, -0.5, true, true);
                }

                // 碧罗之天强力垂直上挑：对空中目标增伤 + 强力挑空！
                AABB box = new AABB(curOrigin.x - reach - 1.0, curOrigin.y - 2.0, curOrigin.z - reach - 1.0,
                        curOrigin.x + reach + 1.0, curOrigin.y + reach + 1.5, curOrigin.z + reach + 1.0);
                List<LivingEntity> cands = lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p);
                List<BladeKinematics.BladeContact> hits = BladeKinematics.sweepBladeContacts(
                        BladeKinematics.ANIM_SUN_CLEAR_BLUE_SKY, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 10, 0.72
                );
                for (BladeKinematics.BladeContact c : hits) {
                    boolean airBonus = !c.target().onGround();
                    float hitDmg = airBonus ? damage * 1.25F : damage;
                    Vec3 knock = new Vec3(c.cutTangent().x * 0.45, 0.68, c.cutTangent().z * 0.45);
                    BreathingManager.applyNichirinStrike(p, lv, st, c.target(), hitDmg,
                            c.contactPoint(), c.cutTangent(), knock, true, true, 1.65F, true);
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 3. 烈日红镜（左右「∞」字高速交叉双弧 + 弹射物斩灭！） ====================
    private static void castThirdRagingSun(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 5.2F * (tc ? 1.15F : 1.0F);
        final float perArcDmg = 10.2F * (tc ? 1.24F : 1.0F) * mult;
        final int duration = 16;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_RAGING_SUN, reach, 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.05F, 1.22F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            boolean secondSound = false;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();

                if (!secondSound && curT >= 0.50F) {
                    secondSound = true;
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.95F, 1.32F);
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, BreathingManager.horizontalLook(p), reach + 0.8F, -0.8, true, true);
                }

                // 烈日红镜攻防一体：交叉火弧持续焚毁周身 6.0 格内敌方弹射物！
                AABB projBox = p.getBoundingBox().inflate(6.0, 3.2, 6.0);
                for (Projectile pr : lv.getEntitiesOfClass(Projectile.class, projBox,
                        x -> x.isAlive() && !x.onGround() && x.getOwner() != p)) {
                    lv.sendParticles(ParticleTypes.FLAME, pr.getX(), pr.getY(), pr.getZ(), 8, 0.2, 0.2, 0.2, 0.08);
                    lv.playSound(null, pr.getX(), pr.getY(), pr.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.55F, 1.6F);
                    pr.discard();
                }

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_SUN_RAGING_SUN, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 6,
                        reach + 1.2, 0.70, perArcDmg, 0.58, 0.20, 1.35F, false
                );

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 4. 幻日虹（高速扭转闪避 + 原地炽热虹光残像诱敌！） ====================
    private static void castFourthFakeRainbow(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            boolean tc, int fwdSign, int strafeSign
    ) {
        Vec3 originPos = player.position();
        float originYaw = player.getYRot();

        // 计算闪避方向：响应 WASD；无按键时自动向右侧后方高速扭转拉身位
        Vec3 fwd = BreathingManager.horizontalLook(player);
        Vec3 left = new Vec3(fwd.z, 0.0, -fwd.x).normalize();
        Vec3 evadeDir;
        if (fwdSign != 0 || strafeSign != 0) {
            evadeDir = fwd.scale(fwdSign).add(left.scale(strafeSign));
            if (evadeDir.lengthSqr() > 1.0E-4) evadeDir = evadeDir.normalize();
            else evadeDir = left.scale(-1.0);
        } else {
            evadeDir = left.scale(-0.85).add(fwd.scale(-0.52)).normalize();
        }
        float evadeYaw = (float) (Mth.atan2(-evadeDir.x, evadeDir.z) * Mth.RAD_TO_DEG);

        // 1. 原地留下 3D 炽热虹光残像！
        state.sunAfterimagePos = originPos;
        state.sunAfterimageTicks = tc ? 36 : 28;
        state.fakeRainbowTicks = tc ? 40 : 32;
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 24);
        BreathingManager.updateHazeSpeed(player, true);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_AFTERIMAGE, player.getId(),
                originPos.x, originPos.y, originPos.z,
                originYaw, evadeYaw, tc ? 1.15F : 1.0F, 0.0F,
                state.sunAfterimageTicks, tc, true, true
        );
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_DEAD_CALM_DOMAIN, player.getId(),
                originPos.x, originPos.y, originPos.z,
                originYaw, 4.2F, 0.0F, 0.0F,
                state.sunAfterimageTicks, tc, false, true
        );

        // 2. 玩家本体瞬速滑步脱离原位
        player.setDeltaMovement(evadeDir.x * 1.48, 0.28, evadeDir.z * 1.48);
        BreathingManager.syncEntityVelocity(player);

        level.playSound(null, originPos.x, originPos.y, originPos.z,
                SoundEvents.BREEZE_SLIDE, SoundSource.PLAYERS, 1.05F, 1.55F);
        level.playSound(null, originPos.x, originPos.y, originPos.z,
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.85F, 1.45F);
        level.sendParticles(ParticleTypes.FLAME, originPos.x, originPos.y + 1.0, originPos.z, 18, 0.4, 0.6, 0.4, 0.06);
    }

    // ==================== 5. 火车（腾空跃起翻越绕背 + 烈焰纵轮劈斩！） ====================
    private static void castFifthFireWheel(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            boolean tc, boolean airborne, float mult
    ) {
        final float reach = 4.3F * (tc ? 1.16F : 1.0F);
        final float damage = 13.8F * (tc ? 1.24F : 1.0F) * mult;
        final int duration = 18;

        Vec3 fwd = BreathingManager.horizontalLook(player);
        player.setDeltaMovement(fwd.x * 0.95, airborne ? 0.42 : 0.72, fwd.z * 0.95);
        BreathingManager.syncEntityVelocity(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 34);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_WHEEL, player.getId(),
                player.getX(), player.getY() + 1.0, player.getZ(),
                player.getYRot(), reach, 0.0F, 6.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.05F, 0.98F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.90F, 1.18F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            boolean crashDone = false;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (animTime >= 7.0F && animTime <= 12.0F) {
                    // 翻过顶点后顺下坠之势加速压下劈斩！
                    Vec3 v = p.getDeltaMovement();
                    p.setDeltaMovement(v.x, Math.min(v.y, -0.52), v.z);
                    BreathingManager.syncEntityVelocity(p);
                }

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_WATER_WHEEL, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 8,
                        reach + 1.1, 0.66, damage, 0.68, 0.22, 1.55F, true
                );

                if (!crashDone && (curT >= 0.78F || (animTime >= 9.0F && p.onGround()))) {
                    crashDone = true;
                    Vec3 f = BreathingManager.horizontalLook(p);
                    Vec3 impact = p.position().add(f.scale(1.6));
                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            impact.x, impact.y + 0.05, impact.z,
                            p.getYRot(), reach * 0.95F, 0.0F, 1.0F,
                            14, tc, false, true
                    );
                    BladeWorldPhysics.processBladeSweep(p, lv, st, impact, f, reach, -0.6, true, true);
                    lv.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.45F, 1.45F);
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 6. 灼骨炎阳（螺旋焚骨烈焰漩涡 + 强效 6 秒禁疗灼骨！） ====================
    private static void castSixthBurningBones(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float radius = 4.6F * (tc ? 1.18F : 1.0F);
        final float height = 5.4F;
        final float tickDamage = 6.2F * (tc ? 1.24F : 1.0F) * mult;
        final int duration = 24;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WHIRLPOOL, player.getId(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), radius, height, 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.20F, 0.82F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.15F, 0.88F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            int tick = 0;
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                tick++;
                BreathingManager.decrementHitCooldowns(hitCd);

                if (tick % 4 == 0) {
                    AABB box = p.getBoundingBox().inflate(radius, 3.2, radius);
                    for (Projectile pr : lv.getEntitiesOfClass(Projectile.class, box,
                            x -> x.isAlive() && !x.onGround() && x.getOwner() != p)) {
                        lv.sendParticles(ParticleTypes.FLAME, pr.getX(), pr.getY(), pr.getZ(), 6, 0.2, 0.2, 0.2, 0.06);
                        pr.discard();
                    }
                    for (LivingEntity target : lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p)) {
                        if (target.distanceToSqr(p) > (radius + 0.8) * (radius + 0.8)) continue;
                        if (hitCd.getOrDefault(target.getId(), 0) > 0) continue;
                        hitCd.put(target.getId(), 5);
                        Vec3 away = target.position().subtract(p.position());
                        Vec3 h = away.horizontalDistanceSqr() > 1.0E-4 ? new Vec3(away.x, 0.0, away.z).normalize() : Vec3.ZERO;
                        Vec3 swirl = new Vec3(-h.z * 0.32 + h.x * 0.22, 0.14, h.x * 0.32 + h.z * 0.22);
                        if (BreathingManager.applyNichirinStrike(p, lv, st, target, tickDamage, swirl, true, true, 1.25F, tick >= duration - 4)) {
                            applyScorch(target, lv, 130L); // 长达 6.5 秒强效灼骨禁疗！
                        }
                    }
                    BladeWorldPhysics.processBladeSweep(p, lv, st, p.position().add(0, 1.0, 0),
                            BreathingManager.horizontalLook(p), radius, -1.0, true, tick >= duration - 4);
                }

                if (tick == duration) {
                    // 收尾向外炸开一圈炽烈日炎冲击环
                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            p.getX(), p.getY() + 0.15, p.getZ(),
                            p.getYRot(), radius * 1.12F, 0.0F, 1.0F,
                            15, tc, false, true
                    );
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.10F, 1.15F);
                }
                return tick < duration;
            }
        });
    }

    // ==================== 7. 阳华突（日之呼吸唯一突刺技 + 3D 炽金日冕环！） ====================
    private static void castSeventhSunflowerThrust(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float damage = 17.5F * (tc ? 1.25F : 1.0F) * mult;
        final int duration = 11;

        Vec3 aim = player.getLookAngle().normalize();
        Vec3 hDir = BreathingManager.horizontalLook(player);
        player.setDeltaMovement(hDir.x * 1.48, Math.max(0.08, aim.y * 0.45), hDir.z * 1.48);
        BreathingManager.syncEntityVelocity(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 18);

        Vec3 tipPos = player.position().add(0.0, 1.15, 0.0).add(aim.scale(2.6));
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_RIPPLE_THRUST, player.getId(),
                tipPos.x, tipPos.y, tipPos.z,
                player.getYRot(), 2.8F * (tc ? 1.15F : 1.0F), player.getXRot(), 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.10F, 1.28F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.05F, 1.38F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            boolean ringBurst = false;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();

                AABB box = p.getBoundingBox().inflate(5.8, 2.5, 5.8);
                List<LivingEntity> cands = lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p);
                List<BladeKinematics.BladeContact> hits = BladeKinematics.sweepBladeContacts(
                        BladeKinematics.ANIM_DROP_RIPPLE_THRUST, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 16, 0.65
                );
                for (BladeKinematics.BladeContact c : hits) {
                    Vec3 knock = aim.scale(1.15).add(0.0, 0.22, 0.0);
                    if (BreathingManager.applyNichirinStrike(p, lv, st, c.target(), damage,
                            c.contactPoint(), aim, knock, true, true, 1.85F, true)) {
                        applyScorch(c.target(), lv, 100L);
                        if (!ringBurst) {
                            ringBurst = true;
                            WaterVfxPayload.sendNear(
                                    lv, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                                    c.contactPoint().x, c.contactPoint().y, c.contactPoint().z,
                                    p.getYRot(), 3.3F, p.getXRot(), 0.0F,
                                    14, tc, false, true
                            );
                        }
                    }
                }
                if (animTime <= 4.0F) {
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin.add(aim.scale(2.0)), aim, 4.2F, 0.25, true, true);
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 8. 飞轮阳炎（7.4 格超长阳炎错觉斩 + 破盾穿甲！） ====================
    private static void castEighthSolarHeatHaze(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 7.4F * (tc ? 1.12F : 1.0F);
        final float damage = 15.2F * (tc ? 1.24F : 1.0F) * mult;
        final int duration = 14;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_HEAT_HAZE, reach, 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 0.95F, 0.85F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.95F, 1.32F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (animTime <= 5.0F) {
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, BreathingManager.horizontalLook(p), 6.2F, -0.35, true, true);
                }

                // 飞轮阳炎：7.4 格超远阳炎延伸判定 + 破盾 + 30% 穿甲热浪追伤！
                AABB box = p.getBoundingBox().inflate(reach + 1.2, 3.0, reach + 1.2);
                List<LivingEntity> cands = lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p);
                List<BladeKinematics.BladeContact> hits = BladeKinematics.sweepBladeContacts(
                        BladeKinematics.ANIM_SUN_HEAT_HAZE, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 16, 0.78
                );
                for (BladeKinematics.BladeContact c : hits) {
                    LivingEntity target = c.target();
                    // 阳炎错觉破防：如果目标正在举盾格挡，直接打断其格挡！
                    if (target.isBlocking()) {
                        target.stopUsingItem();
                        lv.playSound(null, target.getX(), target.getY(), target.getZ(),
                                SoundEvents.SHIELD_BREAK.value(), SoundSource.PLAYERS, 1.0F, 1.15F);
                    }
                    Vec3 knock = c.cutTangent().scale(0.68).add(0.0, 0.22, 0.0);
                    if (BreathingManager.applyNichirinStrike(p, lv, st, target, damage,
                            c.contactPoint(), c.cutTangent(), knock, true, true, 1.55F, true)) {
                        // 附加 30% 穿甲热浪直伤（魔法火焰伤害绕过厚重护甲）
                        if (target.isAlive()) {
                            target.setInvulnerableTime(0);
                            target.hurtServer(lv, lv.damageSources().inFire(), damage * 0.30F);
                        }
                        applyScorch(target, lv, 90L);
                    }
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 9. 斜阳转身（腾空 180° 倒立逆转横斩！） ====================
    private static void castNinthSettingSun(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 5.4F * (tc ? 1.16F : 1.0F);
        final float damage = 17.8F * (tc ? 1.26F : 1.0F) * mult;
        final int duration = 15;

        Vec3 fwd = BreathingManager.horizontalLook(player);
        player.setDeltaMovement(fwd.x * 0.72, 0.62, fwd.z * 0.72);
        BreathingManager.syncEntityVelocity(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 28);

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.1, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_SETTING_SUN, reach, 0.0F,
                duration, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.20F, 0.85F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.05F, 1.12F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            float animTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(animTime / duration, 0.0F, 1.0F);
                animTime += step;
                float curT = Mth.clamp(animTime / duration, 0.0F, 1.0F);

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();
                if (animTime >= 4.0F && animTime <= 7.0F) {
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, BreathingManager.horizontalLook(p), reach, -0.5, true, true);
                }

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_SUN_SETTING_SUN, prevT, curT, 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 16,
                        reach + 1.3, 0.72, damage, 0.88, -0.18, 1.80F, true
                );

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                return animTime < duration;
            }
        });
    }

    // ==================== 10. 辉辉恩光（凌空螺旋太阳炎瀑下劈！） ====================
    private static void castTenthBeneficentRadiance(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            boolean tc, boolean airborne, float mult
    ) {
        final double startY = player.getY();
        Vec3 fwd = BreathingManager.horizontalLook(player);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 42);

        if (!airborne) {
            // 地面施放先凌空跃起，4 tick 后螺旋下坠轰砸大地
            player.setDeltaMovement(fwd.x * 0.62, 0.86, fwd.z * 0.62);
            BreathingManager.syncEntityVelocity(player);
        }
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATERFALL_DIVE_START, player.getId(),
                player.getX(), player.getY() + 1.0, player.getZ(),
                player.getYRot(), 0.0F, 0.0F, 0.0F,
                16, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.05F, 1.25F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            int tick = 0;
            final int diveStartTick = airborne ? 1 : 5;
            double peakY = startY;

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                tick++;
                peakY = Math.max(peakY, p.getY());
                if (tick >= diveStartTick) {
                    Vec3 f = BreathingManager.horizontalLook(p);
                    p.setDeltaMovement(f.x * 0.45, -1.42, f.z * 0.45);
                    BreathingManager.syncEntityVelocity(p);
                    lv.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 0.9, p.getZ(), 10, 0.55, 0.45, 0.55, 0.07);
                }

                boolean landed = tick > diveStartTick && (p.onGround() || p.isInWater() || tick >= 22);
                if (landed) {
                    float dropBlocks = (float) Math.max(0.0, peakY - p.getY());
                    float heightBonus = 1.0F + Mth.clamp(dropBlocks * 0.075F, 0.0F, 0.65F);
                    float radius = (5.2F + Mth.clamp(dropBlocks * 0.14F, 0.0F, 1.8F)) * (tc ? 1.15F : 1.0F);
                    float dmg = 20.5F * (tc ? 1.24F : 1.0F) * heightBonus * mult;

                    Vec3 impact = p.position().add(BreathingManager.horizontalLook(p).scale(1.4));
                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_WATERFALL_BASIN, p.getId(),
                            impact.x, impact.y, impact.z,
                            p.getYRot(), radius, Math.max(5.5F, dropBlocks + 4.5F), 0.0F,
                            22, tc, true, true
                    );
                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_RIPPLE_THRUST, p.getId(),
                            impact.x, impact.y + 0.06, impact.z,
                            p.getYRot(), radius * 1.15F, 0.0F, 1.0F,
                            16, tc, false, true
                    );
                    lv.playSound(null, impact.x, impact.y, impact.z,
                            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.85F, 1.15F);
                    lv.playSound(null, impact.x, impact.y, impact.z,
                            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.25F, 0.85F);

                    AABB box = new AABB(impact.x - radius, impact.y - 2.2, impact.z - radius,
                            impact.x + radius, impact.y + 4.0, impact.z + radius);
                    for (LivingEntity target : lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p)) {
                        double d = target.position().distanceTo(impact);
                        if (d > radius + 0.7) continue;
                        Vec3 away = target.position().subtract(impact);
                        Vec3 h = away.horizontalDistanceSqr() > 1.0E-4 ? new Vec3(away.x, 0.0, away.z).normalize() : Vec3.ZERO;
                        Vec3 knock = new Vec3(h.x * 0.95, 0.56, h.z * 0.95);
                        if (BreathingManager.applyNichirinStrike(p, lv, st, target, dmg, knock, true, true, 2.1F, true)) {
                            applyScorch(target, lv, 120L);
                        }
                    }
                    BladeWorldPhysics.processBladeSweep(p, lv, st, impact.add(0, 0.8, 0),
                            BreathingManager.horizontalLook(p), radius, -1.0, true, true);
                    return false;
                }
                return true;
            }
        });
    }

    // ==================== 11. 日晕之龙·头舞（高速多目标游龙 4 段穿锁连斩 + 3D 太阳神龙！） ====================
    private static void castEleventhDragonSunHalo(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            boolean tc, float mult, boolean inCycleMode
    ) {
        final int totalDashes = inCycleMode ? 3 : 4;
        final int ticksPerDash = 5;
        final int totalDur = totalDashes * ticksPerDash + 6;
        final float dashDamage = 13.5F * (tc ? 1.24F : 1.0F) * mult;
        final float finaleDamage = 19.5F * (tc ? 1.28F : 1.0F) * mult;

        state.dragonRushTicks = Math.max(state.dragonRushTicks, totalDur + 8);
        state.fallImmuneTicks = Math.max(state.fallImmuneTicks, totalDur + 20);
        state.sunBurstBusyTicks = totalDashes * ticksPerDash + 2;

        // 召唤 3D 雕琢太阳烈焰神龙紧缠周身咆哮！
        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_WATER_DRAGON, player.getId(),
                player.getX(), player.getY() + 1.0, player.getZ(),
                player.getYRot(), 14.0F, tc ? 4.0F : 3.0F, player.getXRot(),
                totalDur + 18, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 0.72F, 1.18F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.25F, 0.92F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            int tick = 0;
            int currentDash = -1;
            float dashAnimTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Set<Integer> lockedTargetHistory = new HashSet<>();
            final Map<Integer, Integer> hitCd = new HashMap<>();
            boolean finaleDone = false;

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                int dashIdx = tick / ticksPerDash;

                if (dashIdx < totalDashes && dashIdx != currentDash) {
                    currentDash = dashIdx;
                    dashAnimTime = 0.0F;

                    // 智能搜索周围 10.5 格内尚未被本次游龙斩过的不同目标（还原锻刀村篇连斩半天狗多具分身！）
                    Vec3 aimFwd = BreathingManager.horizontalLook(p);
                    Vec3 dashDir = pickNextDragonDashDir(p, lv, aimFwd, dashIdx, lockedTargetHistory);
                    float dashYaw = (float) (Mth.atan2(-dashDir.x, dashDir.z) * Mth.RAD_TO_DEG);

                    double speed = 1.48 + dashIdx * 0.12;
                    p.setDeltaMovement(dashDir.x * speed, 0.16, dashDir.z * speed);
                    BreathingManager.syncEntityVelocity(p);

                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_SUN_FORM_VFX, p.getId(),
                            p.getX(), p.getY() + 1.05, p.getZ(),
                            dashYaw, BladeKinematics.ANIM_SUN_HALO_DRAGON_DASH, 5.4F, (float) dashIdx,
                            ticksPerDash + 2, tc, true, true
                    );
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.95F, 1.05F + dashIdx * 0.12F);
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.15F, 1.05F + dashIdx * 0.10F);
                    BladeWorldPhysics.processBladeSweep(p, lv, st, p.position().add(0, 1.0, 0), dashDir, 5.4F, -0.45, true, true);
                }

                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();

                if (currentDash >= 0 && currentDash < totalDashes) {
                    float prevT = Mth.clamp(dashAnimTime / (ticksPerDash + 2.0F), 0.0F, 1.0F);
                    dashAnimTime += 1.0F;
                    float curT = Mth.clamp(dashAnimTime / (ticksPerDash + 2.0F), 0.0F, 1.0F);

                    AABB box = p.getBoundingBox().inflate(6.4, 3.0, 6.4);
                    List<LivingEntity> cands = lv.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != p);
                    List<BladeKinematics.BladeContact> hits = BladeKinematics.sweepBladeContacts(
                            BladeKinematics.ANIM_SUN_HALO_DRAGON_DASH, prevT, curT, currentDash, tc, true,
                            prevOrigin, curOrigin, prevYaw, curYaw, cands, hitCd, 4, 0.85
                    );
                    for (BladeKinematics.BladeContact c : hits) {
                        Vec3 knock = c.cutTangent().scale(0.65).add(0.0, 0.24, 0.0);
                        if (BreathingManager.applyNichirinStrike(p, lv, st, c.target(), dashDamage,
                                c.contactPoint(), c.cutTangent(), knock, true, true, 1.75F, true)) {
                            applyScorch(c.target(), lv, 120L);
                        }
                    }
                    lv.sendParticles(ParticleTypes.FLAME, curOrigin.x, curOrigin.y, curOrigin.z, 8, 0.6, 0.4, 0.6, 0.07);
                }

                // 游龙 4 段狂舞收束：刀锋落点释放炽烈日轮热浪爆发（干净符合直觉，0 柱子！）
                if (!finaleDone && tick >= totalDashes * ticksPerDash) {
                    finaleDone = true;
                    Vec3 fwd = BreathingManager.horizontalLook(p);
                    Vec3 blastPos = p.position().add(fwd.scale(2.4));
                    float blastR = 5.6F * (tc ? 1.15F : 1.0F);

                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_SUN_DRAGON_FINALE, p.getId(),
                            blastPos.x, blastPos.y, blastPos.z,
                            p.getYRot(), blastR, 0.0F, 4.0F,
                            28, tc, true, true
                    );
                    lv.playSound(null, blastPos.x, blastPos.y, blastPos.z,
                            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.90F, 1.05F);
                    lv.playSound(null, blastPos.x, blastPos.y, blastPos.z,
                            SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.30F, 0.82F);

                    AABB fBox = new AABB(blastPos.x - blastR, blastPos.y - 2.2, blastPos.z - blastR,
                            blastPos.x + blastR, blastPos.y + 4.0, blastPos.z + blastR);
                    for (LivingEntity target : lv.getEntitiesOfClass(LivingEntity.class, fBox, e -> e.isAlive() && e != p)) {
                        if (target.position().distanceTo(blastPos) > blastR + 0.8) continue;
                        Vec3 away = target.position().subtract(blastPos);
                        Vec3 h = away.horizontalDistanceSqr() > 1.0E-4 ? new Vec3(away.x, 0.0, away.z).normalize() : fwd;
                        if (BreathingManager.applyNichirinStrike(p, lv, st, target, finaleDamage,
                                new Vec3(h.x * 1.15, 0.58, h.z * 1.15), true, true, 2.4F, true)) {
                            applyScorch(target, lv, 140L);
                        }
                    }
                    BladeWorldPhysics.processBladeSweep(p, lv, st, blastPos.add(0, 0.8, 0), fwd, blastR, -1.0, true, true);
                }

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                tick++;
                return tick < totalDur;
            }
        });
    }

    /** 为「日晕之龙·头舞」挑选下一段游龙冲刺矢量：优先穿锁周围尚未斩过的新目标，无新目标则蛇形游龙掠进！ */
    private static Vec3 pickNextDragonDashDir(
            ServerPlayer player, ServerLevel level, Vec3 aimFwd, int dashIdx, Set<Integer> visited
    ) {
        AABB box = player.getBoundingBox().inflate(10.5, 4.0, 10.5);
        List<LivingEntity> enemies = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != player && (e instanceof Enemy || e.getLastHurtByMob() == player));
        if (!enemies.isEmpty()) {
            // 优先选还没被本次头舞锁定过的新敌怪，其次选距离最近敌怪
            LivingEntity best = enemies.stream()
                    .filter(e -> !visited.contains(e.getId()))
                    .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                    .orElseGet(() -> enemies.stream()
                            .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                            .orElse(null));
            if (best != null) {
                visited.add(best.getId());
                Vec3 to = best.position().subtract(player.position());
                Vec3 horiz = new Vec3(to.x, 0.0, to.z);
                if (horiz.lengthSqr() > 0.25) {
                    return horiz.normalize();
                }
            }
        }
        // 无多目标时沿准星方向做华丽蛇形交替折返（±24° 游龙 S 轨迹！）
        Vec3 right = new Vec3(-aimFwd.z, 0.0, aimFwd.x).normalize();
        float zigDeg = (dashIdx % 2 == 0) ? -22.0F : 22.0F;
        return BladeKinematics.rotateHorizontal(aimFwd, right, zigDeg);
    }

    // ==================== 12. 炎舞（重斩 + 回旋追劈强力二连击，闭环衔接圆舞！） ====================
    private static void castTwelfthFlameDance(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state, boolean tc, float mult
    ) {
        final float reach = 5.4F * (tc ? 1.15F : 1.0F);
        final float hit1Dmg = 12.8F * (tc ? 1.24F : 1.0F) * mult;
        final float hit2Dmg = 16.5F * (tc ? 1.28F : 1.0F) * mult;
        final int step1Dur = 7;
        final int step2Dur = 9;
        final int totalDur = step1Dur + step2Dur;

        WaterVfxPayload.sendNear(
                level, WaterVfxPayload.KIND_SUN_FORM_VFX, player.getId(),
                player.getX(), player.getY() + 1.05, player.getZ(),
                player.getYRot(), BladeKinematics.ANIM_SUN_FLAME_DANCE, reach, 0.0F,
                step1Dur, tc, true, true
        );
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.05F, 1.05F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.10F, 1.02F);

        state.activeTasks.add(new BreathingManager.ActiveFormTask() {
            int tick = 0;
            boolean startedSecond = false;
            float segTime = 0.0F;
            Vec3 prevOrigin = player.position().add(0.0, 1.05, 0.0);
            float prevYaw = player.getYRot();
            final Map<Integer, Integer> hitCd = new HashMap<>();

            @Override
            public boolean tick(ServerPlayer p, ServerLevel lv, BreathingManager.PlayerBreathState st) {
                BreathingManager.decrementHitCooldowns(hitCd);
                Vec3 curOrigin = p.position().add(0.0, 1.05, 0.0);
                float curYaw = p.getYRot();

                if (!startedSecond && tick >= step1Dur) {
                    startedSecond = true;
                    segTime = 0.0F;
                    hitCd.clear(); // 第二记炽炎追劈独立刷新命中，打出干脆利落的二连暴击！
                    Vec3 fwd = BreathingManager.horizontalLook(p);
                    p.push(fwd.x * 0.62, 0.14, fwd.z * 0.62);
                    BreathingManager.syncEntityVelocity(p);

                    WaterVfxPayload.sendNear(
                            lv, WaterVfxPayload.KIND_SUN_FORM_VFX, p.getId(),
                            p.getX(), p.getY() + 1.05, p.getZ(),
                            p.getYRot(), BladeKinematics.ANIM_SUN_FLAME_DANCE, reach * 1.08F, 1.0F,
                            step2Dur, tc, true, true
                    );
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.15F, 0.92F);
                    lv.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.25F, 0.85F);
                    BladeWorldPhysics.processBladeSweep(p, lv, st, curOrigin, fwd, reach * 1.1F, -0.5, true, true);
                }

                int segDur = startedSecond ? step2Dur : step1Dur;
                float step = st.serverHitstopTicks > 0.0F ? 0.14F : 1.0F;
                float prevT = Mth.clamp(segTime / segDur, 0.0F, 1.0F);
                segTime += step;
                float curT = Mth.clamp(segTime / segDur, 0.0F, 1.0F);

                BreathingManager.runSweptBladeHits(
                        p, lv, st, BladeKinematics.ANIM_SUN_FLAME_DANCE, prevT, curT, startedSecond ? 1 : 0, tc, true,
                        prevOrigin, curOrigin, prevYaw, curYaw, hitCd, 12,
                        reach + 1.4, 0.72, startedSecond ? hit2Dmg : hit1Dmg,
                        startedSecond ? 0.96 : 0.48, startedSecond ? 0.36 : 0.16,
                        startedSecond ? 1.95F : 1.35F, startedSecond
                );

                prevOrigin = curOrigin;
                prevYaw = curYaw;
                tick++;
                return tick < totalDur;
            }
        });
    }
}
