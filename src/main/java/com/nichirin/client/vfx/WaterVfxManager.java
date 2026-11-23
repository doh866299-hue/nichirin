package com.nichirin.client.vfx;

import com.nichirin.client.ClientBreathState;
import com.nichirin.client.anim.SwordChoreography;
import com.nichirin.client.input.ComboInputController;
import com.nichirin.network.HitPhysicsPayload;
import com.nichirin.network.WaterVfxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 浮世绘水之呼吸 客户端 3D 特效与常态实时刀光带管理器：
 * - 每位持水之日轮刀的玩家实时维护连续刀刃轨迹带（常态自带浮世绘刀光，招式通过改变人/刀动作直接划出各种水浪形态）
 * - 发射类招式（生生流转/水车/流流舞/泷壶）严格绑定施法者本体 entityId，实现人、刀、水龙同步一体发射！
 */
public final class WaterVfxManager {
    private static final int MAX_DEBRIS = 240;
    private static final int MAX_UPHEAVALS = 220;
    private static final int MAX_ORBITING = 110;
    private static final int MAX_FOAM_SPRITES = 520;
    private static final int MAX_STREAKS = 480;

    static final Map<Integer, LiveBladeTrail> BLADE_TRAILS = new HashMap<>();
    static final List<FlyingBlockDebris> DEBRIS = new ArrayList<>();
    static final List<GroundUpheavalBlock> UPHEAVALS = new ArrayList<>();
    static final List<OrbitingBlockDebris> ORBITING_BLOCKS = new ArrayList<>();
    static final List<WaterWhirlpoolVfx> WHIRLPOOLS = new ArrayList<>();
    static final List<WaterfallPillarVfx> WATERFALLS = new ArrayList<>();
    static final List<WaterDragonVfx> DRAGONS = new ArrayList<>();
    static final List<SunDragonFinaleVfx> SUN_FINALES = new ArrayList<>();
    static final List<DeadCalmMirrorVfx> DEAD_CALMS = new ArrayList<>();
    static final List<WaterRiftSlashVfx> RIFT_SLASHES = new ArrayList<>();
    static final List<ChargeAuraVfx> CHARGE_AURAS = new ArrayList<>();
    static final List<UkiyoFoamSprite> FOAM_SPRITES = new ArrayList<>();
    static final List<VelocityStreakVfx> STREAKS = new ArrayList<>();
    static final List<ThreadSnapVfx> THREAD_SNAPS = new ArrayList<>();
    static final List<RippleRingVfx> RIPPLE_RINGS = new ArrayList<>();
    static final List<SunAfterimageVfx> AFTERIMAGES = new ArrayList<>();
    private static final List<DelayedCue> DELAYED_CUES = new ArrayList<>();

    // ==================== 鬼灭战斗直觉状态（隙之线 + 杀气感知 + 看破/断线视觉瞬闪） ====================
    private static final Map<Integer, Long> EXPOSED_TARGETS = new HashMap<>();
    private static final Map<Integer, Long> THREAD_COOLDOWNS = new HashMap<>();
    private static int openingThreadTargetId = -1;
    private static float openingThreadStrength = 0.0F;
    private static float prevOpeningThreadStrength = 0.0F;
    private static int forcedHighlightTargetId = -1;
    private static long forcedHighlightUntil = 0L;

    private static int dangerAlertTicks = 0;
    private static int prevDangerAlertTicks = 0;
    private static int dangerSoundCooldown = 0;

    private static int intuitionFlashTicks = 0;
    private static int prevIntuitionFlashTicks = 0;
    private static int intuitionFlashMax = 12;
    /** 1 = 斩断隙之线「斩」, 2 = 直觉看破「破」, 3 = 断首斩杀「一闪」 */
    private static int intuitionFlashType = 0;

    private static float shakeStrength = 0.0F;
    private static float shakeDecay = 0.76F;
    private static float shakeTime = 0.0F;

    /** 严格单调递增的客户端逻辑 tick 计数（结合 partialTick 构成 100% 连续的渲染时钟，杜绝任何 20Hz 抖动！） */
    private static long clientTickCounter = 0L;
    private static double lastGlobalRenderTime = 0.0;

    private static float fovDelta = 0.0F;
    private static float prevFovDelta = 0.0F;
    private static float fovTarget = 0.0F;

    private static float flashAlpha = 0.0F;
    private static float prevFlashAlpha = 0.0F;
    private static int flashRgb = 0xE8F8FF;

    private static float vignetteAlpha = 0.0F;
    private static float prevVignetteAlpha = 0.0F;

    private WaterVfxManager() {}

    public static void handlePayload(WaterVfxPayload p, IPayloadContext context) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        RandomSource rand = level.getRandom();
        boolean tc = p.isTotalConcentration();
        boolean enhanced = p.isEnhanced();
        boolean sun = p.isSunStyle();
        int eid = p.entityId();

        switch (p.kind()) {
            case WaterVfxPayload.KIND_WATER_SLASH -> {
                resetBladeTrail(eid);
                int dur = Math.max(12, p.duration());
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_SURFACE_SLASH, dur, 0, tc, sun);
                float radius = Math.max(2.4F, p.param1());
                float tilt = p.param2();
                float sweep = Math.max(120.0F, p.param3());
                // 起势 0..2 tick 收紧聚压 -> 第 3 tick 刀锋一闪先破空（视野完全清澈！）-> 第 5 tick 沿刀弧外缘轰然炸开巨浪与掀地！
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), -3.4F, 16.0);
                DELAYED_CUES.add(new DelayedCue(3, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() + 1.05 : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    float syaw = ent != null ? ent.getYRot() : p.yaw();
                    // 刀先出：先拉出高速锐利水针/火星线，不刷贴脸大贴片
                    spawnSlashStreaks(sx, sy, sz, syaw, radius * 0.95F, sweep, enhanced ? 24 : 12, sun, rand);
                    triggerFovKickAt(mc, sx, sy, sz, 6.2F, 16.0);
                }));
                DELAYED_CUES.add(new DelayedCue(5, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() + 1.05 : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    float syaw = ent != null ? ent.getYRot() : p.yaw();
                    // 特效后跟：慢 2 tick 沿刀弧外缘轰然澎湃炸开浮世绘浪花、火星与掀地！
                    spawnSlashUkiyoFoam(sx, sy, sz, syaw, radius * 1.06F, tilt, sweep,
                            enhanced ? (tc ? 34 : 26) : 14, tc, sun, rand);
                    spawnSlashStreaks(sx, sy, sz, syaw, radius * 1.05F, sweep,
                            enhanced ? (sun ? 32 : 24) : 10, sun, rand);
                    if (enhanced) {
                        spawnGroundUpheaval(level, sx, sy - 1.0, sz, 2.2F, radius * 0.95F, 0.38F, 12, rand);
                        triggerShakeAt(mc, sx, sy, sz, tc ? 0.44F : 0.32F, 18.0);
                        if (sun) {
                            triggerFlashAt(mc, sx, sy, sz, 0.12F, 0xFFE2A8, 18.0);
                        }
                    }
                }));
            }
            case WaterVfxPayload.KIND_WATER_WHEEL -> {
                resetBladeTrail(eid);
                boolean horizontal = p.param2() > 0.5F;
                SwordChoreography.startAnim(
                        eid,
                        horizontal ? SwordChoreography.ANIM_LATERAL_WHEEL : SwordChoreography.ANIM_WATER_WHEEL,
                        Math.max(16, p.duration()), 0, tc, sun
                );
                float radius = Math.max(2.4F, p.param1());
                // 0..1 tick 聚势 -> 第 2 tick 锐利刀轮起转 -> 第 4 tick 沿外环切线轰然甩出漫画水花与碎石！
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), -2.8F, 14.0);
                DELAYED_CUES.add(new DelayedCue(2, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() + 1.05 : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    spawnBurstStreaks(sx, sy + 0.15, sz, radius * 0.72, horizontal ? 28 : 22, 0.52, sun, rand);
                    triggerFovKickAt(mc, sx, sy, sz, 6.4F, 14.0);
                }));
                DELAYED_CUES.add(new DelayedCue(4, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() + 1.05 : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    spawnRingBurstUkiyoFoam(sx, sy, sz, radius * 0.62, radius * 1.05, horizontal ? 26 : 20, 0.34, 1.32F, sun, rand);
                    spawnBurstStreaks(sx, sy + 0.2, sz, radius * 0.85, horizontal ? 24 : 18, 0.56, sun, rand);
                    if (!horizontal) {
                        spawnGroundDebris(level, sx, sy - 1.0, sz, 12, 1.4, 2.8, 0.40, 0.56, 22, rand);
                    }
                    triggerShakeAt(mc, sx, sy, sz, sun ? 0.45F : 0.38F, 20.0);
                    if (sun) {
                        triggerFlashAt(mc, sx, sy, sz, 0.12F, 0xFFE0A0, 18.0);
                    }
                }));
            }
            case WaterVfxPayload.KIND_FLOWING_STEP -> {
                int step = Math.round(p.param3());
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_FLOWING_DANCE, sun ? 8 : 7, step, tc, sun);
                // 刀先掠过：先给轻快速度线，慢 2 tick 在你刚踏过的身后轨迹处炸开波涛/火舌尾流！
                spawnBurstStreaks(p.x(), p.y() + 0.9, p.z(), 1.5, sun ? 22 : 16, 0.48, sun, rand);
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), sun ? 5.2F : 4.2F, 12.0);
                double ox = p.x(), oy = p.y() + 0.85, oz = p.z();
                DELAYED_CUES.add(new DelayedCue(2, () -> {
                    spawnRingBurstUkiyoFoam(ox, oy, oz, 0.75, sun ? 2.3 : 1.9, sun ? 20 : 14, 0.32, sun ? 1.28F : 1.18F, sun, rand);
                    spawnBurstStreaks(ox, oy + 0.1, oz, 1.8, sun ? 20 : 14, 0.52, sun, rand);
                    if (sun && step >= 2) {
                        triggerShakeAt(mc, ox, oy, oz, 0.42F, 18.0);
                        triggerFlashAt(mc, ox, oy, oz, 0.14F, 0xFFE2AA, 18.0);
                    }
                }));
            }
            case WaterVfxPayload.KIND_WHIRLPOOL -> {
                int dur = Math.max(24, p.duration());
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_WHIRLPOOL, dur, 0, tc, sun);
                float radius = Math.max(3.6F, p.param1());
                float height = Math.max(5.8F, p.param2());
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), -3.6F, 16.0);
                // 第 3 tick 刀锋起旋 -> 第 5 tick 台风眼外围轰然拔地卷起巨型龙卷带与反重力石块（中心视野通透！）
                DELAYED_CUES.add(new DelayedCue(3, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    spawnBurstStreaks(sx, sy + 1.2, sz, radius * 0.85, 28, 0.54, sun, rand);
                    triggerFovKickAt(mc, sx, sy, sz, 6.5F, 18.0);
                }));
                DELAYED_CUES.add(new DelayedCue(5, () -> {
                    Entity ent = level.getEntity(eid);
                    double sx = ent != null ? ent.getX() : p.x();
                    double sy = ent != null ? ent.getY() : p.y();
                    double sz = ent != null ? ent.getZ() : p.z();
                    WHIRLPOOLS.add(new WaterWhirlpoolVfx(eid, sx, sy, sz, radius, height, Math.max(18, dur - 5), tc || enhanced, sun));
                    spawnOrbitingGroundBlocks(level, sx, sy, sz, 18, radius * 0.62F, radius * 1.02F, height * 0.72F, Math.max(18, dur - 5), rand);
                    spawnGroundUpheaval(level, sx, sy, sz, 2.0F, radius * 1.0F, 0.44F, 16, rand);
                    spawnWhirlpoolUkiyoFoam(sx, sy, sz, radius, height, 30, sun, rand);
                    spawnBurstStreaks(sx, sy + 1.4, sz, radius * 0.90, 42, 0.56, sun, rand);
                    triggerShakeAt(mc, sx, sy, sz, sun ? 0.62F : 0.54F, 24.0);
                    if (sun) {
                        triggerFlashAt(mc, sx, sy, sz, 0.14F, 0xFFDDA2, 22.0);
                    }
                }));
            }
            case WaterVfxPayload.KIND_WATERFALL_DIVE_START -> {
                if (p.param1() > 0.5F) {
                    // 思路 2 · 人刀龙一体腾空蓄刀俯冲！延长修长真龙跟踪，让神龙随剑士一起跃空蓄势、一起俯冲重劈！
                    SwordChoreography.startAnim(eid, SwordChoreography.ANIM_DRAGON_LEAP_CLEAVE,
                            Math.max(14, p.duration()), Math.round(p.param2()), tc, sun);
                    extendActiveDragonTracking(eid, 18);
                    spawnBurstStreaks(p.x(), p.y() + 1.0, p.z(), 1.8, 22, 0.44, sun, rand);
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), -5.8F, 22.0);
                    DELAYED_CUES.add(new DelayedCue(5, () ->
                            triggerFovKickAt(mc, p.x(), p.y(), p.z(), 8.8F, 24.0)));
                } else {
                    SwordChoreography.startAnim(eid, SwordChoreography.ANIM_WATERFALL_DIVE, Math.max(20, p.duration()), 0, tc, sun);
                    // 空中高举悬停顿帧：收紧视野聚势 -> 第 3 tick 爆坠弹开！
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), -4.2F, 16.0);
                    DELAYED_CUES.add(new DelayedCue(3, () ->
                            triggerFovKickAt(mc, p.x(), p.y(), p.z(), 7.8F, 18.0)));
                }
            }
            case WaterVfxPayload.KIND_WATERFALL_BASIN -> {
                // 捌之型·泷壶 原版重做：自上而下垂直瀑布重劈 + 符合直觉的「瀑布砸潭」贴地水压激浪冲击爆裂！（0 向上冲天柱！）
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_WATERFALL_CLEAVE, 14, 0, tc, sun);
                float radius = Math.max(4.0F, p.param1());
                float cascadeHeight = Math.max(4.5F, p.param2());
                int dur = Math.max(18, p.duration());
                double groundY = sampleGroundTopY(level, p.x(), p.y(), p.z());

                WATERFALLS.add(new WaterfallPillarVfx(p.x(), groundY, p.z(), radius, cascadeHeight, 0.0F, dur, tc, sun));

                // 垂直瀑布劈地瞬间的交叉斩痕 + 环形喷溅白沫浪花 + 克制微裂地皮（绝不满屏刷脏土块！）
                for (int i = 0; i < 4; i++) {
                    float ryaw = p.yaw() + (i - 1.5F) * 28.0F;
                    float rpitch = (i % 2 == 0 ? 55.0F : -45.0F) + (rand.nextFloat() - 0.5F) * 18.0F;
                    RIFT_SLASHES.add(new WaterRiftSlashVfx(
                            p.x() + (rand.nextDouble() - 0.5) * 1.4,
                            groundY + 1.0 + rand.nextDouble() * 1.8,
                            p.z() + (rand.nextDouble() - 0.5) * 1.4,
                            ryaw, rpitch, 2.8F + rand.nextFloat() * 1.4F, 12, true, sun
                    ));
                }
                spawnGroundUpheaval(level, p.x(), groundY, p.z(), 1.2F, radius * 0.65F, 0.45F, 13, rand);
                spawnGroundDebris(level, p.x(), groundY, p.z(), 8, 1.4, radius * 0.72, 0.44, 0.64, 18, rand);
                spawnRingBurstUkiyoFoam(p.x(), groundY + 0.9, p.z(), radius * 0.30, radius * 0.96, 38, 0.48, 1.60F, sun, rand);
                spawnBurstStreaks(p.x(), groundY + 1.0, p.z(), radius * 0.78, 58, 0.76, sun, rand);
                triggerShakeAt(mc, p.x(), groundY, p.z(), 0.85F, 30.0);
                triggerFovKickAt(mc, p.x(), groundY, p.z(), 8.8F, 24.0);
                triggerFlashAt(mc, p.x(), groundY, p.z(), 0.16F, sun ? 0xFFE0A0 : 0xE6F8FF, 26.0);
            }
            case WaterVfxPayload.KIND_CHARGE_AURA -> {
                float radius = Math.max(3.0F, p.param1());
                int mode = Math.round(p.param2());
                int dur = Math.max(14, p.duration());
                SwordChoreography.setCharging(eid, mode, dur);
                CHARGE_AURAS.add(new ChargeAuraVfx(eid, p.x(), p.y(), p.z(), radius, mode, dur, tc, sun));
                if (mode == 1) {
                    spawnOrbitingGroundBlocks(level, p.x(), p.y(), p.z(), 16, 2.2F, radius, 3.6F, dur, rand);
                    spawnRingBurstUkiyoFoam(p.x(), p.y() + 0.8, p.z(), 1.8, radius * 0.80, 16, 0.22, 1.12F, sun, rand);
                    spawnBurstStreaks(p.x(), p.y() + 0.7, p.z(), radius * 0.60, 26, 0.36, sun, rand);
                }
            }
            case WaterVfxPayload.KIND_WATER_DRAGON -> {
                resetBladeTrail(eid);
                int tier = Mth.clamp(Math.round(p.param2()), 1, 4);
                int dur = Math.max(24, p.duration());
                int rushTicks = Math.max(16, dur - 16);
                double nowT = peekRenderTime();
                DRAGONS.add(new WaterDragonVfx(eid, p.x(), p.y(), p.z(), p.yaw(), p.param3(), tier, rushTicks, dur, nowT, tc, sun));
                // 起步推进水波刷在玩家身后 -1.5 格（正前方视野 100% 清澈！）
                Vec3 fDir = Vec3.directionFromRotation(0.0F, p.yaw()).normalize();
                double bx = p.x() - fDir.x * 1.5, by = p.y() + 0.2, bz = p.z() - fDir.z * 1.5;
                spawnRingBurstUkiyoFoam(bx, by, bz, 1.0, 2.4, 18 + tier * 4, 0.36, 1.28F, sun, rand);
                spawnBurstStreaks(bx, by + 0.2, bz, 1.8, 30 + tier * 8, 0.60, sun, rand);
                triggerShakeAt(mc, p.x(), p.y(), p.z(), 0.38F + tier * 0.08F, 30.0);
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), 5.0F + tier * 1.0F, 22.0);
                triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.10F + tier * 0.02F, sun ? 0xFFE2A6 : 0xE6F8FF, 24.0);
            }
            case WaterVfxPayload.KIND_DEAD_CALM_DOMAIN -> {
                int dur = Math.max(30, p.duration());
                float radius = Math.max(5.5F, p.param1());
                if (sun) {
                    SwordChoreography.startAnim(eid, SwordChoreography.ANIM_DEAD_CALM, 10, 0, tc, true);
                    DEAD_CALMS.add(new DeadCalmMirrorVfx(eid, p.x(), p.y(), p.z(), radius, dur, tc, true));
                    spawnBurstUkiyoFoam(p.x(), p.y() + 1.0, p.z(), 2.1, 26, 0.30, 1.38F, true, rand);
                    spawnBurstStreaks(p.x(), p.y() + 1.0, p.z(), 1.8, 40, 0.52, true, rand);
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), 7.5F, 16.0);
                    triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.16F, 0xFFE2A8, 18.0);
                } else {
                    SwordChoreography.startAnim(eid, SwordChoreography.ANIM_DEAD_CALM, dur, 0, tc, false);
                    DEAD_CALMS.add(new DeadCalmMirrorVfx(eid, p.x(), p.y(), p.z(), radius, dur, tc, false));
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), 5.0F, 14.0);
                    triggerVignetteAt(mc, p.x(), p.y(), p.z(), 0.34F, 18.0);
                }
            }
            case WaterVfxPayload.KIND_DEAD_CALM_COUNTER -> {
                SwordChoreography.triggerParrySnap(eid, sun);
                float scale = Math.max(1.0F, p.param1());
                int count = Mth.clamp(Math.round(p.param2()), 3, 12);
                int dur = Math.max(10, p.duration());
                for (int i = 0; i < count; i++) {
                    double ox = (rand.nextDouble() - 0.5) * scale * 0.85;
                    double oy = (rand.nextDouble() - 0.5) * scale * 0.75;
                    double oz = (rand.nextDouble() - 0.5) * scale * 0.85;
                    float ryaw = rand.nextFloat() * 360.0F;
                    float rpitch = (rand.nextFloat() - 0.5F) * 145.0F;
                    float len = scale * (1.2F + rand.nextFloat() * 0.9F);
                    RIFT_SLASHES.add(new WaterRiftSlashVfx(
                            p.x() + ox, p.y() + oy, p.z() + oz, ryaw, rpitch, len, dur + rand.nextInt(4), tc || enhanced, sun
                    ));
                }
                spawnBurstStreaks(p.x(), p.y(), p.z(), scale * 0.45, enhanced ? 34 : 16, 0.55, sun, rand);
                for (int i = 0; i < (enhanced ? 14 : 6) && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; i++) {
                    int tile = (i % 2 == 0) ? 2 : 3;
                    FOAM_SPRITES.add(new UkiyoFoamSprite(
                            p.x(), p.y(), p.z(),
                            (rand.nextDouble() - 0.5) * 0.32, (rand.nextDouble() - 0.25) * 0.28, (rand.nextDouble() - 0.5) * 0.32,
                            tile, scale * (0.65F + rand.nextFloat() * 0.55F),
                            rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 20.0F,
                            10 + rand.nextInt(6), sun
                    ));
                }
                if (enhanced) {
                    triggerShakeAt(mc, p.x(), p.y(), p.z(), 0.52F, 20.0);
                    triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.20F, sun ? 0xFFE4AE : 0xEFFBFF, 20.0);
                }
            }
            case WaterVfxPayload.KIND_BREATH_PULSE -> {
                float radius = Math.max(1.8F, p.param1());
                boolean burst = p.param2() > 0.5F;
                CHARGE_AURAS.add(new ChargeAuraVfx(eid, p.x(), p.y(), p.z(), radius, burst ? 3 : 4, Math.max(8, p.duration()), tc, sun));
                if (burst) {
                    spawnBurstUkiyoFoam(p.x(), p.y() + 0.7, p.z(), radius * 0.5, 22, 0.26, 1.18F, sun, rand);
                    spawnBurstStreaks(p.x(), p.y() + 0.8, p.z(), radius * 0.5, 40, 0.56, sun, rand);
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), 6.0F, 10.0);
                    triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.16F, sun ? 0xFFDBA0 : 0xE6F8FF, 14.0);
                }
            }
            case WaterVfxPayload.KIND_SUN_DRAGON_FINALE -> {
                // 拾之型·生生流转 高势能收尾龙尾斩 + 符合直觉的高压水爆！（随叠加圈数 1..4 递增规模，0 柱子、0 地面法阵、0 脏土块！）
                int stage = Mth.clamp(Math.round(p.param3()), 1, 4);
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_FINALE_IAI_FLASH, 13, stage, tc, sun);
                if (mc.player != null && mc.player.getId() == eid) {
                    ClientBreathState.clearFluxSpin();
                }
                float radius = Math.max(4.4F, p.param1());
                int dur = Math.max(28, p.duration());
                double groundY = sampleGroundTopY(level, p.x(), p.y(), p.z());

                // 一路随旋转成长的水龙瞬间并入收尾龙尾斩弧，无缝穿斩而出！
                lockActiveDragonFinale(eid, new Vec3(p.x(), groundY + 1.15, p.z()), 1.2F, peekRenderTime());
                SUN_FINALES.add(new SunDragonFinaleVfx(p.x(), groundY, p.z(), p.yaw(), radius, stage, dur, tc, sun));

                int slashN = 3 + stage * 2;
                for (int i = 0; i < slashN; i++) {
                    double ang = (Math.PI * 2.0 * i) / slashN + (rand.nextDouble() - 0.5) * 0.28;
                    double r = radius * (0.20 + rand.nextDouble() * 0.42);
                    RIFT_SLASHES.add(new WaterRiftSlashVfx(
                            p.x() + Math.cos(ang) * r,
                            groundY + 0.85 + rand.nextDouble() * 2.0,
                            p.z() + Math.sin(ang) * r,
                            p.yaw() + (rand.nextFloat() - 0.5F) * 140.0F,
                            (rand.nextFloat() - 0.5F) * 95.0F,
                            2.4F + stage * 0.55F + rand.nextFloat() * 1.2F, 12 + rand.nextInt(4), true, sun
                    ));
                }
                // 符合直觉的高压水爆：大量清澈浮世绘浪花 + 高速水针向外飞炸，仅在满 3~4 圈时微振内圈地皮！
                if (stage >= 3) {
                    spawnGroundUpheaval(level, p.x(), groundY, p.z(), 1.4F, radius * 0.52F, 0.34F, 11, rand);
                }
                spawnRingBurstUkiyoFoam(p.x(), groundY + 1.0, p.z(), radius * 0.35, radius * 0.98,
                        18 + stage * 8, 0.42 + stage * 0.05, 1.32F + stage * 0.14F, sun, rand);
                spawnBurstStreaks(p.x(), groundY + 1.0, p.z(), radius * 0.78, 36 + stage * 14, 0.66 + stage * 0.06, sun, rand);
                triggerShakeAt(mc, p.x(), groundY, p.z(), 0.48F + stage * 0.12F, 30.0);
                triggerFovKickAt(mc, p.x(), groundY, p.z(), 6.0F + stage * 1.4F, 26.0);
                triggerFlashAt(mc, p.x(), groundY, p.z(), 0.11F + stage * 0.025F, sun ? 0xFFE2A4 : 0xE6FAFF, 28.0);
            }
            case WaterVfxPayload.KIND_DRAGON_WAKE_IMPACT -> {
                // 方案 B 巨龙顺轨尾流二段碾压：干净利落的浮世绘浪花/烈焰与犀利斩痕（大幅收敛脏泥块！）
                float radius = Math.max(2.6F, p.param1());
                float strength = Math.max(0.8F, p.param2());
                int hits = Math.round(p.param3());

                spawnRingBurstUkiyoFoam(p.x(), p.y(), p.z(), 0.9, radius * 0.85,
                        hits > 0 ? 16 : 8, 0.38, (hits > 0 ? 1.48F : 1.25F) * strength * 0.88F, sun, rand);
                spawnBurstStreaks(p.x(), p.y() + 0.2, p.z(), radius * 0.72, hits > 0 ? 28 : 14, 0.60, sun, rand);
                if (hits > 0) {
                    for (int i = 0; i < Math.min(3, hits + 1); i++) {
                        RIFT_SLASHES.add(new WaterRiftSlashVfx(
                                p.x() + (rand.nextDouble() - 0.5) * 1.8,
                                p.y() + (rand.nextDouble() - 0.3) * 1.4,
                                p.z() + (rand.nextDouble() - 0.5) * 1.8,
                                p.yaw() + (rand.nextFloat() - 0.5F) * 130.0F,
                                (rand.nextFloat() - 0.5F) * 110.0F,
                                2.4F + rand.nextFloat() * 1.4F, 11, true, sun
                        ));
                    }
                    triggerShakeAt(mc, p.x(), p.y(), p.z(), 0.42F, 26.0);
                    triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.09F, sun ? 0xFFE3A4 : 0xE6FAFF, 24.0);
                }
            }
            case WaterVfxPayload.KIND_HIT_IMPACT -> {
                // 斩击命中实感回馈：平滑顿刀卡肉 + 定向十字斩痕 + 方向火星/浪花 + 隙之线物理绷断！
                float weight = Math.max(0.8F, p.param1());
                int targetId = Math.round(p.param2());
                int mask = Math.round(p.param3());
                boolean threadBreak = (mask & 1) != 0;
                boolean lethal = (mask & 2) != 0;
                boolean heavy = (mask & 4) != 0;
                boolean isLocalAttacker = mc.player != null && mc.player.getId() == eid;

                // 1. 顿刀卡肉（刀刃咬合减速至 14% + 刀身微震，144Hz 零跳帧！）
                float stopTicks = lethal ? 3.8F : (threadBreak ? 3.2F : (heavy ? 2.4F : 1.7F));
                SwordChoreography.triggerHitstop(eid, stopTicks, Mth.clamp(weight * 0.65F, 0.70F, 1.55F));

                // 2. 敌人中刀处爆开极致锐利的赛璐璐斩痕（破绽断线/斩杀时呈华丽交叉十字闪斩！）
                int slashCount = (threadBreak || lethal) ? 4 : (heavy ? 2 : 1);
                for (int i = 0; i < slashCount; i++) {
                    float ryaw = p.yaw() + (i == 0 ? 35.0F : (i == 1 ? -45.0F : (rand.nextFloat() - 0.5F) * 160.0F));
                    float rpitch = (i % 2 == 0 ? 32.0F : -32.0F) + (rand.nextFloat() - 0.5F) * 28.0F;
                    float len = (threadBreak || lethal ? 2.15F : (heavy ? 1.55F : 1.10F)) + rand.nextFloat() * 0.55F;
                    RIFT_SLASHES.add(new WaterRiftSlashVfx(
                            p.x(), p.y(), p.z(), ryaw, rpitch, len,
                            threadBreak || lethal ? 12 : 8, heavy || threadBreak || lethal, sun
                    ));
                }

                // 3. 方向喷溅火星/水针与漫画受击炸点
                int streakN = threadBreak || lethal ? 26 : (heavy ? 16 : 9);
                spawnBurstStreaks(p.x(), p.y(), p.z(), 0.45, streakN, threadBreak ? 0.64 : 0.44, sun, rand);
                int foamN = threadBreak || lethal ? 10 : (heavy ? 5 : 2);
                spawnBurstUkiyoFoam(p.x(), p.y(), p.z(), 0.50, foamN, 0.24, threadBreak ? 1.18F : 0.82F, sun, rand);

                // 4. 隙之线物理绷断演出与破绽连锁同步
                if (threadBreak) {
                    if (targetId > 0) {
                        THREAD_COOLDOWNS.put(targetId, clientTickCounter + 28L);
                        EXPOSED_TARGETS.remove(targetId);
                        if (forcedHighlightTargetId == targetId) forcedHighlightTargetId = -1;
                    }
                    Entity atkEnt = level.getEntity(eid);
                    Vec3 startPt = atkEnt != null
                            ? atkEnt.position().add(0.0, 1.15, 0.0).add(atkEnt.getLookAngle().scale(0.95))
                            : new Vec3(p.x(), p.y(), p.z());
                    THREAD_SNAPS.add(new ThreadSnapVfx(startPt, new Vec3(p.x(), p.y(), p.z()), 11, sun));
                    if (isLocalAttacker) {
                        triggerIntuitionFlash(lethal ? 3 : 1, 12);
                        triggerFovKickAt(mc, p.x(), p.y(), p.z(), lethal ? 5.6F : 4.2F, 22.0);
                        triggerFlashAt(mc, p.x(), p.y(), p.z(), lethal ? 0.22F : 0.16F, sun ? 0xFFF0C4 : 0xEBFCFF, 24.0);
                        triggerShakeAt(mc, p.x(), p.y(), p.z(), lethal ? 0.56F : 0.44F, 22.0);
                    }
                } else {
                    if (isLocalAttacker && !lethal && targetId > 0 && weight >= 1.1F) {
                        // 剑型命中后立刻在客户端标明该目标已露受招破绽，「隙之线」瞬间缠上其颈部！
                        EXPOSED_TARGETS.put(targetId, clientTickCounter + 38L);
                    }
                    if (isLocalAttacker) {
                        if (lethal) {
                            triggerIntuitionFlash(3, 10);
                            triggerFovKickAt(mc, p.x(), p.y(), p.z(), 3.8F, 18.0);
                            triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.14F, sun ? 0xFFEBC0 : 0xE8FAFF, 18.0);
                            triggerShakeAt(mc, p.x(), p.y(), p.z(), 0.42F, 18.0);
                        } else {
                            triggerShakeAt(mc, p.x(), p.y(), p.z(), heavy ? 0.30F : 0.18F, 16.0);
                            if (heavy) triggerFovKickAt(mc, p.x(), p.y(), p.z(), 2.2F, 16.0);
                        }
                    }
                }
            }
            case WaterVfxPayload.KIND_INTUITION_PULSE -> {
                // 战斗直觉「看破！」：强开目标高光「隙之线」+ 瞬闪回响！
                int targetId = Math.round(p.param1());
                int pulseType = Math.round(p.param2());
                boolean isLocal = mc.player != null && mc.player.getId() == eid;
                if (pulseType == 1) {
                    if (targetId > 0) {
                        EXPOSED_TARGETS.put(targetId, clientTickCounter + 70L);
                        THREAD_COOLDOWNS.remove(targetId);
                        forcedHighlightTargetId = targetId;
                        forcedHighlightUntil = clientTickCounter + 70L;
                    }
                    spawnBurstStreaks(p.x(), p.y(), p.z(), 0.85, 22, 0.48, sun, rand);
                    if (isLocal) {
                        triggerIntuitionFlash(2, 13);
                        triggerFovKickAt(mc, p.x(), p.y(), p.z(), 4.5F, 24.0);
                        triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.16F, sun ? 0xFFEEC2 : 0xE6FAFF, 24.0);
                        triggerVignetteAt(mc, p.x(), p.y(), p.z(), 0.24F, 24.0);
                    }
                }
            }
            case WaterVfxPayload.KIND_RIPPLE_THRUST -> {
                // 柒之型·雫波纹突刺 & 全招式 3D 定向同心水波纹冲击环！
                float maxR = Math.max(1.4F, p.param1());
                float pitchDeg = p.param2();
                int mode = Math.round(p.param3()); // 0=垂直突刺波纹, 1=水平贴地水压波纹, 2=柔和慈雨波纹, 3=曲突刺起手
                int dur = Math.max(10, p.duration());
                if (enhanced && (mode == 0 || mode == 3)) {
                    resetBladeTrail(eid);
                    SwordChoreography.startAnim(eid, SwordChoreography.ANIM_DROP_RIPPLE_THRUST, dur, mode == 3 ? 1 : 0, tc, sun);
                    triggerFovKickAt(mc, p.x(), p.y(), p.z(), 7.4F, 18.0);
                }
                int renderMode = (mode == 3) ? 0 : mode;
                RIPPLE_RINGS.add(new RippleRingVfx(p.x(), p.y(), p.z(), p.yaw(), pitchDeg, maxR, renderMode, dur, sun));
                if (renderMode == 0) {
                    // 雫波纹突刺命中/尖端：清澈穿刺水针与精致飞沫！
                    Vec3 fwd = Vec3.directionFromRotation(pitchDeg, p.yaw()).normalize();
                    for (int i = 0; i < 16 && STREAKS.size() < MAX_STREAKS; i++) {
                        double sp = 0.35 + rand.nextDouble() * 0.55;
                        STREAKS.add(new VelocityStreakVfx(
                                p.x(), p.y(), p.z(),
                                fwd.x * sp + (rand.nextDouble() - 0.5) * 0.32,
                                fwd.y * sp + (rand.nextDouble() - 0.35) * 0.26,
                                fwd.z * sp + (rand.nextDouble() - 0.5) * 0.32,
                                1.35F + rand.nextFloat() * 0.85F, 0.024F, 11 + rand.nextInt(6), sun, i % 2 == 0
                        ));
                    }
                    spawnBurstUkiyoFoam(p.x(), p.y(), p.z(), 0.75, 8, 0.24, 0.96F, sun, rand);
                } else if (renderMode == 1) {
                    spawnRingBurstUkiyoFoam(p.x(), p.y() + 0.35, p.z(), maxR * 0.28, maxR * 0.88, 16, 0.32, 1.20F, sun, rand);
                    spawnBurstStreaks(p.x(), p.y() + 0.4, p.z(), maxR * 0.55, 22, 0.52, sun, rand);
                } else if (renderMode == 2) {
                    // 慈雨：轻盈微降的银青水雨细丝！
                    for (int i = 0; i < 14 && STREAKS.size() < MAX_STREAKS; i++) {
                        STREAKS.add(new VelocityStreakVfx(
                                p.x() + (rand.nextDouble() - 0.5) * maxR * 1.4,
                                p.y() + 1.6 + rand.nextDouble() * 1.8,
                                p.z() + (rand.nextDouble() - 0.5) * maxR * 1.4,
                                (rand.nextDouble() - 0.5) * 0.04, -0.22 - rand.nextDouble() * 0.14, (rand.nextDouble() - 0.5) * 0.04,
                                1.45F, 0.018F, 15 + rand.nextInt(8), false, true
                        ));
                    }
                }
            }
            case WaterVfxPayload.KIND_STRIKING_TIDE -> {
                // 肆之型·打潮：连绵四拍海潮重斩！
                int step = Math.round(p.param2());
                boolean isCrash = p.param3() > 0.5F;
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_STRIKING_TIDE, Math.max(6, p.duration()), step, tc, sun);
                float radius = Math.max(3.6F, p.param1());
                spawnSlashStreaks(p.x(), p.y(), p.z(), p.yaw(), radius * 0.92F, 185.0F, isCrash ? 26 : 16, sun, rand);
                double ox = p.x(), oy = p.y(), oz = p.z();
                float syaw = p.yaw();
                DELAYED_CUES.add(new DelayedCue(2, () -> {
                    spawnSlashUkiyoFoam(ox, oy, oz, syaw, radius, (step % 2 == 0) ? 24.0F : -24.0F, 180.0F,
                            isCrash ? 28 : 14, tc, sun, rand);
                    if (isCrash) {
                        triggerShakeAt(mc, ox, oy, oz, 0.56F, 22.0);
                        triggerFovKickAt(mc, ox, oy, oz, 6.2F, 20.0);
                    }
                }));
            }
            case WaterVfxPayload.KIND_BLESSED_RAIN -> {
                // 伍之型·乾天的慈雨：温柔轻盈高弧斩 + 漫天细密银青甘霖雨幕！
                resetBladeTrail(eid);
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_BLESSED_RAIN, Math.max(14, p.duration()), 0, tc, false);
                float radius = Math.max(4.0F, p.param1());
                RIPPLE_RINGS.add(new RippleRingVfx(p.x(), p.y() - 0.4, p.z(), p.yaw(), 0.0F, radius * 0.90F, 2, 22, false));
                for (int i = 0; i < 36 && STREAKS.size() < MAX_STREAKS; i++) {
                    double ang = rand.nextDouble() * Math.PI * 2.0;
                    double r = rand.nextDouble() * radius * 1.15;
                    STREAKS.add(new VelocityStreakVfx(
                            p.x() + Math.cos(ang) * r,
                            p.y() + 2.2 + rand.nextDouble() * 3.0,
                            p.z() + Math.sin(ang) * r,
                            (rand.nextDouble() - 0.5) * 0.03, -0.24 - rand.nextDouble() * 0.16, (rand.nextDouble() - 0.5) * 0.03,
                            1.55F, 0.016F, 16 + rand.nextInt(10), false, true
                    ));
                }
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), -2.8F, 16.0);
                triggerVignetteAt(mc, p.x(), p.y(), p.z(), 0.18F, 18.0);
            }
            case WaterVfxPayload.KIND_SPLASH_STEP -> {
                // 玖之型·水流飞沫·乱：脚下瞬间点水飞沫波纹 + 轻快掠身斩！
                int parity = Math.round(p.param3());
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_SPLASH_FLOW, Math.max(6, p.duration()), parity, tc, false);
                RIPPLE_RINGS.add(new RippleRingVfx(p.x(), p.y() + 0.04, p.z(), p.yaw(), 0.0F, 2.25F, 1, 12, false));
                spawnRingBurstUkiyoFoam(p.x(), p.y() + 0.25, p.z(), 0.35, 1.45, 10, 0.28, 0.92F, false, rand);
                spawnBurstStreaks(p.x(), p.y() + 0.45, p.z(), 1.15, 18, 0.46, false, rand);
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), 5.2F, 14.0);
            }
            case WaterVfxPayload.KIND_DRAGON_SPIN_STAGE -> {
                // 拾之型·生生流转 旋转叠势阶段推进（1..4 圈）：转速、龙身长度与威压逐圈攀升！
                int stage = Mth.clamp(Math.round(p.param1()), 1, 4);
                int cycleLen = Math.max(5, Math.round(p.param2()));
                SwordChoreography.startAnim(eid, SwordChoreography.ANIM_CONSTANT_FLUX, cycleLen + 1, stage, tc, sun);
                upgradeActiveDragonStage(eid, stage, cycleLen + 14);
                if (mc.player != null && mc.player.getId() == eid) {
                    ClientBreathState.setFluxSpinStage(stage, cycleLen + 8);
                }
                spawnBurstStreaks(p.x(), p.y(), p.z(), 1.8 + stage * 0.3, 16 + stage * 6, 0.48 + stage * 0.06, sun, rand);
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), 3.2F + stage * 1.4F, 22.0);
                if (stage >= 2) {
                    triggerShakeAt(mc, p.x(), p.y(), p.z(), 0.26F + stage * 0.08F, 24.0);
                }
            }
            case WaterVfxPayload.KIND_SUN_AFTERIMAGE -> {
                // 日·幻日虹：原地留下炽热虹光残像剪影 + 虹彩火星扭曲热浪！
                int dur = Math.max(16, p.duration());
                float evadeYaw = p.param1();
                float scale = Math.max(0.85F, p.param2());
                AFTERIMAGES.add(new SunAfterimageVfx(p.x(), p.y(), p.z(), p.yaw(), evadeYaw, scale, dur));
                spawnBurstStreaks(p.x(), p.y() + 1.0, p.z(), 1.25, 28, 0.46, true, rand);
                spawnRingBurstUkiyoFoam(p.x(), p.y() + 0.9, p.z(), 0.45, 1.85, 16, 0.28, 1.15F, true, rand);
                triggerFovKickAt(mc, p.x(), p.y(), p.z(), 7.2F, 18.0);
                triggerFlashAt(mc, p.x(), p.y(), p.z(), 0.14F, 0xFFE4B4, 18.0);
            }
            case WaterVfxPayload.KIND_SUN_FORM_VFX -> {
                // 日之呼吸 · 火之神神乐 专属 7 套剑舞演出（17..23）！
                int animType = Math.round(p.param1());
                float radius = Math.max(3.2F, p.param2());
                int subStep = Math.round(p.param3());
                int dur = Math.max(6, p.duration());
                // 日晕之龙连续游龙穿斩不重置前一段刀光带，让 4 段火龙斩弧在空中连成狂舞炎阵！
                if (animType != SwordChoreography.ANIM_SUN_HALO_DRAGON_DASH || subStep == 0) {
                    resetBladeTrail(eid);
                }
                SwordChoreography.startAnim(eid, animType, dur, subStep, tc, true);

                double sx = p.x(), sy = p.y(), sz = p.z();
                float syaw = p.yaw();

                switch (animType) {
                    case SwordChoreography.ANIM_SUN_CLEAR_BLUE_SKY -> {
                        // 碧罗之天：升天大炎轮火星喷涌 + 上挑热浪
                        spawnBurstStreaks(sx, sy + 0.6, sz, radius * 0.72, 34, 0.62, true, rand);
                        DELAYED_CUES.add(new DelayedCue(3, () -> {
                            spawnRingBurstUkiyoFoam(sx, sy + 0.9, sz, 1.2, radius * 0.92, 24, 0.36, 1.38F, true, rand);
                            triggerShakeAt(mc, sx, sy, sz, 0.48F, 20.0);
                            triggerFlashAt(mc, sx, sy, sz, 0.13F, 0xFFE0A0, 20.0);
                        }));
                        triggerFovKickAt(mc, sx, sy, sz, 6.4F, 18.0);
                    }
                    case SwordChoreography.ANIM_SUN_RAGING_SUN -> {
                        // 烈日红镜：左右「∞」字双弧两拍爆发！
                        spawnSlashStreaks(sx, sy, sz, syaw, radius * 0.95F, 240.0F, 24, true, rand);
                        DELAYED_CUES.add(new DelayedCue(3, () ->
                                spawnSlashUkiyoFoam(sx, sy, sz, syaw, radius, 18.0F, 240.0F, 20, tc, true, rand)));
                        DELAYED_CUES.add(new DelayedCue(8, () -> {
                            Entity ent = level.getEntity(eid);
                            double ex = ent != null ? ent.getX() : sx;
                            double ey = ent != null ? ent.getY() + 1.05 : sy;
                            double ez = ent != null ? ent.getZ() : sz;
                            float eyaw = ent != null ? ent.getYRot() : syaw;
                            spawnSlashStreaks(ex, ey, ez, eyaw, radius, 250.0F, 26, true, rand);
                            spawnSlashUkiyoFoam(ex, ey, ez, eyaw, radius * 1.05F, -18.0F, 250.0F, 22, tc, true, rand);
                            triggerShakeAt(mc, ex, ey, ez, 0.42F, 20.0);
                        }));
                        triggerFovKickAt(mc, sx, sy, sz, 5.2F, 18.0);
                    }
                    case SwordChoreography.ANIM_SUN_SETTING_SUN -> {
                        // 斜阳转身：半空倒立聚势 -> 第 4 tick 赤红横斩爆发
                        triggerFovKickAt(mc, sx, sy, sz, -4.2F, 18.0);
                        DELAYED_CUES.add(new DelayedCue(4, () -> {
                            Entity ent = level.getEntity(eid);
                            double ex = ent != null ? ent.getX() : sx;
                            double ey = ent != null ? ent.getY() + 1.1 : sy;
                            double ez = ent != null ? ent.getZ() : sz;
                            float eyaw = ent != null ? ent.getYRot() : syaw;
                            spawnSlashStreaks(ex, ey, ez, eyaw, radius * 1.05F, 270.0F, 34, true, rand);
                            spawnSlashUkiyoFoam(ex, ey, ez, eyaw, radius * 1.06F, -12.0F, 270.0F, 28, tc, true, rand);
                            triggerFovKickAt(mc, ex, ey, ez, 7.2F, 20.0);
                            triggerShakeAt(mc, ex, ey, ez, 0.56F, 22.0);
                            triggerFlashAt(mc, ex, ey, ez, 0.15F, 0xFFDC98, 20.0);
                        }));
                    }
                    case SwordChoreography.ANIM_SUN_HEAT_HAZE -> {
                        // 飞轮阳炎：7.4 格超远阳炎热浪余光 + 扭曲炽白火星雨
                        spawnSlashStreaks(sx, sy, sz, syaw, radius, 230.0F, 38, true, rand);
                        DELAYED_CUES.add(new DelayedCue(3, () -> {
                            spawnSlashUkiyoFoam(sx, sy, sz, syaw, radius * 0.96F, 4.0F, 230.0F, 30, tc, true, rand);
                            for (int i = 0; i < 3; i++) {
                                float yawOff = syaw + (i - 1) * 38.0F;
                                Vec3 dir = Vec3.directionFromRotation(0.0F, yawOff).normalize();
                                RIFT_SLASHES.add(new WaterRiftSlashVfx(
                                        sx + dir.x * (radius * 0.72), sy + 0.15, sz + dir.z * (radius * 0.72),
                                        yawOff, (rand.nextFloat() - 0.5F) * 28.0F, 2.8F, 10, true, true
                                ));
                            }
                            triggerShakeAt(mc, sx, sy, sz, 0.44F, 22.0);
                            triggerFlashAt(mc, sx, sy, sz, 0.14F, 0xFFF0C2, 22.0);
                        }));
                        triggerFovKickAt(mc, sx, sy, sz, 5.8F, 20.0);
                    }
                    case SwordChoreography.ANIM_SUN_FLAME_DANCE -> {
                        // 炎舞二连击：第 2 刀大幅加强震屏与炽焰炸裂！
                        boolean second = (subStep & 1) != 0;
                        spawnSlashStreaks(sx, sy, sz, syaw, radius, 250.0F, second ? 38 : 22, true, rand);
                        DELAYED_CUES.add(new DelayedCue(2, () -> {
                            spawnSlashUkiyoFoam(sx, sy, sz, syaw, radius * (second ? 1.10F : 0.95F),
                                    second ? -26.0F : 24.0F, 255.0F, second ? 34 : 20, tc, true, rand);
                            if (second) {
                                triggerShakeAt(mc, sx, sy, sz, 0.62F, 24.0);
                                triggerFovKickAt(mc, sx, sy, sz, 7.6F, 22.0);
                                triggerFlashAt(mc, sx, sy, sz, 0.17F, 0xFFDF9E, 22.0);
                            } else {
                                triggerShakeAt(mc, sx, sy, sz, 0.34F, 18.0);
                                triggerFovKickAt(mc, sx, sy, sz, 4.4F, 18.0);
                            }
                        }));
                    }
                    case SwordChoreography.ANIM_SUN_HALO_DRAGON_DASH -> {
                        // 日晕之龙·头舞：每段游龙高速折返持续延长 3D 太阳神龙随身狂舞轨迹！
                        extendActiveDragonTracking(eid, 12);
                        spawnSlashStreaks(sx, sy, sz, syaw, radius * 0.95F, 265.0F, 28, true, rand);
                        spawnRingBurstUkiyoFoam(sx, sy, sz, 1.0, radius * 0.85, 18, 0.42, 1.32F, true, rand);
                        triggerFovKickAt(mc, sx, sy, sz, 5.6F + subStep * 0.9F, 22.0);
                        triggerShakeAt(mc, sx, sy, sz, 0.36F + subStep * 0.06F, 24.0);
                    }
                    case SwordChoreography.ANIM_SUN_DANCING_FLASH -> {
                        // 圆舞一闪：雷步金白电光 + 太阳烈焰双色瞬闪爆发！
                        for (int i = 0; i < 24 && STREAKS.size() < MAX_STREAKS; i++) {
                            double ang = rand.nextDouble() * Math.PI * 2.0;
                            double sp = 0.48 + rand.nextDouble() * 0.62;
                            STREAKS.add(new VelocityStreakVfx(
                                    sx, sy + (rand.nextDouble() - 0.3) * 0.8, sz,
                                    Math.cos(ang) * sp, 0.08 + rand.nextDouble() * 0.28, Math.sin(ang) * sp,
                                    1.65F, 0.028F, 11 + rand.nextInt(6), true, true
                            ));
                        }
                        spawnSlashUkiyoFoam(sx, sy, sz, syaw, radius, 4.0F, 275.0F, 30, tc, true, rand);
                        triggerFovKickAt(mc, sx, sy, sz, 9.2F, 22.0);
                        triggerShakeAt(mc, sx, sy, sz, 0.54F, 22.0);
                        triggerFlashAt(mc, sx, sy, sz, 0.19F, 0xFFF4C8, 22.0);
                    }
                    default -> {}
                }
            }
            default -> {}
        }
    }

    public static void onLocalChargeStart(int mode) {
        if (mode == 1) fovTarget = -8.5F;
        else if (mode == 2) fovTarget = -4.5F;
    }

    /**
     * 精确 3D 刀锋切线命中反馈（由 HitPhysicsPayload 驱动）：
     * 斩痕严格沿着实际 3D 刀锋切入矢量 (dirX, dirY, dirZ) 划开，火星顺刀势斜向喷溅！
     */
    public static void onHitPhysics(HitPhysicsPayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        RandomSource rand = level.getRandom();

        int eid = p.attackerId();
        int targetId = p.targetId();
        boolean threadBreak = p.isThreadBreak();
        boolean lethal = p.isLethal();
        boolean heavy = p.isHeavy();
        boolean sun = p.isSunStyle();
        boolean isLocalAttacker = mc.player != null && mc.player.getId() == eid;

        // 1. 出刀顿刀卡肉（与服务端同频的 144Hz 0.14x 咬合减速 + 刀身微震；身后二段龙碾 hitstop=0 不拖慢前面冲刺！）
        if (p.hitstopTicks() > 0.05F) {
            SwordChoreography.triggerHitstop(eid, p.hitstopTicks(), Mth.clamp(p.force() * 0.65F, 0.70F, 1.55F));
        }

        // 2. 沿真实 3D 刀锋切线矢量生成赛璐璐斩痕（不再随机乱指！）
        Vec3 dir = new Vec3(p.dirX(), p.dirY(), p.dirZ());
        if (dir.lengthSqr() > 1.0E-5) dir = dir.normalize();
        else dir = new Vec3(0.0, 0.0, 1.0);
        float cutYaw = (float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        float cutPitch = (float) (-Math.asin(Mth.clamp(dir.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);

        float mainLen = (threadBreak || lethal ? 2.30F : (heavy ? 1.65F : 1.15F));
        RIFT_SLASHES.add(new WaterRiftSlashVfx(
                p.hitX(), p.hitY(), p.hitZ(), cutYaw, cutPitch, mainLen,
                threadBreak || lethal ? 12 : 8, heavy || threadBreak || lethal, sun
        ));
        if (heavy || threadBreak || lethal) {
            // 交叉补斩痕强化一闪断首张力
            int extra = (threadBreak || lethal) ? 3 : 1;
            for (int i = 0; i < extra; i++) {
                float offYaw = cutYaw + (i == 0 ? 78.0F : (rand.nextFloat() - 0.5F) * 140.0F);
                float offPitch = -cutPitch + (rand.nextFloat() - 0.5F) * 35.0F;
                RIFT_SLASHES.add(new WaterRiftSlashVfx(
                        p.hitX(), p.hitY(), p.hitZ(), offYaw, offPitch, mainLen * (0.72F + rand.nextFloat() * 0.35F),
                        threadBreak || lethal ? 11 : 7, true, sun
                ));
            }
        }

        // 3. 顺刀势喷溅的定向火星/水针（沿切线 dir 方向喷射，符合物理直觉！）
        int streakN = threadBreak || lethal ? 24 : (heavy ? 15 : 8);
        for (int i = 0; i < streakN && STREAKS.size() < MAX_STREAKS; i++) {
            double spd = (threadBreak ? 0.55 : 0.38) * (0.55 + rand.nextDouble() * 0.90);
            Vec3 sv = dir.scale(spd).add(
                    (rand.nextDouble() - 0.5) * 0.24,
                    0.08 + (rand.nextDouble() - 0.35) * 0.26,
                    (rand.nextDouble() - 0.5) * 0.24
            );
            STREAKS.add(new VelocityStreakVfx(
                    p.hitX(), p.hitY(), p.hitZ(), sv.x, sv.y, sv.z,
                    1.15F + rand.nextFloat() * 0.85F, 0.025F + rand.nextFloat() * 0.018F,
                    10 + rand.nextInt(9), sun, i % 3 == 0
            ));
        }

        int foamN = threadBreak || lethal ? 9 : (heavy ? 4 : 2);
        spawnBurstUkiyoFoam(p.hitX(), p.hitY(), p.hitZ(), 0.45, foamN, 0.24, threadBreak ? 1.15F : 0.82F, sun, rand);

        // 4. 隙之线物理绷断与破绽连锁同步
        if (threadBreak) {
            if (targetId > 0) {
                THREAD_COOLDOWNS.put(targetId, clientTickCounter + 28L);
                EXPOSED_TARGETS.remove(targetId);
                if (forcedHighlightTargetId == targetId) forcedHighlightTargetId = -1;
            }
            Entity atkEnt = level.getEntity(eid);
            Vec3 startPt = atkEnt != null
                    ? atkEnt.position().add(0.0, 1.15, 0.0).add(atkEnt.getLookAngle().scale(0.95))
                    : new Vec3(p.hitX(), p.hitY(), p.hitZ());
            THREAD_SNAPS.add(new ThreadSnapVfx(startPt, new Vec3(p.hitX(), p.hitY(), p.hitZ()), 11, sun));
            if (isLocalAttacker) {
                triggerFovKickAt(mc, p.hitX(), p.hitY(), p.hitZ(), lethal ? 5.2F : 3.8F, 22.0);
                triggerFlashAt(mc, p.hitX(), p.hitY(), p.hitZ(), lethal ? 0.20F : 0.14F, sun ? 0xFFF0C4 : 0xEBFCFF, 24.0);
                triggerShakeAt(mc, p.hitX(), p.hitY(), p.hitZ(), lethal ? 0.52F : 0.40F, 22.0);
            }
        } else {
            if (isLocalAttacker && !lethal && targetId > 0 && p.force() >= 1.1F) {
                EXPOSED_TARGETS.put(targetId, clientTickCounter + 38L);
            }
            if (isLocalAttacker) {
                if (lethal) {
                    triggerFovKickAt(mc, p.hitX(), p.hitY(), p.hitZ(), 3.6F, 18.0);
                    triggerFlashAt(mc, p.hitX(), p.hitY(), p.hitZ(), 0.12F, sun ? 0xFFEBC0 : 0xE8FAFF, 18.0);
                    triggerShakeAt(mc, p.hitX(), p.hitY(), p.hitZ(), 0.40F, 18.0);
                } else {
                    triggerShakeAt(mc, p.hitX(), p.hitY(), p.hitZ(), heavy ? 0.28F : 0.16F, 16.0);
                    if (heavy) triggerFovKickAt(mc, p.hitX(), p.hitY(), p.hitZ(), 2.0F, 16.0);
                }
            }
        }
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        RandomSource rand = level.getRandom();
        clientTickCounter++;

        // 1. 屏幕震动 / FOV / 闪白 / 暗角平滑演进
        shakeTime += 1.0F;
        shakeStrength *= shakeDecay;
        if (shakeStrength < 0.008F) shakeStrength = 0.0F;

        prevFovDelta = fovDelta;
        int chargeMode = ComboInputController.getChargingMode();
        if (chargeMode == 1) {
            fovTarget = -3.0F - 8.5F * ComboInputController.getChargeProgress();
        } else if (chargeMode == 2) {
            fovTarget = -4.5F * ComboInputController.getChargeProgress();
        } else if (ClientBreathState.getSpecialDomainTicks() > 0) {
            fovTarget = ClientBreathState.isSunStyle() ? 7.8F : 4.2F;
        } else {
            fovTarget *= 0.80F;
            if (Math.abs(fovTarget) < 0.04F) fovTarget = 0.0F;
        }
        fovDelta += (fovTarget - fovDelta) * 0.24F;
        if (Math.abs(fovDelta) < 0.02F && fovTarget == 0.0F) fovDelta = 0.0F;

        prevFlashAlpha = flashAlpha;
        flashAlpha *= 0.72F;
        if (flashAlpha < 0.004F) flashAlpha = 0.0F;

        prevVignetteAlpha = vignetteAlpha;
        vignetteAlpha *= 0.90F;
        if (vignetteAlpha < 0.004F) vignetteAlpha = 0.0F;

        // 注：实时刀光带已升级为渲染帧原生连续采样（见 updateLiveBladeTrailsAtRenderFrame），
        // 与摄像机/人物 100% 同步在同一微秒，彻底告别 20Hz 提前采样导致的回折抽搐！

        // 2. 驱动延时蓄势爆发指令与伴生特效
        if (!DELAYED_CUES.isEmpty()) {
            List<DelayedCue> cueSnap = new ArrayList<>(DELAYED_CUES);
            for (DelayedCue cue : cueSnap) {
                if (--cue.ticksLeft <= 0) {
                    DELAYED_CUES.remove(cue);
                    cue.action.run();
                }
            }
        }
        tickList(WATERFALLS);
        tickList(RIFT_SLASHES);
        tickList(UPHEAVALS);
        tickList(FOAM_SPRITES);
        tickList(STREAKS);
        tickList(THREAD_SNAPS);
        tickList(RIPPLE_RINGS);
        tickList(AFTERIMAGES);

        // 2.5 实时鬼灭战斗直觉扫描（隙之线最佳破绽目标锁定 + 杀气危机感知）
        updateCombatIntuition(level, mc.player);

        for (Iterator<ChargeAuraVfx> it = CHARGE_AURAS.iterator(); it.hasNext(); ) {
            ChargeAuraVfx c = it.next();
            c.prevX = c.x; c.prevY = c.y; c.prevZ = c.z;
            Entity ent = level.getEntity(c.entityId);
            if (ent != null) {
                c.x = ent.getX();
                c.y = ent.getY();
                c.z = ent.getZ();
            }
            if (!c.tick()) it.remove();
        }

        for (Iterator<WaterWhirlpoolVfx> it = WHIRLPOOLS.iterator(); it.hasNext(); ) {
            WaterWhirlpoolVfx wp = it.next();
            wp.prevX = wp.x; wp.prevY = wp.y; wp.prevZ = wp.z;
            Entity ent = level.getEntity(wp.entityId);
            if (ent != null) {
                wp.x = ent.getX();
                wp.y = ent.getY();
                wp.z = ent.getZ();
            }
            if (wp.age % 3 == 0 && wp.age < wp.maxAge - 4) {
                spawnWhirlpoolUkiyoFoam(wp.x, wp.y, wp.z, wp.radius, wp.height, 8, wp.sunStyle, rand);
                spawnBurstStreaks(wp.x, wp.y + wp.height * 0.45, wp.z, wp.radius * 0.75, 12, 0.44, wp.sunStyle, rand);
            }
            if (!wp.tick()) it.remove();
        }

        for (Iterator<DeadCalmMirrorVfx> it = DEAD_CALMS.iterator(); it.hasNext(); ) {
            DeadCalmMirrorVfx d = it.next();
            d.prevX = d.x; d.prevY = d.y; d.prevZ = d.z;
            if (!d.sunStyle) {
                Entity ent = level.getEntity(d.entityId);
                if (ent != null) {
                    d.x = ent.getX();
                    d.y = ent.getY() + 0.06;
                    d.z = ent.getZ();
                }
            } else if (d.age % 4 == 0 && d.age < d.maxAge - 8) {
                spawnBurstStreaks(d.x, d.y + 1.0, d.z, 1.35, 8, 0.26, true, rand);
                if (d.age % 8 == 0) {
                    spawnBurstUkiyoFoam(d.x, d.y + 1.1, d.z, 1.4, 5, 0.16, 1.18F, true, rand);
                }
            }
            if (!d.tick()) it.remove();
        }

        // 拾之型·生生流转 / 日晕之龙：真龙严格滞后于刀锋顺轨追击，沿身后龙首持续喷涌滔天浪花/烈焰！
        double tickNow = peekRenderTime();
        for (Iterator<WaterDragonVfx> it = DRAGONS.iterator(); it.hasNext(); ) {
            WaterDragonVfx dr = it.next();
            dr.stepWithEntity(level, tickNow, rand);
            if (!dr.tick()) it.remove();
        }

        // 日之呼吸奥义终点：三段式孙策龙限风「焦土裂地 · 赤金神龙冲霄 · 战场龙纹余烬」演出调度
        for (Iterator<SunDragonFinaleVfx> it = SUN_FINALES.iterator(); it.hasNext(); ) {
            SunDragonFinaleVfx finale = it.next();
            finale.stepEffects(level, rand, mc);
            if (!finale.tick()) it.remove();
        }

        for (Iterator<FlyingBlockDebris> it = DEBRIS.iterator(); it.hasNext(); ) {
            if (!it.next().tick()) it.remove();
        }

        for (Iterator<OrbitingBlockDebris> it = ORBITING_BLOCKS.iterator(); it.hasNext(); ) {
            OrbitingBlockDebris orb = it.next();
            if (!orb.tick()) {
                it.remove();
                if (DEBRIS.size() < MAX_DEBRIS) {
                    double vx = Math.cos(orb.angle) * (0.28 + rand.nextDouble() * 0.25);
                    double vz = Math.sin(orb.angle) * (0.28 + rand.nextDouble() * 0.25);
                    double vy = 0.22 + rand.nextDouble() * 0.28;
                    DEBRIS.add(new FlyingBlockDebris(
                            orb.blockState, orb.seedPos, orb.x, orb.y, orb.z,
                            vx, vy, vz, orb.baseScale, 20 + rand.nextInt(10), rand
                    ));
                }
            }
        }
    }

    /** 只读当前平滑时钟，绝不提前推进 lastGlobalRenderTime（避免破坏 144Hz partialTick 连续性！） */
    public static double peekRenderTime() {
        return Math.max(lastGlobalRenderTime, (double) clientTickCounter);
    }

    public static double getRenderTime(float partialTick) {
        if (Minecraft.getInstance().isPaused()) {
            return lastGlobalRenderTime;
        }
        double candidate = clientTickCounter + Mth.clamp(partialTick, 0.0F, 1.0F);
        if (candidate > lastGlobalRenderTime) {
            lastGlobalRenderTime = candidate;
        }
        return lastGlobalRenderTime;
    }

    /**
     * 真·渲染帧原生 60/144/240Hz 连续刀光采样（由 ExtractLevelRenderStateEvent 每帧调用）：
     * - 严格单调递增时钟 nowTime，绝不时间倒流回折！
     * - 每个采样点的 base / tip / normal / arcDist / birthTime 出生瞬间永久锁死，绝不每帧重算抖动！
     * - 粒子发射按固定 0.25 tick 间隔节流，不随帧率变化保持完美一致的炸裂漫画密度！
     */
    public static double updateLiveBladeTrailsAtRenderFrame(ClientLevel level, float pt) {
        double nowTime = getRenderTime(pt);
        if (Minecraft.getInstance().isPaused()) {
            return nowTime;
        }
        RandomSource rand = level.getRandom();
        Set<Integer> activeIds = new HashSet<>();

        // 144Hz 渲染帧原生采集冲刺真龙的施法者历史路径（彻底消灭 20Hz 龙躯阶梯跳动，实现丝绸般顺轨追袭！）
        for (WaterDragonVfx dr : DRAGONS) {
            dr.sampleEntityPathAtRenderFrame(level, nowTime, pt);
        }

        for (Player player : level.players()) {
            if (!player.isAlive() || !SwordChoreography.isHoldingWaterBlade(player)) {
                continue;
            }
            SwordChoreography.BladeSample sample = SwordChoreography.sampleWorldBlade(player, pt);
            if (!sample.active()) {
                continue;
            }
            int id = player.getId();
            activeIds.add(id);
            SwordChoreography.PlayerSwordAnim anim = SwordChoreography.get(id);
            boolean isRush = anim != null && anim.animType == SwordChoreography.ANIM_CONSTANT_FLUX;

            LiveBladeTrail trail = BLADE_TRAILS.computeIfAbsent(id, k -> new LiveBladeTrail(id));
            trail.sunStyle = sample.sunStyle();

            if (nowTime > trail.lastSampleTime + 1.0E-4) {
                // 若帧率较低导致帧间跨度较大（且仍在同一 tick 内），自动补插中间平滑子步
                if (trail.lastSampleTick == clientTickCounter && pt - trail.lastSamplePt > 0.24F) {
                    int subSteps = Mth.clamp((int) Math.ceil((pt - trail.lastSamplePt) / 0.20F), 2, 4);
                    for (int s = 1; s < subSteps; s++) {
                        float subPt = Mth.lerp(s / (float) subSteps, trail.lastSamplePt, pt);
                        double subTime = clientTickCounter + subPt;
                        SwordChoreography.BladeSample mid = SwordChoreography.sampleWorldBlade(player, subPt);
                        if (mid.active()) {
                            trail.pushSample(mid.base(), mid.tip(), mid.intensity(), subTime, isRush);
                        }
                    }
                }

                trail.pushSample(sample.base(), sample.tip(), sample.intensity(), nowTime, isRush);
                trail.lastSampleTime = nowTime;
                trail.lastSampleTick = clientTickCounter;
                trail.lastSamplePt = pt;

                // 「刀先破空」：当前刀尖瞬间仅发射极细极锐的高速火星/水针与材质触碰火花，绝不在鼻尖刷大贴片糊眼！
                if (trail.lastParticleTip == null) {
                    trail.lastParticleTip = sample.tip();
                    trail.lastParticleTime = nowTime;
                } else if (nowTime - trail.lastParticleTime >= 0.25) {
                    Vec3 tipMove = sample.tip().subtract(trail.lastParticleTip);
                    double speed = tipMove.length();
                    trail.lastParticleTip = sample.tip();
                    trail.lastParticleTime = nowTime;

                    if (speed > 0.22 && speed < 4.5) {
                        int streakCount = Mth.clamp((int) (speed * (isRush ? 1.8 : 2.4)), 1, isRush ? 2 : 3);
                        for (int k = 0; k < streakCount && STREAKS.size() < MAX_STREAKS; k++) {
                            Vec3 pos = sample.base().lerp(sample.tip(), 0.65 + 0.35 * rand.nextDouble());
                            Vec3 vel = tipMove.scale(0.32 + rand.nextDouble() * 0.22).add(
                                    (rand.nextDouble() - 0.5) * 0.16,
                                    (sample.sunStyle() ? 0.08 : 0.03) + rand.nextDouble() * 0.12,
                                    (rand.nextDouble() - 0.5) * 0.16
                            );
                            STREAKS.add(new VelocityStreakVfx(
                                    pos.x, pos.y, pos.z, vel.x, vel.y, vel.z,
                                    1.20F + rand.nextFloat() * 0.90F,
                                    0.022F + rand.nextFloat() * 0.016F,
                                    9 + rand.nextInt(8), sample.sunStyle(), rand.nextFloat() < 0.45F
                            ));
                        }

                        // 旷野之息式刀尖真实材质接触物理：刀锋掠过水面激水花、刮石壁迸金铁火星、擦熔岩/雪层冒白汽！
                        emitBladeSurfaceContactVfx(level, sample.tip(), tipMove, sample.sunStyle(), rand);
                    }
                }
            }
        }

        // 「特效后跟」：在所有刀光带的历史轨迹点（慢刀尖 1.15~2.15 tick）处轰然炸开张扬浮世绘漫画浪花与尾流火星！
        for (Iterator<Map.Entry<Integer, LiveBladeTrail>> it = BLADE_TRAILS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, LiveBladeTrail> e = it.next();
            LiveBladeTrail tr = e.getValue();
            tr.pruneByTime(nowTime);

            if (nowTime - tr.lastWakeEmitTime >= 0.28 && tr.points.size() >= 3) {
                TrailNode prevN = null;
                for (TrailNode node : tr.points) {
                    double age = nowTime - node.birthTime;
                    double targetDelay = node.rushNode ? 2.05 : 1.15;
                    if (age >= targetDelay && age <= targetDelay + 1.1 && !node.wakeEmitted) {
                        node.wakeEmitted = true;
                        tr.lastWakeEmitTime = nowTime;
                        Vec3 move = (prevN != null) ? prevN.tip.subtract(node.tip) : Vec3.ZERO;
                        double spd = move.length();
                        if (spd > 0.10 && spd < 4.5) {
                            int foamCount = node.rushNode ? 2 : Mth.clamp((int) (spd * 1.9) + 1, 1, 3);
                            for (int k = 0; k < foamCount && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; k++) {
                                Vec3 pos = node.base.lerp(node.tip, 0.50 + 0.50 * rand.nextDouble());
                                Vec3 vel = move.scale(0.18).add(
                                        (rand.nextDouble() - 0.5) * 0.16,
                                        (tr.sunStyle ? 0.08 : 0.04) + rand.nextDouble() * 0.13,
                                        (rand.nextDouble() - 0.5) * 0.16
                                );
                                int pick = rand.nextInt(10);
                                int tile = pick < 3 ? 0 : (pick < 6 ? 1 : (pick < 8 ? 2 : 3));
                                float size = (tr.sunStyle ? 1.28F : 1.22F) * (0.74F + rand.nextFloat() * 0.58F);
                                FOAM_SPRITES.add(new UkiyoFoamSprite(
                                        pos.x, pos.y, pos.z, vel.x, vel.y, vel.z,
                                        tile, size, rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 12.0F,
                                        9 + rand.nextInt(7), tr.sunStyle
                                ));
                            }
                            if (STREAKS.size() < MAX_STREAKS) {
                                Vec3 pos = node.base.lerp(node.tip, 0.55 + 0.45 * rand.nextDouble());
                                Vec3 vel = move.scale(0.24).add(
                                        (rand.nextDouble() - 0.5) * 0.22,
                                        (tr.sunStyle ? 0.12 : 0.06) + rand.nextDouble() * 0.16,
                                        (rand.nextDouble() - 0.5) * 0.22
                                );
                                STREAKS.add(new VelocityStreakVfx(
                                        pos.x, pos.y, pos.z, vel.x, vel.y, vel.z,
                                        1.25F + rand.nextFloat() * 0.95F,
                                        0.026F + rand.nextFloat() * 0.018F,
                                        10 + rand.nextInt(9), tr.sunStyle, rand.nextFloat() < 0.35F
                                ));
                            }
                        }
                        break;
                    }
                    prevN = node;
                }
            }

            if (!activeIds.contains(e.getKey())) {
                tr.lastParticleTip = null;
                if (tr.points.size() < 2) {
                    it.remove();
                }
            }
        }
        return nowTime;
    }

    /** 实时 3D 刀尖与世界方块的物理接触反馈（刀走到哪，碰到水面/石壁/熔岩/积雪就即时产生对应物理材质反应！） */
    private static void emitBladeSurfaceContactVfx(
            ClientLevel level, Vec3 tip, Vec3 tipMove, boolean sunStyle, RandomSource rand
    ) {
        BlockPos pos = BlockPos.containing(tip.x, tip.y, tip.z);
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) {
            pos = BlockPos.containing(tip.x, tip.y - 0.35, tip.z);
            st = level.getBlockState(pos);
            if (st.isAir()) return;
        }

        if (st.is(Blocks.WATER)) {
            if (sunStyle) {
                level.addParticle(ParticleTypes.CLOUD, tip.x, pos.getY() + 0.98, tip.z,
                        (rand.nextDouble() - 0.5) * 0.08, 0.09 + rand.nextDouble() * 0.08, (rand.nextDouble() - 0.5) * 0.08);
            } else {
                for (int i = 0; i < 3 && STREAKS.size() < MAX_STREAKS; i++) {
                    STREAKS.add(new VelocityStreakVfx(
                            tip.x, pos.getY() + 0.98, tip.z,
                            tipMove.x * 0.25 + (rand.nextDouble() - 0.5) * 0.24,
                            0.22 + rand.nextDouble() * 0.22,
                            tipMove.z * 0.25 + (rand.nextDouble() - 0.5) * 0.24,
                            1.15F, 0.026F, 11 + rand.nextInt(5), false, true
                    ));
                }
                if (FOAM_SPRITES.size() < MAX_FOAM_SPRITES) {
                    FOAM_SPRITES.add(new UkiyoFoamSprite(
                            tip.x, pos.getY() + 1.02, tip.z,
                            tipMove.x * 0.16, 0.14 + rand.nextDouble() * 0.10, tipMove.z * 0.16,
                            rand.nextInt(4), 1.15F, rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 16.0F,
                            10, false
                    ));
                }
            }
        } else if (st.is(Blocks.LAVA)) {
            for (int i = 0; i < 2; i++) {
                level.addParticle(sunStyle ? ParticleTypes.LAVA : ParticleTypes.CLOUD,
                        tip.x, pos.getY() + 0.98, tip.z,
                        (rand.nextDouble() - 0.5) * 0.10, 0.12 + rand.nextDouble() * 0.10, (rand.nextDouble() - 0.5) * 0.10);
            }
        } else if (st.is(Blocks.SNOW) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW)) {
            level.addParticle(sunStyle ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE,
                    tip.x, tip.y + 0.1, tip.z,
                    tipMove.x * 0.15 + (rand.nextDouble() - 0.5) * 0.08, 0.09, tipMove.z * 0.15 + (rand.nextDouble() - 0.5) * 0.08);
        } else if (st.isSolidRender()) {
            // 刀尖刮到石头/金属/硬壁：在精确接触点炸出炽亮金铁刮擦火星！
            for (int i = 0; i < 4 && STREAKS.size() < MAX_STREAKS; i++) {
                STREAKS.add(new VelocityStreakVfx(
                        tip.x, tip.y, tip.z,
                        -tipMove.x * 0.22 + (rand.nextDouble() - 0.5) * 0.30,
                        0.16 + rand.nextDouble() * 0.24,
                        -tipMove.z * 0.22 + (rand.nextDouble() - 0.5) * 0.30,
                        1.05F, 0.022F, 8 + rand.nextInt(6), true, true
                ));
            }
        }
    }

    private static <T extends TickableVfx> void tickList(List<T> list) {
        for (Iterator<T> it = list.iterator(); it.hasNext(); ) {
            if (!it.next().tick()) it.remove();
        }
    }

    public static void applyCameraShake(ViewportEvent.ComputeCameraAngles event) {
        if (shakeStrength <= 0.005F) return;
        float pt = (float) event.getPartialTick();
        float t = shakeTime + pt;
        float amp = shakeStrength;
        // 沉稳低频微震，取消高频翻滚晃动（消除“全屏特效跟着疯狂抖动”的眩晕感！）
        event.setYaw(event.getYaw() + Mth.sin(t * 1.15F) * amp * 0.55F);
        event.setPitch(event.getPitch() + Mth.cos(t * 1.30F) * amp * 0.45F);
    }

    public static void applyFovModifier(ViewportEvent.ComputeFov event) {
        float pt = (float) event.getPartialTick();
        float delta = Mth.lerp(pt, prevFovDelta, fovDelta);
        if (Math.abs(delta) > 0.02F) {
            event.setFOV(Mth.clamp(event.getFOV() + delta, 32.0F, 132.0F));
        }
    }

    public static float getFlashAlpha(float partialTick) {
        return Mth.lerp(partialTick, prevFlashAlpha, flashAlpha);
    }

    public static int getFlashRgb() { return flashRgb; }

    public static float getVignetteAlpha(float partialTick) {
        return Mth.lerp(partialTick, prevVignetteAlpha, vignetteAlpha);
    }

    public static int getOpeningThreadTargetId() { return openingThreadTargetId; }

    public static float getOpeningThreadStrength(float partialTick) {
        return Mth.clamp(Mth.lerp(partialTick, prevOpeningThreadStrength, openingThreadStrength), 0.0F, 1.0F);
    }

    public static boolean isOpeningThreadForcedHighlight() {
        return openingThreadTargetId > 0 && openingThreadTargetId == forcedHighlightTargetId && clientTickCounter < forcedHighlightUntil;
    }

    public static float getDangerAlertAlpha(float partialTick) {
        return Mth.clamp(Mth.lerp(partialTick, prevDangerAlertTicks, dangerAlertTicks) / 6.0F, 0.0F, 1.0F);
    }

    public static float getIntuitionFlashAlpha(float partialTick) {
        if (intuitionFlashTicks <= 0 && prevIntuitionFlashTicks <= 0) return 0.0F;
        float rem = Mth.lerp(partialTick, prevIntuitionFlashTicks, intuitionFlashTicks);
        return Mth.clamp(rem / Math.max(1.0F, intuitionFlashMax), 0.0F, 1.0F);
    }

    public static int getIntuitionFlashType() { return intuitionFlashType; }

    private static void triggerIntuitionFlash(int type, int duration) {
        intuitionFlashType = type;
        intuitionFlashMax = Math.max(6, duration);
        intuitionFlashTicks = intuitionFlashMax;
        prevIntuitionFlashTicks = intuitionFlashMax;
    }

    /**
     * 鬼灭核心战斗直觉感知引擎（客户端每 tick 运行）：
     * 1. 「隙之线」：在视野前方 14.5 格内寻找最契合直觉的破绽目标（看破锁定 > 刚受招硬直 > 残血濒死 > 进攻空门）
     * 2. 「危」杀气感知：敏锐察觉飞向自己的弹射物或近身挥击猛扑的敌怪，给予极简克制的危机闪芒与轻风警觉音
     */
    private static void updateCombatIntuition(ClientLevel level, LocalPlayer player) {
        prevOpeningThreadStrength = openingThreadStrength;
        prevDangerAlertTicks = dangerAlertTicks;
        prevIntuitionFlashTicks = intuitionFlashTicks;
        if (dangerAlertTicks > 0) dangerAlertTicks--;
        if (dangerSoundCooldown > 0) dangerSoundCooldown--;
        if (intuitionFlashTicks > 0) intuitionFlashTicks--;

        if (clientTickCounter % 40 == 0) {
            EXPOSED_TARGETS.entrySet().removeIf(e -> e.getValue() <= clientTickCounter);
            THREAD_COOLDOWNS.entrySet().removeIf(e -> e.getValue() <= clientTickCounter);
        }

        if (player == null || !player.isAlive() || !SwordChoreography.isHoldingWaterBlade(player)) {
            openingThreadStrength *= 0.72F;
            if (openingThreadStrength < 0.02F) {
                openingThreadStrength = 0.0F;
                openingThreadTargetId = -1;
            }
            return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();

        // 1. 扫描视野前方最契合直觉的「隙之线」破绽目标
        int bestId = -1;
        double bestScore = -1.0;
        AABB scanBox = player.getBoundingBox().inflate(14.5, 6.5, 14.5);
        for (LivingEntity ent : level.getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> e.isAlive() && e != player && !e.isInvisible())) {
            int id = ent.getId();
            if (THREAD_COOLDOWNS.getOrDefault(id, 0L) > clientTickCounter) continue;

            Vec3 toNeck = ent.position().add(0.0, ent.getBbHeight() * 0.78, 0.0).subtract(eye);
            double dist = toNeck.length();
            if (dist < 0.6 || dist > 14.5) continue;
            double dot = toNeck.scale(1.0 / dist).dot(look);
            if (dot < 0.16) continue;

            boolean forced = (id == forcedHighlightTargetId && clientTickCounter < forcedHighlightUntil);
            boolean exposed = EXPOSED_TARGETS.getOrDefault(id, 0L) > clientTickCounter;
            boolean lowHp = ent.getHealth() <= ent.getMaxHealth() * 0.38F || ent.getHealth() <= 10.0F;
            boolean attacking = ent.isSwinging()
                    || (ent instanceof Enemy && dist <= 5.5)
                    || (ent instanceof Mob m && m.getTarget() == player && dist <= 5.8);

            if (!forced && !exposed && !lowHp && !attacking) continue;

            double score = dot * 38.0 + (14.5 - dist) * 2.2;
            if (forced) score += 120.0;
            if (exposed) score += 72.0;
            if (lowHp) score += 58.0;
            if (attacking) score += 48.0;
            if (dist <= 6.8) score += 24.0;
            if (id == openingThreadTargetId) score += 16.0; // 迟滞防抖：避免两个怪之间来回跳闪

            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }

        if (bestId != -1) {
            if (openingThreadTargetId != bestId) {
                openingThreadTargetId = bestId;
                openingThreadStrength = Math.max(0.20F, openingThreadStrength * 0.5F);
            }
            openingThreadStrength = Math.min(1.0F, openingThreadStrength + 0.24F);
        } else {
            openingThreadStrength *= 0.76F;
            if (openingThreadStrength < 0.02F) {
                openingThreadStrength = 0.0F;
                openingThreadTargetId = -1;
            }
        }

        // 2. 杀气危机感知（仅对正在飞向自己的弹射物或近身挥击猛扑的敌怪发声提示）
        boolean imminentDanger = false;
        AABB projBox = player.getBoundingBox().inflate(9.0, 4.0, 9.0);
        for (Projectile proj : level.getEntitiesOfClass(Projectile.class, projBox,
                pr -> pr.isAlive() && !pr.onGround() && pr.getOwner() != player)) {
            Vec3 vel = proj.getDeltaMovement();
            Vec3 toP = eye.subtract(proj.position());
            if (vel.lengthSqr() > 0.04 && toP.lengthSqr() > 0.1 && vel.normalize().dot(toP.normalize()) > 0.58) {
                imminentDanger = true;
                break;
            }
        }
        if (!imminentDanger) {
            AABB dangerMobBox = player.getBoundingBox().inflate(4.6, 2.4, 4.6);
            for (LivingEntity ent : level.getEntitiesOfClass(LivingEntity.class, dangerMobBox,
                    e -> e.isAlive() && e != player && (e instanceof Enemy || e instanceof Mob))) {
                if (ent.isSwinging() || (ent instanceof Enemy && ent.getDeltaMovement().horizontalDistanceSqr() > 0.015
                        && ent.distanceToSqr(player) <= 18.0)) {
                    imminentDanger = true;
                    break;
                }
            }
        }
        if (imminentDanger) {
            boolean fresh = dangerAlertTicks == 0;
            dangerAlertTicks = 6;
            if (fresh && dangerSoundCooldown <= 0) {
                dangerSoundCooldown = 28;
                player.playSound(SoundEvents.BREEZE_SLIDE, 0.34F, 1.85F);
            }
        }
    }

    private static void resetBladeTrail(int entityId) {
        BLADE_TRAILS.remove(entityId);
    }

    public static void clearAll() {
        BLADE_TRAILS.clear();
        DEBRIS.clear();
        UPHEAVALS.clear();
        ORBITING_BLOCKS.clear();
        WHIRLPOOLS.clear();
        WATERFALLS.clear();
        DRAGONS.clear();
        SUN_FINALES.clear();
        DEAD_CALMS.clear();
        RIFT_SLASHES.clear();
        CHARGE_AURAS.clear();
        FOAM_SPRITES.clear();
        STREAKS.clear();
        THREAD_SNAPS.clear();
        RIPPLE_RINGS.clear();
        AFTERIMAGES.clear();
        DELAYED_CUES.clear();
        EXPOSED_TARGETS.clear();
        THREAD_COOLDOWNS.clear();
        openingThreadTargetId = -1;
        openingThreadStrength = 0.0F;
        prevOpeningThreadStrength = 0.0F;
        forcedHighlightTargetId = -1;
        forcedHighlightUntil = 0L;
        dangerAlertTicks = 0;
        prevDangerAlertTicks = 0;
        dangerSoundCooldown = 0;
        intuitionFlashTicks = 0;
        prevIntuitionFlashTicks = 0;
        intuitionFlashType = 0;
        shakeStrength = 0.0F;
        fovDelta = 0.0F;
        prevFovDelta = 0.0F;
        fovTarget = 0.0F;
        flashAlpha = 0.0F;
        prevFlashAlpha = 0.0F;
        vignetteAlpha = 0.0F;
        prevVignetteAlpha = 0.0F;
        clientTickCounter = 0L;
        lastGlobalRenderTime = 0.0;
    }

    // ==================== 浮世绘黑边白沫喷溅与地面碎块工具 ====================

    private static void spawnSlashStreaks(
            double cx, double cy, double cz, float yawDeg, float radius, float sweepDeg,
            int count, boolean sunStyle, RandomSource rand
    ) {
        float baseYaw = (yawDeg + 90.0F) * Mth.DEG_TO_RAD;
        float halfSweep = (sweepDeg * 0.5F) * Mth.DEG_TO_RAD;
        float cosY = Mth.cos(baseYaw), sinY = Mth.sin(baseYaw);
        for (int i = 0; i < count && STREAKS.size() < MAX_STREAKS; i++) {
            float u = (i + rand.nextFloat()) / Math.max(1, count);
            float ang = -halfSweep + u * halfSweep * 2.0F;
            float r = radius * (0.55F + rand.nextFloat() * 0.55F);
            float lx = Mth.cos(ang) * r, lz = Mth.sin(ang) * r;
            double sx = cx + lx * cosY - lz * sinY;
            double sy = cy + (rand.nextDouble() - 0.35) * 0.75;
            double sz = cz + lx * sinY + lz * cosY;
            double ox = (sx - cx) / Math.max(0.4, r), oz = (sz - cz) / Math.max(0.4, r);
            double spd = 0.28 + rand.nextDouble() * 0.36;
            STREAKS.add(new VelocityStreakVfx(
                    sx, sy, sz,
                    ox * spd - oz * 0.26, (sunStyle ? 0.14 : 0.08) + rand.nextDouble() * 0.24, oz * spd + ox * 0.26,
                    1.20F + rand.nextFloat() * 1.0F, 0.025F + rand.nextFloat() * 0.018F,
                    12 + rand.nextInt(10), sunStyle, rand.nextFloat() < 0.38F
            ));
        }
    }

    /** 空气坍缩倒吸光丝（在 0.2s 聚压寂静期从四周向阵眼中心狂吸汇聚，营造极致爆炸前奏张力！） */
    private static void spawnImplosionStreaks(
            double cx, double cy, double cz, double minR, double maxR, int count, int lifeTicks,
            boolean sunStyle, RandomSource rand
    ) {
        int life = Math.max(3, lifeTicks);
        for (int i = 0; i < count && STREAKS.size() < MAX_STREAKS; i++) {
            double ang = rand.nextDouble() * Math.PI * 2.0;
            double r = minR + rand.nextDouble() * Math.max(0.5, maxR - minR);
            double dy = (rand.nextDouble() - 0.15) * 4.4;
            double sx = cx + Math.cos(ang) * r;
            double sy = cy + dy;
            double sz = cz + Math.sin(ang) * r;
            // 速度精确指向阵眼中心，刚好在 life tick 时坍缩归零于一点！
            double invT = 1.0 / (life * 0.92);
            double vx = (cx - sx) * invT;
            double vy = (cy - sy) * invT;
            double vz = (cz - sz) * invT;
            STREAKS.add(new VelocityStreakVfx(
                    sx, sy, sz, vx, vy, vz,
                    1.35F + rand.nextFloat() * 0.75F,
                    0.028F + rand.nextFloat() * 0.018F,
                    life, sunStyle, i % 2 == 0
            ));
        }
    }

    private static void spawnBurstStreaks(
            double cx, double cy, double cz, double spreadRadius, int count, double speed,
            boolean sunStyle, RandomSource rand
    ) {
        for (int i = 0; i < count && STREAKS.size() < MAX_STREAKS; i++) {
            double ang = rand.nextDouble() * Math.PI * 2.0;
            double r = rand.nextDouble() * spreadRadius;
            double sx = cx + Math.cos(ang) * r;
            double sy = cy + (rand.nextDouble() - 0.30) * spreadRadius * 0.75;
            double sz = cz + Math.sin(ang) * r;
            double spd = speed * (0.45 + rand.nextDouble() * 0.90);
            double vy = (sunStyle ? 0.16 : 0.06) + rand.nextDouble() * speed * 1.05;
            STREAKS.add(new VelocityStreakVfx(
                    sx, sy, sz,
                    Math.cos(ang) * spd, vy, Math.sin(ang) * spd,
                    1.15F + rand.nextFloat() * 1.05F, 0.026F + rand.nextFloat() * 0.020F,
                    12 + rand.nextInt(12), sunStyle, rand.nextFloat() < 0.38F
            ));
        }
    }

    private static void spawnSlashUkiyoFoam(
            double cx, double cy, double cz, float yawDeg, float radius, float tiltDeg, float sweepDeg,
            int count, boolean tc, boolean sunStyle, RandomSource rand
    ) {
        float baseYaw = (yawDeg + 90.0F) * Mth.DEG_TO_RAD;
        float halfSweep = (sweepDeg * 0.5F) * Mth.DEG_TO_RAD;
        float tiltRad = tiltDeg * Mth.DEG_TO_RAD;
        float cosY = Mth.cos(baseYaw), sinY = Mth.sin(baseYaw);
        float cosT = Mth.cos(tiltRad), sinT = Mth.sin(tiltRad);

        for (int i = 0; i < count && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; i++) {
            float u = (i + rand.nextFloat()) / Math.max(1, count);
            float localAng = -halfSweep + u * halfSweep * 2.0F;
            float r = radius * (0.68F + rand.nextFloat() * 0.44F);
            float lx = Mth.cos(localAng) * r;
            float lz = Mth.sin(localAng) * r;
            float ly = lz * sinT + (rand.nextFloat() - 0.35F) * 0.65F;
            float lzT = lz * cosT;
            double sx = cx + lx * cosY - lzT * sinY;
            double sy = cy + ly;
            double sz = cz + lx * sinY + lzT * cosY;

            double outX = (sx - cx) / Math.max(0.4, r);
            double outZ = (sz - cz) / Math.max(0.4, r);
            double spd = 0.14 + rand.nextDouble() * 0.24;
            int pick = rand.nextInt(10);
            int tile = pick < 3 ? 1 : (pick < 6 ? 0 : (pick < 8 ? 2 : 3));
            float size = (tile == 2 ? 1.35F : (tile == 1 ? 1.25F : 1.05F))
                    * (0.75F + rand.nextFloat() * 0.65F) * (tc ? 1.18F : 1.0F);
            FOAM_SPRITES.add(new UkiyoFoamSprite(
                    sx, sy, sz,
                    outX * spd - outZ * 0.12, 0.06 + rand.nextDouble() * 0.22, outZ * spd + outX * 0.12,
                    tile, size, rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 18.0F,
                    11 + rand.nextInt(9), sunStyle
            ));
        }
    }

    private static void spawnBurstUkiyoFoam(
            double cx, double cy, double cz, double spreadRadius, int count, double speed, float sizeScale,
            boolean sunStyle, RandomSource rand
    ) {
        for (int i = 0; i < count && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; i++) {
            double ang = rand.nextDouble() * Math.PI * 2.0;
            double r = rand.nextDouble() * spreadRadius;
            double sx = cx + Math.cos(ang) * r;
            double sy = cy + (rand.nextDouble() - 0.35) * spreadRadius * 0.85;
            double sz = cz + Math.sin(ang) * r;
            double spd = speed * (0.4 + rand.nextDouble() * 0.85);
            FOAM_SPRITES.add(new UkiyoFoamSprite(
                    sx, sy, sz,
                    Math.cos(ang) * spd, (rand.nextDouble() - 0.1) * speed * 1.15, Math.sin(ang) * spd,
                    rand.nextInt(4), sizeScale * (0.65F + rand.nextFloat() * 0.72F),
                    rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 20.0F,
                    11 + rand.nextInt(9), sunStyle
            ));
        }
    }

    private static void extendActiveDragonTracking(int entityId, int extraTicks) {
        for (WaterDragonVfx dr : DRAGONS) {
            if (dr.entityId == entityId && !dr.finaleLocked) {
                dr.extendTracking(extraTicks);
            }
        }
    }

    private static void upgradeActiveDragonStage(int entityId, int stage, int extraTicks) {
        for (WaterDragonVfx dr : DRAGONS) {
            if (dr.entityId == entityId && !dr.finaleLocked) {
                dr.upgradeStage(stage, extraTicks);
            }
        }
    }

    private static void lockActiveDragonFinale(int entityId, Vec3 targetPos, float delayTicks, double nowTime) {
        for (WaterDragonVfx dr : DRAGONS) {
            if (dr.entityId == entityId && !dr.finaleLocked) {
                dr.lockFinaleTarget(targetPos, delayTicks, nowTime);
            }
        }
    }

    /** 环形外爆漫画浪花/烈焰贴片（从 minR..maxR 外环向外放射炸开，留出中心清澈视野呈现神龙与招式主体！） */
    private static void spawnRingBurstUkiyoFoam(
            double cx, double cy, double cz, double minR, double maxR, int count, double speed, float sizeScale,
            boolean sunStyle, RandomSource rand
    ) {
        double lo = Math.max(0.2, minR);
        double hi = Math.max(lo + 0.3, maxR);
        for (int i = 0; i < count && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; i++) {
            double ang = (Math.PI * 2.0 * i) / Math.max(1, count) + (rand.nextDouble() - 0.5) * 0.45;
            double r = lo + rand.nextDouble() * (hi - lo);
            double sx = cx + Math.cos(ang) * r;
            double sy = cy + (rand.nextDouble() - 0.28) * Math.min(2.6, hi * 0.55);
            double sz = cz + Math.sin(ang) * r;
            double spd = speed * (0.55 + rand.nextDouble() * 0.85);
            FOAM_SPRITES.add(new UkiyoFoamSprite(
                    sx, sy, sz,
                    Math.cos(ang) * spd, (rand.nextDouble() - 0.08) * speed * 1.10, Math.sin(ang) * spd,
                    rand.nextInt(4), sizeScale * (0.68F + rand.nextFloat() * 0.68F),
                    rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 20.0F,
                    11 + rand.nextInt(9), sunStyle
            ));
        }
    }

    private static void spawnWhirlpoolUkiyoFoam(
            double cx, double cy, double cz, float radius, float height, int count, boolean sunStyle, RandomSource rand
    ) {
        for (int i = 0; i < count && FOAM_SPRITES.size() < MAX_FOAM_SPRITES; i++) {
            float hFrac = rand.nextFloat();
            double ang = rand.nextDouble() * Math.PI * 2.0;
            // 台风眼留白：贴片从 0.68*r 外缘开始高速旋转甩出，中心视野澄澈通透！
            float r = Mth.lerp(hFrac, radius * 0.68F, radius * 1.18F) * (0.88F + rand.nextFloat() * 0.22F);
            FOAM_SPRITES.add(new UkiyoFoamSprite(
                    cx + Math.cos(ang) * r, cy + 0.35 + hFrac * height, cz + Math.sin(ang) * r,
                    -Math.sin(ang) * 0.26 + Math.cos(ang) * 0.12, 0.14 + rand.nextDouble() * 0.18,
                    Math.cos(ang) * 0.26 + Math.sin(ang) * 0.12,
                    rand.nextInt(4), 1.05F + rand.nextFloat() * 0.90F,
                    rand.nextFloat() * 360.0F, (rand.nextFloat() - 0.5F) * 24.0F,
                    12 + rand.nextInt(8), sunStyle
            ));
        }
    }

    private static void triggerShakeAt(Minecraft mc, double x, double y, double z, float intensity, double maxDist) {
        if (mc.player == null) return;
        double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
        if (dist >= maxDist) return;
        float att = 1.0F - (float) (dist / maxDist);
        shakeStrength = Math.min(2.0F, Math.max(shakeStrength, intensity * att * att));
    }

    private static void triggerFovKickAt(Minecraft mc, double x, double y, double z, float kick, double maxDist) {
        if (mc.player == null) return;
        double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
        if (dist >= maxDist) return;
        float att = 1.0F - (float) (dist / maxDist);
        fovTarget = Mth.clamp(fovTarget + kick * att, -14.0F, 16.0F);
    }

    private static void triggerFlashAt(Minecraft mc, double x, double y, double z, float alpha, int rgb, double maxDist) {
        if (mc.player == null) return;
        double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
        if (dist >= maxDist) return;
        float att = 1.0F - (float) (dist / maxDist);
        flashAlpha = Math.min(0.32F, Math.max(flashAlpha, alpha * att));
        flashRgb = rgb;
    }

    private static void triggerVignetteAt(Minecraft mc, double x, double y, double z, float alpha, double maxDist) {
        if (mc.player == null) return;
        double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
        if (dist >= maxDist) return;
        float att = 1.0F - (float) (dist / maxDist);
        vignetteAlpha = Math.min(0.50F, Math.max(vignetteAlpha, alpha * att));
    }

    private static BlockState sampleSolidGround(ClientLevel level, double wx, double wy, double wz, BlockPos.MutableBlockPos outPos) {
        int bx = Mth.floor(wx), startY = Mth.floor(wy + 0.8), bz = Mth.floor(wz);
        for (int dy = 0; dy <= 5; dy++) {
            outPos.set(bx, startY - dy, bz);
            BlockState st = level.getBlockState(outPos);
            if (!st.isAir() && st.getRenderShape() == RenderShape.MODEL && !st.getCollisionShape(level, outPos).isEmpty()) {
                return st;
            }
        }
        outPos.set(bx, Mth.floor(wy - 0.5), bz);
        return Blocks.PRISMARINE.defaultBlockState();
    }

    private static double sampleGroundTopY(ClientLevel level, double wx, double wy, double wz) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(wx), startY = Mth.floor(wy + 1.0), bz = Mth.floor(wz);
        for (int dy = 0; dy <= 6; dy++) {
            pos.set(bx, startY - dy, bz);
            BlockState st = level.getBlockState(pos);
            if (!st.isAir() && st.getRenderShape() == RenderShape.MODEL && !st.getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + 1.0;
            }
        }
        return wy;
    }

    private static void spawnGroundDebris(
            ClientLevel level, double cx, double cy, double cz,
            int count, double minR, double maxR, double hSpeed, double vSpeed, int life, RandomSource rand
    ) {
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < count && DEBRIS.size() < MAX_DEBRIS; i++) {
            double ang = rand.nextDouble() * Math.PI * 2.0;
            double r = minR + rand.nextDouble() * (maxR - minR);
            double sx = cx + Math.cos(ang) * r, sz = cz + Math.sin(ang) * r;
            BlockState state = sampleSolidGround(level, sx, cy, sz, samplePos);
            DEBRIS.add(new FlyingBlockDebris(
                    state, samplePos.immutable(), sx, samplePos.getY() + 0.85, sz,
                    Math.cos(ang) * hSpeed * (0.55 + rand.nextDouble() * 0.85),
                    vSpeed * (0.55 + rand.nextDouble() * 0.85),
                    Math.sin(ang) * hSpeed * (0.55 + rand.nextDouble() * 0.85),
                    0.28F + rand.nextFloat() * 0.34F, life + rand.nextInt(10), rand
            ));
        }
    }

    private static void spawnGroundUpheaval(
            ClientLevel level, double cx, double cy, double cz,
            float minR, float maxR, float lift, int life, RandomSource rand
    ) {
        int rings = 2;
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int ring = 0; ring < rings; ring++) {
            float r = Mth.lerp((ring + 0.5F) / rings, minR, maxR);
            int samples = Math.max(8, Math.round(r * 3.6F));
            for (int i = 0; i < samples && UPHEAVALS.size() < MAX_UPHEAVALS; i++) {
                double ang = (Math.PI * 2.0 * i) / samples + rand.nextDouble() * 0.25;
                double sx = cx + Math.cos(ang) * r, sz = cz + Math.sin(ang) * r;
                BlockState state = sampleSolidGround(level, sx, cy, sz, samplePos);
                UPHEAVALS.add(new GroundUpheavalBlock(
                        state, samplePos.immutable(),
                        samplePos.getX() + 0.5, samplePos.getY(), samplePos.getZ() + 0.5,
                        (float) Math.cos(ang), (float) Math.sin(ang),
                        lift * (0.75F + rand.nextFloat() * 0.45F), 16.0F + rand.nextFloat() * 14.0F,
                        ring * 2 + rand.nextInt(2), life + rand.nextInt(6)
                ));
            }
        }
    }

    private static void spawnOrbitingGroundBlocks(
            ClientLevel level, double cx, double cy, double cz,
            int count, float minR, float maxR, float maxRise, int duration, RandomSource rand
    ) {
        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < count && ORBITING_BLOCKS.size() < MAX_ORBITING; i++) {
            double ang = (Math.PI * 2.0 * i) / count + rand.nextDouble() * 0.3;
            float r = minR + rand.nextFloat() * (maxR - minR);
            double sx = cx + Math.cos(ang) * r, sz = cz + Math.sin(ang) * r;
            BlockState state = sampleSolidGround(level, sx, cy, sz, samplePos);
            ORBITING_BLOCKS.add(new OrbitingBlockDebris(
                    state, samplePos.immutable(), cx, cy + 0.4, cz,
                    r, ang, 0.22 + rand.nextDouble() * 0.14,
                    maxRise * (0.55F + rand.nextFloat() * 0.55F),
                    0.26F + rand.nextFloat() * 0.26F, duration + rand.nextInt(5), rand
            ));
        }
    }

    // ==================== 特效数据结构 ====================

    public interface TickableVfx {
        boolean tick();
    }

    /** 渲染帧原生连续刀光带：每个采样点所有几何/法线/弧长/出生时刻 100% 永久锁死不可变！ */
    public static final class LiveBladeTrail {
        public static final double MAX_LIFE_TICKS = 11.5;
        private static final int MAX_POINTS = 196;
        public final int entityId;
        public final Deque<TrailNode> points = new ArrayDeque<>(MAX_POINTS + 8);
        public Vec3 lastTip = null;
        public Vec3 lastNormal = new Vec3(0.0, 1.0, 0.0);
        public float totalArcDist = 0.0F;
        public double lastSampleTime = -1.0;
        public long lastSampleTick = -1L;
        public float lastSamplePt = 0.0F;
        public double lastParticleTime = -1.0;
        public double lastWakeEmitTime = -1.0;
        public Vec3 lastParticleTip = null;
        public boolean sunStyle = false;

        public LiveBladeTrail(int entityId) {
            this.entityId = entityId;
        }

        public void pushSample(Vec3 base, Vec3 tip, float intensity, double birthTime, boolean rushNode) {
            if (lastTip != null) {
                double step = tip.distanceTo(lastTip);
                // 极微静止点跳过，防止高刷新率下压入零长度重复点
                if (step < 0.015 && birthTime - lastSampleTime < 0.18) {
                    return;
                }
                if (step < 3.6) {
                    totalArcDist += (float) step;
                    Vec3 span = tip.subtract(base);
                    Vec3 rawNormal = tip.subtract(lastTip).cross(span);
                    if (rawNormal.lengthSqr() > 1.0E-5) {
                        Vec3 nDir = rawNormal.normalize();
                        if (nDir.dot(lastNormal) < 0.0) {
                            nDir = nDir.scale(-1.0);
                        }
                        Vec3 blended = lastNormal.lerp(nDir, 0.22);
                        if (blended.lengthSqr() > 1.0E-6) {
                            lastNormal = blended.normalize();
                        }
                    }
                }
            }
            points.addFirst(new TrailNode(base, tip, lastNormal, intensity, totalArcDist, birthTime, rushNode));
            lastTip = tip;
            while (points.size() > MAX_POINTS) {
                points.removeLast();
            }
        }

        public void pruneByTime(double nowTime) {
            while (!points.isEmpty() && (nowTime - points.peekLast().birthTime) > MAX_LIFE_TICKS) {
                points.removeLast();
            }
            if (points.isEmpty()) {
                lastTip = null;
                totalArcDist = 0.0F;
            }
        }
    }

    public static final class TrailNode {
        public final Vec3 base, tip, normal;
        public final float intensity;
        /** 永久锁死的绝对世界弧长：保证旧轨迹上的浮世绘贴图 100% 不跳帧、零抖动！ */
        public final float arcDist;
        /** 永久锁死的出生时刻（连续浮点 tick）：驱动「刀先破空 -> 慢半拍轰然展开」零抖动波形！ */
        public final double birthTime;
        /** 是否为奥义高速冲刺期间生成的节点（冲刺节点拥有更长视野清空延迟，彻底甩在剑士身后澎湃炸浪！） */
        public final boolean rushNode;
        public boolean wakeEmitted = false;

        public TrailNode(Vec3 base, Vec3 tip, Vec3 normal, float intensity, float arcDist, double birthTime, boolean rushNode) {
            this.base = base; this.tip = tip; this.normal = normal;
            this.intensity = intensity; this.arcDist = arcDist; this.birthTime = birthTime;
            this.rushNode = rushNode;
        }
    }

    /**
     * 水·生生流转 / 日·日晕之龙：144Hz 连续历史路径插值「刀先出、龙后跟」顺轨追袭真龙！
     * - 冲刺期间：龙头严格落后剑士 2.65 tick (~3.5 格)，沿玩家真实拐弯历史轨迹从身侧后方盘旋追袭，正前方视野 100% 清澈！
     * - 冲刺终点：收到终点爆心坐标后，巨龙从剑士身侧外弧咆哮超车，一头精准撞进正前方裂点引爆大瀑布/冲霄神龙！
     */
    public static final class WaterDragonVfx implements TickableVfx {
        private static final int SPINE_LEN = 38;
        private static final int MAX_PATH_ANCHORS = 260;
        private static final double WAKE_LAG_TICKS = 2.45;

        public final int entityId;
        public int tier;
        public int rushTicks, maxAge;
        public final double startTime;
        public final boolean totalConc;
        public final boolean sunStyle;
        private final List<PathAnchor> pathHistory = new ArrayList<>(MAX_PATH_ANCHORS);
        private double lastPathSampleTime = -1.0;
        private Vec3 fallbackPos, fallbackDir;

        public boolean finaleLocked = false;
        private double finaleStartTime = 0.0;
        private double finaleDuration = 3.0;
        private Vec3 finaleStartHead = Vec3.ZERO;
        private Vec3 finaleControlPt = Vec3.ZERO;
        private Vec3 finaleTarget = Vec3.ZERO;

        public int age = 0;

        public void extendTracking(int extraTicks) {
            this.rushTicks += extraTicks;
            this.maxAge += extraTicks;
        }

        /** 生生流转每转一圈，水龙实时成长：体长、体魄与气势逐圈飙升（1 -> 2 -> 3 -> 4 圈）！ */
        public void upgradeStage(int newStage, int extraTicks) {
            this.tier = Math.max(this.tier, Mth.clamp(newStage, 1, 4));
            this.rushTicks = Math.max(this.rushTicks, this.age + extraTicks);
            this.maxAge = Math.max(this.maxAge, this.age + extraTicks + 14);
        }

        public float getStageScale() {
            return switch (Mth.clamp(tier, 1, 4)) {
                case 1 -> 0.72F;
                case 2 -> 0.90F;
                case 3 -> 1.04F;
                default -> 1.16F;
            };
        }

        public WaterDragonVfx(
                int entityId, double x, double y, double z, float yawDeg, float pitchDeg,
                int tier, int rushTicks, int maxAge, double startTime, boolean totalConc, boolean sunStyle
        ) {
            this.entityId = entityId;
            this.tier = tier;
            this.rushTicks = rushTicks;
            this.maxAge = maxAge;
            this.startTime = startTime;
            this.totalConc = totalConc;
            this.sunStyle = sunStyle;
            this.fallbackPos = new Vec3(x, y, z);
            this.fallbackDir = Vec3.directionFromRotation(pitchDeg * 0.50F, yawDeg).normalize();
            Vec3 right = horizontalRight(this.fallbackDir);
            // 预埋施法者起手身后的 12 节历史轨，令巨龙从玩家背后破空昂首腾出、绝不顶在鼻尖！
            for (int i = 11; i >= 0; i--) {
                double t = startTime - i * 0.55;
                Vec3 pos = fallbackPos.subtract(fallbackDir.scale(i * 0.62));
                pathHistory.add(new PathAnchor(pos, fallbackDir, right, t));
            }
            lastPathSampleTime = startTime;
        }

        public void sampleEntityPathAtRenderFrame(ClientLevel level, double nowTime, float pt) {
            if (finaleLocked || (nowTime - startTime) > rushTicks + 1.5) return;
            if (nowTime <= lastPathSampleTime + 0.04) return;

            Entity caster = level.getEntity(entityId);
            Vec3 pos;
            Vec3 fwd;
            if (caster != null) {
                pos = new Vec3(
                        Mth.lerp(pt, caster.xo, caster.getX()),
                        Mth.lerp(pt, caster.yo, caster.getY()) + 1.02,
                        Mth.lerp(pt, caster.zo, caster.getZ())
                );
                Vec3 look = caster.getViewVector(pt);
                fwd = look.lengthSqr() > 1.0E-4 ? look.normalize() : fallbackDir;
                fallbackPos = pos;
                fallbackDir = fwd;
            } else {
                double dt = nowTime - lastPathSampleTime;
                fallbackPos = fallbackPos.add(fallbackDir.scale(1.15 * dt));
                pos = fallbackPos;
                fwd = fallbackDir;
            }
            pathHistory.add(new PathAnchor(pos, fwd, horizontalRight(fwd), nowTime));
            lastPathSampleTime = nowTime;
            while (pathHistory.size() > MAX_PATH_ANCHORS) {
                pathHistory.remove(0);
            }
        }

        public void lockFinaleTarget(Vec3 target, float delayTicks, double nowTime) {
            if (finaleLocked) return;
            finaleLocked = true;
            finaleStartTime = nowTime;
            finaleDuration = Math.max(1.8, delayTicks);
            finaleTarget = target;
            finaleStartHead = evalDelayedSpinePoint(nowTime - WAKE_LAG_TICKS, 0);

            // 脱刃交棒弧：追击巨龙贴着剑士外肩侧弧咆哮超车，2.4 tick 精准汇入刀尖前方的 15 格犁地龙道起点！
            PathAnchor latest = pathHistory.get(pathHistory.size() - 1);
            double coilSign = Math.cos((nowTime - WAKE_LAG_TICKS) * 0.66) >= 0.0 ? 1.0 : -1.0;
            Vec3 mid = finaleStartHead.lerp(target, 0.52);
            finaleControlPt = mid.add(latest.right.scale(coilSign * 2.25)).add(0.0, 0.45, 0.0);
        }

        public void stepWithEntity(ClientLevel level, double nowTime, RandomSource rand) {
            if (finaleLocked && (nowTime - finaleStartTime) > finaleDuration + 1.0) {
                return;
            }
            if (age > rushTicks + 4) {
                return;
            }
            Vec3[] spine = buildSmoothSpine(nowTime);
            if (spine.length >= 2) {
                Vec3 head = spine[0];
                // 粒子发射在延迟后的龙首位置（玩家身侧后方），干净飘逸且不刷脏土块！
                spawnBurstStreaks(head.x, head.y, head.z, 1.15, 8, 0.45, sunStyle, rand);
                spawnRingBurstUkiyoFoam(head.x, head.y, head.z, 0.45, 1.25, 3, 0.28, 1.28F + tier * 0.12F, sunStyle, rand);
            }
        }

        public float getEffectiveLifeNorm(double nowTime) {
            if (finaleLocked) {
                double afterImpact = (nowTime - finaleStartTime) - finaleDuration;
                if (afterImpact <= 0.0) return 0.35F;
                // 巨龙一头撞进终结爆心后在 3.5 tick 内顺势钻没融化进大爆发！
                return Mth.clamp(0.72F + (float) (afterImpact / 3.6) * 0.28F, 0.35F, 1.0F);
            }
            return Mth.clamp((float) ((nowTime - startTime) / Math.max(1.0, maxAge)), 0.0F, 1.0F);
        }

        public Vec3[] buildSmoothSpine(double nowTime) {
            if (pathHistory.size() < 2) {
                return new Vec3[] {fallbackPos, fallbackPos.subtract(fallbackDir)};
            }
            Vec3[] spine = new Vec3[SPINE_LEN];
            if (!finaleLocked) {
                double headTime = nowTime - WAKE_LAG_TICKS;
                for (int i = 0; i < SPINE_LEN; i++) {
                    double sTime = headTime - i * 0.34;
                    spine[i] = evalDelayedSpinePoint(sTime, i);
                }
            } else {
                double rawU = (nowTime - finaleStartTime) / Math.max(0.5, finaleDuration);
                // 冲刺收尾：前半段加速扑出超车，精准砸进终点裂点！
                double headU = rawU <= 1.0 ? Math.pow(Mth.clamp(rawU, 0.0, 1.0), 1.15) : (1.0 + (rawU - 1.0) * 0.35);
                for (int i = 0; i < SPINE_LEN; i++) {
                    double u = headU - i * 0.048;
                    if (u >= 0.0) {
                        double cu = Mth.clamp(u, 0.0, 1.0);
                        Vec3 basePt = evalQuadBezier(finaleStartHead, finaleControlPt, finaleTarget, cu);
                        // 越接近撞击终点，龙身越紧收直插爆心！
                        double env = Math.sin(cu * Math.PI);
                        double sway = Math.sin(cu * 9.0 - nowTime * 0.55) * 0.35 * env;
                        spine[i] = basePt.add(0.0, sway, 0.0);
                    } else {
                        // u < 0：无缝衔接回冲锋时的延迟历史龙躯！
                        double backTime = (finaleStartTime - WAKE_LAG_TICKS) + u * 7.2;
                        spine[i] = evalDelayedSpinePoint(backTime, i);
                    }
                }
            }
            return spine;
        }

        private Vec3 evalDelayedSpinePoint(double queryTime, int segIdx) {
            PathAnchor a = sampleAnchorAt(queryTime);
            double u = segIdx / (double) (SPINE_LEN - 1);
            double coilAngle = queryTime * 0.66;
            // 外侧盘旋游动（偏向剑士左右外肩与略低位，视觉气势磅礴且绝不跨过第一人称准星正中！）
            double coilR = (1.38 + tier * 0.16) * (0.92 - 0.16 * u);
            double sideOff = Math.cos(coilAngle) * coilR;
            double vertOff = Math.sin(coilAngle) * (0.58 + tier * 0.06) - 0.22;
            return a.pos.add(a.right.scale(sideOff)).add(0.0, vertOff, 0.0);
        }

        private PathAnchor sampleAnchorAt(double queryTime) {
            int size = pathHistory.size();
            PathAnchor first = pathHistory.get(0);
            if (queryTime <= first.time) {
                double dt = first.time - queryTime;
                return new PathAnchor(first.pos.subtract(first.fwd.scale(dt * 0.95)), first.fwd, first.right, queryTime);
            }
            PathAnchor last = pathHistory.get(size - 1);
            if (queryTime >= last.time) {
                return last;
            }
            // 二分查找对应时间区间做精确线性插值（144Hz 零跳变！）
            int lo = 0, hi = size - 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (pathHistory.get(mid).time <= queryTime) lo = mid;
                else hi = mid;
            }
            PathAnchor p0 = pathHistory.get(lo);
            PathAnchor p1 = pathHistory.get(hi);
            double span = Math.max(1.0E-5, p1.time - p0.time);
            double f = Mth.clamp((queryTime - p0.time) / span, 0.0, 1.0);
            Vec3 pos = p0.pos.lerp(p1.pos, f);
            Vec3 fwd = p0.fwd.lerp(p1.fwd, f);
            fwd = fwd.lengthSqr() > 1.0E-5 ? fwd.normalize() : p0.fwd;
            return new PathAnchor(pos, fwd, horizontalRight(fwd), queryTime);
        }

        private static Vec3 evalQuadBezier(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
            double inv = 1.0 - t;
            return p0.scale(inv * inv).add(p1.scale(2.0 * inv * t)).add(p2.scale(t * t));
        }

        private static Vec3 horizontalRight(Vec3 fwd) {
            Vec3 r = new Vec3(-fwd.z, 0.0, fwd.x);
            return r.lengthSqr() > 1.0E-4 ? r.normalize() : new Vec3(1.0, 0.0, 0.0);
        }

        @Override
        public boolean tick() { return ++age < maxAge; }

        private record PathAnchor(Vec3 pos, Vec3 fwd, Vec3 right, double time) {}
    }

    /**
     * 思路 2 · 人刀龙一体落点终结「修长东方神龙 480° 贴地外圈大回环甩尾！」（零发射、零柱子、零脏泥块！）
     * - 剑士连人带刀带龙腾空重劈入地，修长东方神龙在刀锋落点外圈（半径 4.6~6.0 格，中心全空不挡剑士视野！）
     *   于平视黄金低空（1.0~3.6 格）霸气盘旋甩尾一整圈半，最后微微昂首舒展化作漫天细腻水花/金红余烬！
     */
    public static final class SunDragonFinaleVfx implements TickableVfx {
        public static final int SPINE_SEGMENTS = 38;
        public static final float DRAGON_START_TICK = 0.0F;
        public static final float DRAGON_END_TICK = 28.0F;

        public final double x, y, z;
        public final float yawDeg, radius;
        public final int tier, maxAge;
        public final boolean totalConc;
        public final boolean sunStyle;
        public int age = 0;

        public SunDragonFinaleVfx(
                double x, double y, double z, float yawDeg, float radius,
                int tier, int maxAge, boolean totalConc, boolean sunStyle
        ) {
            this.x = x; this.y = y; this.z = z;
            this.yawDeg = yawDeg; this.radius = radius;
            this.tier = tier; this.maxAge = maxAge; this.totalConc = totalConc; this.sunStyle = sunStyle;
        }

        public void stepEffects(ClientLevel level, RandomSource rand, Minecraft mc) {
            if (age >= 1 && age <= 24) {
                // 修长东方神龙绕剑士外圈盘旋甩尾途中，沿龙头甩出轻盈浮世绘浪花/火舌与高速火星雨
                Vec3 head = computeRingDragonHead(x, y, z, yawDeg, radius, age + 0.5F);
                spawnBurstStreaks(head.x, head.y, head.z, 1.35, 9, 0.52, sunStyle, rand);
                if (age % 2 == 0) {
                    spawnBurstUkiyoFoam(head.x, head.y, head.z, 1.25, 3, 0.30, 1.32F + tier * 0.10F, sunStyle, rand);
                }
            } else if (age > 24 && age < maxAge - 6 && age % 3 == 0) {
                // 地面神龙图腾 / 沧海神纹战痕余韵：缓缓升起稀疏优雅的金红余烬 / 水芒
                spawnBurstStreaks(x, y + 0.30, z, radius * 0.90, 7, 0.14, sunStyle, rand);
            }
        }

        public static Vec3 computeRingDragonHead(
                double cx, double cy, double cz, float yawDeg, float radius, float ageTicks
        ) {
            float u = Mth.clamp(ageTicks / 28.0F, 0.0F, 1.0F);
            // 前快后稳的凌厉甩尾回环节奏！
            float headProg = 1.0F - (float) Math.pow(1.0F - u, 1.85) + Math.max(0.0F, (ageTicks - 28.0F) * 0.015F);
            double baseYawRad = (yawDeg + 90.0F) * Mth.DEG_TO_RAD;
            return evalRingDragonPoint(cx, cy, cz, baseYawRad, radius, ageTicks, headProg);
        }

        public static Vec3[] buildRingDragonSpine(
                double cx, double cy, double cz, float yawDeg, float radius, float ageTicks
        ) {
            if (ageTicks < DRAGON_START_TICK || ageTicks >= DRAGON_END_TICK) {
                return new Vec3[0];
            }
            float u = Mth.clamp(ageTicks / 28.0F, 0.0F, 1.0F);
            float headProg = 1.0F - (float) Math.pow(1.0F - u, 1.85) + Math.max(0.0F, (ageTicks - 28.0F) * 0.015F);
            // 极其修长优美的东方长龙身躯弧长（约 250° 环绕舒展！）
            float bodySpan = Mth.clamp(headProg * 0.85F + 0.24F, 0.24F, 0.62F);
            double baseYawRad = (yawDeg + 90.0F) * Mth.DEG_TO_RAD;

            Vec3[] spine = new Vec3[SPINE_SEGMENTS];
            for (int i = 0; i < SPINE_SEGMENTS; i++) {
                float frac = i / (float) (SPINE_SEGMENTS - 1);
                float s = headProg - frac * bodySpan;
                spine[i] = evalRingDragonPoint(cx, cy, cz, baseYawRad, radius, ageTicks, s);
            }
            return spine;
        }

        /**
         * 144Hz 解析修长东方神龙外圈大回环：
         * 起手从剑士刀锋劈落点贴地腾出 -> 绕剑士外圈（半径 0.72~0.94*radius，中心全空通透！）低空盘旋 480° -> 末尾优雅微昂首收锋！
         */
        private static Vec3 evalRingDragonPoint(
                double cx, double cy, double cz, double baseYawRad, float radius, float ageTicks, float s
        ) {
            float sClamped = Math.max(0.0F, s);
            // 环绕半径：始终稳在外圈 4.5~6.0 格（绝不糊剑士中心视野，平视极其大气！）
            double baseR = radius * Mth.lerp(Mth.clamp(sClamped * 1.4F, 0.0F, 1.0F), 0.56F, 0.88F);
            double waveR = 0.42 * Math.sin(s * 14.0 - ageTicks * 0.34);
            double r = baseR + waveR;
            // 旋转角：顺势沿剑士横扫大回环扫出 480°（约 1.33 整圈）
            double theta = baseYawRad - 0.55 + s * Mth.TWO_PI * 1.33;
            // 高度：全程锁在平视黄金低空 1.1 ~ 3.4 格，末段微微优雅昂首至 5.2 格！
            double lift = 1.05 + 1.65 * Math.sin(Mth.clamp(sClamped, 0.0F, 1.0F) * Mth.PI * 0.85)
                    + (sClamped > 0.72F ? Math.pow((sClamped - 0.72F) / 0.28F, 1.5) * 2.6 : 0.0);
            double waveY = 0.32 * Math.cos(s * 12.0 - ageTicks * 0.28);
            return new Vec3(
                    cx + Math.cos(theta) * r,
                    cy + Math.max(0.45, lift + waveY),
                    cz + Math.sin(theta) * r
            );
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    public static final class UkiyoFoamSprite implements TickableVfx {
        public double x, y, z, prevX, prevY, prevZ;
        public double vx, vy, vz;
        public final int tile;
        public final float baseScale;
        public float rollDeg, prevRollDeg;
        public final float vRollDeg;
        public final int maxAge;
        public final boolean sunStyle;
        public int age = 0;

        public UkiyoFoamSprite(
                double x, double y, double z, double vx, double vy, double vz,
                int tile, float baseScale, float rollDeg, float vRollDeg, int maxAge, boolean sunStyle
        ) {
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.tile = tile; this.baseScale = baseScale;
            this.rollDeg = rollDeg; this.prevRollDeg = rollDeg;
            this.vRollDeg = vRollDeg; this.maxAge = maxAge;
            this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() {
            prevX = x; prevY = y; prevZ = z;
            prevRollDeg = rollDeg;
            x += vx; y += vy; z += vz;
            // 水花受重力回落，日之呼吸烈焰火舌则热浪微升！
            vy += sunStyle ? 0.010 : -0.020;
            vx *= 0.90; vz *= 0.90;
            rollDeg += vRollDeg;
            return ++age < maxAge;
        }
    }

    public static final class WaterWhirlpoolVfx implements TickableVfx {
        public final int entityId;
        public double x, y, z, prevX, prevY, prevZ;
        public final float radius, height;
        public final int maxAge;
        public final boolean boosted;
        public final boolean sunStyle;
        public int age = 0;

        public WaterWhirlpoolVfx(int entityId, double x, double y, double z, float radius, float height, int maxAge, boolean boosted, boolean sunStyle) {
            this.entityId = entityId;
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.radius = radius; this.height = height; this.maxAge = maxAge;
            this.boosted = boosted; this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    public static final class WaterfallPillarVfx implements TickableVfx {
        public final double x, y, z;
        public final float radius, height, delayTicks;
        public final int maxAge;
        public final boolean totalConc;
        public final boolean sunStyle;
        public int age = 0;

        public WaterfallPillarVfx(double x, double y, double z, float radius, float height, float delayTicks, int maxAge, boolean totalConc, boolean sunStyle) {
            this.x = x; this.y = y; this.z = z;
            this.radius = radius; this.height = height; this.delayTicks = delayTicks; this.maxAge = maxAge;
            this.totalConc = totalConc; this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    public static final class DeadCalmMirrorVfx implements TickableVfx {
        public final int entityId;
        public double x, y, z, prevX, prevY, prevZ;
        public final float radius;
        public final int maxAge;
        public final boolean totalConc;
        public final boolean sunStyle;
        public int age = 0;

        public DeadCalmMirrorVfx(int entityId, double x, double y, double z, float radius, int maxAge, boolean totalConc, boolean sunStyle) {
            this.entityId = entityId;
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.radius = radius; this.maxAge = maxAge;
            this.totalConc = totalConc; this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    public static final class WaterRiftSlashVfx implements TickableVfx {
        public final double x, y, z;
        public final float yawDeg, pitchDeg, length;
        public final int maxAge;
        public final boolean heavy;
        public final boolean sunStyle;
        public int age = 0;

        public WaterRiftSlashVfx(double x, double y, double z, float yawDeg, float pitchDeg, float length, int maxAge, boolean heavy, boolean sunStyle) {
            this.x = x; this.y = y; this.z = z;
            this.yawDeg = yawDeg; this.pitchDeg = pitchDeg; this.length = length;
            this.maxAge = maxAge; this.heavy = heavy; this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    public static final class ChargeAuraVfx implements TickableVfx {
        public final int entityId;
        public double x, y, z, prevX, prevY, prevZ;
        public final float radius;
        public final int mode;
        public final int maxAge;
        public final boolean totalConc;
        public final boolean sunStyle;
        public int age = 0;

        public ChargeAuraVfx(int entityId, double x, double y, double z, float radius, int mode, int maxAge, boolean totalConc, boolean sunStyle) {
            this.entityId = entityId;
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.radius = radius; this.mode = mode; this.maxAge = maxAge;
            this.totalConc = totalConc; this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }


    /** 沿速度矢量高速拉伸的发光细水针 / 炽热上升火星丝 */
    public static final class VelocityStreakVfx implements TickableVfx {
        public double x, y, z, prevX, prevY, prevZ;
        public double vx, vy, vz;
        public final float lengthScale, widthScale;
        public final int maxAge;
        public final boolean sunStyle, hotWhite;
        public int age = 0;

        public VelocityStreakVfx(
                double x, double y, double z, double vx, double vy, double vz,
                float lengthScale, float widthScale, int maxAge, boolean sunStyle, boolean hotWhite
        ) {
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.lengthScale = lengthScale; this.widthScale = widthScale;
            this.maxAge = maxAge; this.sunStyle = sunStyle; this.hotWhite = hotWhite;
        }

        @Override
        public boolean tick() {
            prevX = x; prevY = y; prevZ = z;
            x += vx; y += vy; z += vz;
            if (sunStyle) {
                // 日之呼吸火星：热浪微微上飘并带自然火流扰动
                vy = vy * 0.93 + 0.015;
                vx = vx * 0.92 + Math.sin(age * 0.7 + x) * 0.008;
                vz = vz * 0.92 + Math.cos(age * 0.7 + z) * 0.008;
            } else {
                // 水之呼吸细水针：潇洒抛物飞溅
                vy -= 0.024;
                vx *= 0.93;
                vz *= 0.93;
            }
            return ++age < maxAge;
        }
    }

    public static final class FlyingBlockDebris implements TickableVfx {
        public final BlockState blockState;
        public final BlockPos seedPos;
        public double x, y, z, prevX, prevY, prevZ;
        public double vx, vy, vz;
        public float rotX, rotY, rotZ, prevRotX, prevRotY, prevRotZ;
        public final float vRotX, vRotY, vRotZ, baseScale;
        public final int maxAge;
        public int age = 0;

        public FlyingBlockDebris(BlockState state, BlockPos seedPos, double x, double y, double z,
                                 double vx, double vy, double vz, float baseScale, int maxAge, RandomSource rand) {
            this.blockState = state; this.seedPos = seedPos;
            this.x = x; this.y = y; this.z = z;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.baseScale = baseScale; this.maxAge = maxAge;
            this.rotX = rand.nextFloat() * 360.0F;
            this.rotY = rand.nextFloat() * 360.0F;
            this.rotZ = rand.nextFloat() * 360.0F;
            this.prevRotX = rotX; this.prevRotY = rotY; this.prevRotZ = rotZ;
            this.vRotX = (rand.nextFloat() - 0.5F) * 26.0F;
            this.vRotY = (rand.nextFloat() - 0.5F) * 26.0F;
            this.vRotZ = (rand.nextFloat() - 0.5F) * 26.0F;
        }

        @Override
        public boolean tick() {
            prevX = x; prevY = y; prevZ = z;
            prevRotX = rotX; prevRotY = rotY; prevRotZ = rotZ;
            x += vx; y += vy; z += vz;
            vy -= 0.068;
            vx *= 0.97; vz *= 0.97;
            rotX += vRotX; rotY += vRotY; rotZ += vRotZ;
            return ++age < maxAge;
        }
    }

    public static final class GroundUpheavalBlock implements TickableVfx {
        public final BlockState blockState;
        public final BlockPos seedPos;
        public final double baseX, baseY, baseZ;
        public final float dirX, dirZ, liftHeight, maxTiltDeg;
        public final int delay, maxAge;
        public int age = 0;

        public GroundUpheavalBlock(BlockState state, BlockPos seedPos, double baseX, double baseY, double baseZ,
                                   float dirX, float dirZ, float liftHeight, float maxTiltDeg, int delay, int maxAge) {
            this.blockState = state; this.seedPos = seedPos;
            this.baseX = baseX; this.baseY = baseY; this.baseZ = baseZ;
            this.dirX = dirX; this.dirZ = dirZ; this.liftHeight = liftHeight; this.maxTiltDeg = maxTiltDeg;
            this.delay = delay; this.maxAge = maxAge;
        }

        public float getElevationFactor(float partialTick) {
            float t = (age + partialTick) - delay;
            if (t <= 0.0F) return 0.0F;
            float norm = t / (float) maxAge;
            if (norm >= 1.0F) return 0.0F;
            if (norm < 0.25F) {
                float u = norm / 0.25F;
                return 1.0F - (1.0F - u) * (1.0F - u);
            }
            float u = (norm - 0.25F) / 0.75F;
            return (1.0F - u) * (1.0F - u);
        }

        @Override
        public boolean tick() { return ++age < delay + maxAge; }
    }

    public static final class OrbitingBlockDebris implements TickableVfx {
        public final BlockState blockState;
        public final BlockPos seedPos;
        public final double centerX, centerY, centerZ;
        public final float orbitRadius, maxRise, baseScale;
        public double angle;
        public final double angularSpeed;
        public double x, y, z, prevX, prevY, prevZ;
        public float rotX, rotY, rotZ, prevRotX, prevRotY, prevRotZ;
        public final float vRotX, vRotY, vRotZ;
        public final int maxAge;
        public int age = 0;

        public OrbitingBlockDebris(BlockState state, BlockPos seedPos, double cx, double cy, double cz,
                                   float orbitRadius, double startAngle, double angularSpeed, float maxRise,
                                   float baseScale, int maxAge, RandomSource rand) {
            this.blockState = state; this.seedPos = seedPos;
            this.centerX = cx; this.centerY = cy; this.centerZ = cz;
            this.orbitRadius = orbitRadius; this.angle = startAngle; this.angularSpeed = angularSpeed;
            this.maxRise = maxRise; this.baseScale = baseScale; this.maxAge = maxAge;
            this.x = cx + Math.cos(startAngle) * orbitRadius;
            this.y = cy;
            this.z = cz + Math.sin(startAngle) * orbitRadius;
            this.prevX = x; this.prevY = y; this.prevZ = z;
            this.rotX = rand.nextFloat() * 360.0F;
            this.rotY = rand.nextFloat() * 360.0F;
            this.rotZ = rand.nextFloat() * 360.0F;
            this.prevRotX = rotX; this.prevRotY = rotY; this.prevRotZ = rotZ;
            this.vRotX = (rand.nextFloat() - 0.5F) * 18.0F;
            this.vRotY = (rand.nextFloat() - 0.5F) * 20.0F;
            this.vRotZ = (rand.nextFloat() - 0.5F) * 18.0F;
        }

        @Override
        public boolean tick() {
            prevX = x; prevY = y; prevZ = z;
            prevRotX = rotX; prevRotY = rotY; prevRotZ = rotZ;
            age++;
            float p = Mth.clamp(age / (float) maxAge, 0.0F, 1.0F);
            angle += angularSpeed * (1.0 + p * 0.55);
            double r = orbitRadius * (0.85 + 0.35 * p);
            x = centerX + Math.cos(angle) * r;
            y = centerY + maxRise * p;
            z = centerZ + Math.sin(angle) * r;
            rotX += vRotX; rotY += vRotY; rotZ += vRotZ;
            return age < maxAge;
        }
    }

    /** 招式起势蓄力 -> 瞬发爆发时刻的客户端延时节拍回调 */
    private static final class DelayedCue {
        int ticksLeft;
        final Runnable action;

        DelayedCue(int delayTicks, Runnable action) {
            this.ticksLeft = Math.max(1, delayTicks);
            this.action = action;
        }
    }

    /** 「斩断隙之线」后，丝线从中点轰然绷断并向两端卷曲飞散的 3D 光丝特效 */
    public static final class ThreadSnapVfx implements TickableVfx {
        public final Vec3 start, end;
        public final int maxAge;
        public final boolean sunStyle;
        public int age = 0;

        public ThreadSnapVfx(Vec3 start, Vec3 end, int maxAge, boolean sunStyle) {
            this.start = start;
            this.end = end;
            this.maxAge = Math.max(6, maxAge);
            this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }

    /**
     * 3D 定向同心水波纹冲击环：
     * - mode 0：柒之型·雫波纹突刺（垂直于刺击矢量展开的 3 层立体同心水滴波纹！）
     * - mode 1：水平贴地水压爆裂环（泷壶砸潭 / 生生流转高压水爆 / 扭转漩涡收尾 / 肆之型打潮拍岸 / 玖之型水流飞沫踏水）
     * - mode 2：伍之型·乾天的慈雨（轻柔银青浮空细雨同心涟漪！）
     */
    public static final class RippleRingVfx implements TickableVfx {
        public final double x, y, z;
        public final float yawDeg, pitchDeg, maxRadius;
        public final int mode, maxAge;
        public final boolean sunStyle;
        public int age = 0;

        public RippleRingVfx(
                double x, double y, double z, float yawDeg, float pitchDeg,
                float maxRadius, int mode, int maxAge, boolean sunStyle
        ) {
            this.x = x; this.y = y; this.z = z;
            this.yawDeg = yawDeg; this.pitchDeg = pitchDeg;
            this.maxRadius = maxRadius; this.mode = mode;
            this.maxAge = Math.max(8, maxAge); this.sunStyle = sunStyle;
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }

    /** 日·幻日虹 原地 3D 炽热虹光残像剪影特效 */
    public static final class SunAfterimageVfx implements TickableVfx {
        public final double x, y, z;
        public final float facingYaw, evadeYaw, scale;
        public final int maxAge;
        public int age = 0;

        public SunAfterimageVfx(double x, double y, double z, float facingYaw, float evadeYaw, float scale, int maxAge) {
            this.x = x; this.y = y; this.z = z;
            this.facingYaw = facingYaw; this.evadeYaw = evadeYaw;
            this.scale = scale; this.maxAge = Math.max(12, maxAge);
        }

        @Override
        public boolean tick() { return ++age < maxAge; }
    }
}
