package com.nichirin.client.anim;

import com.nichirin.breathing.BladeKinematics;
import com.nichirin.client.ClientBreathState;
import com.nichirin.client.input.ComboInputController;
import com.nichirin.item.WaterNichirinBladeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一剑术动作编排引擎（驱动第一人称日轮刀动作、第三人称人物翻滚/旋身动作，以及实时 3D 刀锋世界坐标采样）
 * 保证「刀走到哪，浮世绘刀光就划到哪」，且发射类招式人刀一体协同运动。
 */
public final class SwordChoreography {
    public static final int ANIM_NONE = 0;
    public static final int ANIM_SURFACE_SLASH = 1;
    public static final int ANIM_WATER_WHEEL = 2;
    public static final int ANIM_LATERAL_WHEEL = 3;
    public static final int ANIM_FLOWING_DANCE = 4;
    public static final int ANIM_WHIRLPOOL = 5;
    public static final int ANIM_WATERFALL_DIVE = 6;
    public static final int ANIM_WATERFALL_IMPACT = 7;
    public static final int ANIM_CONSTANT_FLUX = 8;
    public static final int ANIM_DEAD_CALM = 9;
    public static final int ANIM_FINALE_IAI_FLASH = 10;
    public static final int ANIM_DRAGON_LEAP_CLEAVE = 11;
    public static final int ANIM_STRIKING_TIDE = 12;
    public static final int ANIM_BLESSED_RAIN = 13;
    public static final int ANIM_DROP_RIPPLE_THRUST = 14;
    public static final int ANIM_SPLASH_FLOW = 15;
    public static final int ANIM_WATERFALL_CLEAVE = 16;
    public static final int ANIM_SUN_CLEAR_BLUE_SKY = 17;
    public static final int ANIM_SUN_RAGING_SUN = 18;
    public static final int ANIM_SUN_SETTING_SUN = 19;
    public static final int ANIM_SUN_HEAT_HAZE = 20;
    public static final int ANIM_SUN_FLAME_DANCE = 21;
    public static final int ANIM_SUN_HALO_DRAGON_DASH = 22;
    public static final int ANIM_SUN_DANCING_FLASH = 23;

    private static final Map<Integer, PlayerSwordAnim> ANIMS = new ConcurrentHashMap<>();

    private SwordChoreography() {}

    public static PlayerSwordAnim getOrCreate(int entityId) {
        return ANIMS.computeIfAbsent(entityId, k -> new PlayerSwordAnim());
    }

    public static PlayerSwordAnim get(int entityId) {
        return ANIMS.get(entityId);
    }

    public static void startAnim(int entityId, int animType, int durationTicks, int paramInt, boolean totalConc, boolean sunStyle) {
        PlayerSwordAnim anim = getOrCreate(entityId);
        anim.animType = animType;
        anim.prevAnimTime = 0.0F;
        anim.animTime = 0.0F;
        anim.duration = Math.max(6, durationTicks);
        anim.paramInt = paramInt;
        anim.totalConc = totalConc;
        anim.sunStyle = sunStyle;
        anim.chargingMode = 0;
        anim.hitstopTicks = 0.0F;
        anim.hitstopIntensity = 0.0F;
    }

    /** 斩中敌人瞬间的「顿刀卡肉」：把剑术与刀光带平滑压慢至 14% 速并附带刀身咬合微震（144Hz 零跳帧！） */
    public static void triggerHitstop(int entityId, float ticks, float intensity) {
        PlayerSwordAnim anim = getOrCreate(entityId);
        anim.hitstopTicks = Math.max(anim.hitstopTicks, ticks);
        anim.hitstopIntensity = Math.max(anim.hitstopIntensity, intensity);
    }

    public static void setCharging(int entityId, int chargeMode, int durationTicks) {
        PlayerSwordAnim anim = getOrCreate(entityId);
        anim.chargingMode = chargeMode;
        anim.chargeAge = 0;
        anim.chargeDuration = Math.max(10, durationTicks);
    }

    public static void triggerParrySnap(int entityId, boolean sunStyle) {
        PlayerSwordAnim anim = getOrCreate(entityId);
        anim.prevParrySnapTicks = 6;
        anim.parrySnapTicks = 6;
        anim.parrySnapMax = 6;
        anim.sunStyle = sunStyle;
        anim.swingAlt = !anim.swingAlt;
    }

    public static void triggerComboFlourish(int entityId) {
        PlayerSwordAnim anim = getOrCreate(entityId);
        anim.comboFlourishTicks = 6;
        anim.swingAlt = !anim.swingAlt;
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        int localId = mc.player != null ? mc.player.getId() : -1;

        for (Iterator<Map.Entry<Integer, PlayerSwordAnim>> it = ANIMS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, PlayerSwordAnim> entry = it.next();
            int id = entry.getKey();
            PlayerSwordAnim a = entry.getValue();

            if (a.animType != ANIM_NONE) {
                a.prevAnimTime = a.animTime;
                if (a.prevAnimTime >= a.duration) {
                    a.animType = ANIM_NONE;
                    a.prevAnimTime = 0.0F;
                    a.animTime = 0.0F;
                } else {
                    // 命中卡肉期间刀势降至 14% 极慢咬合推进，松开后豁然贯通斩出，手感丝滑无比！
                    float step = a.hitstopTicks > 0.0F ? 0.14F : 1.0F;
                    a.animTime = Math.min((float) a.duration, a.animTime + step);
                }
            }
            if (a.hitstopTicks > 0.0F) {
                a.hitstopTicks = Math.max(0.0F, a.hitstopTicks - 1.0F);
                if (a.hitstopTicks <= 0.0F) {
                    a.hitstopIntensity = 0.0F;
                }
            }
            a.prevParrySnapTicks = a.parrySnapTicks;
            if (a.parrySnapTicks > 0) a.parrySnapTicks--;
            if (a.comboFlourishTicks > 0) a.comboFlourishTicks--;

            // 本地玩家实时同步长按蓄力进度
            if (id == localId) {
                int localCharge = ComboInputController.getChargingMode();
                a.chargingMode = localCharge;
                if (localCharge != 0) {
                    a.chargeAge++;
                } else {
                    a.chargeAge = 0;
                }
            } else if (a.chargingMode != 0) {
                a.chargeAge++;
                if (a.chargeAge >= a.chargeDuration) {
                    a.chargingMode = 0;
                    a.chargeAge = 0;
                }
            }

            // 清理长时间无任何状态的远端实体条目
            if (id != localId && a.animType == ANIM_NONE && a.chargingMode == 0
                    && a.parrySnapTicks == 0 && a.comboFlourishTicks == 0 && a.hitstopTicks <= 0.0F) {
                it.remove();
            }
        }
    }

    public static void clearAll() {
        ANIMS.clear();
    }

    // ==================== 1. 第一人称日轮刀姿态演算 ====================

    public static FirstPersonPose computeFirstPersonPose(
            Player player, float partialTick, float vanillaSwing, float equipProgress
    ) {
        PlayerSwordAnim a = get(player.getId());
        float time = (player.tickCount + partialTick) * 0.14F;
        boolean tc = ClientBreathState.isTotalConcentration();
        boolean inhaling = ClientBreathState.isInhaling();

        // 默认待机姿态：帅气的日式正眼/中段持刀
        float tx = 0.0F;
        float ty = Mth.sin(time * (tc ? 1.5F : 0.9F)) * 0.015F - equipProgress * 0.42F;
        float tz = 0.0F;
        float rx = -6.0F + Mth.sin(time * 0.8F) * 1.4F;
        float ry = -8.0F;
        float rz = 4.0F;

        // 吸气深呼吸架势：双臂微收、刀刃立于眼前聚息
        if (inhaling) {
            tx = -0.18F;
            ty = -0.06F + Mth.sin(time * 3.0F) * 0.025F;
            tz = 0.10F;
            rx = -22.0F;
            ry = 24.0F;
            rz = 18.0F;
        }

        // 长按蓄力架势
        if (a != null && a.chargingMode != 0) {
            float prog = ComboInputController.getChargeProgress();
            float vib = Mth.sin((player.tickCount + partialTick) * 2.8F) * 0.012F * prog;
            if (a.chargingMode == 1) {
                // 生生流转：压低腰际向右后拉弓居合蓄力，刀身微颤蓄势待发！
                tx = 0.24F + prog * 0.12F + vib;
                ty = -0.26F - prog * 0.08F;
                tz = 0.24F + prog * 0.10F;
                rx = -62.0F - prog * 18.0F;
                ry = -54.0F - prog * 18.0F;
                rz = -26.0F;
            } else {
                // 拾壹之型·凪：沉静横刀置于胸前，止水不动
                tx = -0.26F;
                ty = -0.14F;
                tz = -0.08F;
                rx = -78.0F;
                ry = 48.0F;
                rz = 64.0F;
            }
            return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
        }

        // 凪弹反 / 幻日虹残影反杀：瞬间 0.15s 极速闪斩（优先级高于待机与静止架势）
        if (a != null && a.isParryActive()) {
            float su = a.getParryProgress(partialTick);
            float dir = a.swingAlt ? 1.0F : -1.0F;
            tx = Mth.lerp(su, 0.28F * dir, -0.64F * dir);
            ty = -0.12F;
            tz = -0.30F;
            rx = -86.0F;
            ry = Mth.lerp(su, -76.0F * dir, 96.0F * dir);
            rz = 58.0F * dir;
            return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
        }

        // 活跃剑型动作（起势蓄力 -> 非线性鞭击瞬发 -> 残心定格收刀 + 命中刀身卡肉微震！）
        if (a != null && a.animType != ANIM_NONE) {
            float t = a.getProgress(partialTick);
            float biteVib = a.hitstopTicks > 0.0F
                    ? Mth.sin((player.tickCount + partialTick) * 3.8F) * 0.022F * a.hitstopIntensity : 0.0F;
            switch (a.animType) {
                case ANIM_SURFACE_SLASH -> {
                    // 壹之型·水面斩 / 圆舞：前 20% 狠狠右拉贴腰蓄力 -> 中段鞭击极速横扫 250° -> 残心定格再收刀！
                    if (t < 0.20F) {
                        float u = smoothStep(t / 0.20F);
                        tx = 0.40F * u; ty = -0.24F * u; tz = 0.26F * u;
                        rx = -82.0F * u; ry = -96.0F * u; rz = -48.0F * u;
                    } else if (t < 0.62F) {
                        float u = whipCurve((t - 0.20F) / 0.42F);
                        tx = Mth.lerp(u, 0.40F, -0.82F) + biteVib;
                        ty = -0.18F + Mth.sin(u * Mth.PI) * 0.10F + biteVib * 0.6F;
                        tz = Mth.lerp(u, 0.26F, -0.36F) - Mth.sin(u * Mth.PI) * 0.24F;
                        rx = -84.0F;
                        ry = Mth.lerp(u, -96.0F, 114.0F);
                        rz = Mth.lerp(u, -48.0F, 72.0F);
                    } else if (t < 0.78F) {
                        // 残心定格：斩完稳稳刹停一瞬，力道感瞬间拉满
                        tx = -0.82F + biteVib; ty = -0.18F; tz = -0.36F;
                        rx = -84.0F; ry = 114.0F; rz = 72.0F;
                    } else {
                        float u = smoothStep((t - 0.78F) / 0.22F);
                        tx = Mth.lerp(u, -0.82F, 0.0F);
                        ty = Mth.lerp(u, -0.18F, 0.0F);
                        tz = Mth.lerp(u, -0.36F, 0.0F);
                        rx = Mth.lerp(u, -84.0F, -6.0F);
                        ry = Mth.lerp(u, 114.0F, -8.0F);
                        rz = Mth.lerp(u, 72.0F, 4.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_WATER_WHEEL -> {
                    // 贰之型·水车 / 碧罗之天：前 12% 屈膝压刀蓄势 -> 随后爆发加速纵翻 390° -> 落地收势
                    if (t < 0.12F) {
                        float u = smoothStep(t / 0.12F);
                        tx = -0.14F * u; ty = -0.26F * u; tz = 0.18F * u;
                        rx = 26.0F * u; ry = 14.0F * u; rz = -8.0F * u;
                    } else {
                        float spin = snapOut((t - 0.12F) / 0.88F) * 395.0F;
                        tx = -0.16F + biteVib;
                        ty = 0.08F + Mth.sin(spin * Mth.DEG_TO_RAD) * 0.28F;
                        tz = -0.22F + Mth.cos(spin * Mth.DEG_TO_RAD) * 0.22F;
                        rx = -spin;
                        ry = 12.0F;
                        rz = -10.0F;
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_LATERAL_WHEEL -> {
                    // 贰之型·改 横水车 / 烈日红镜：前 12% 空中缩身引刀 -> 随后极速横旋 410°
                    if (t < 0.12F) {
                        float u = smoothStep(t / 0.12F);
                        tx = 0.26F * u; ty = -0.18F * u; tz = 0.16F * u;
                        rx = -76.0F * u; ry = -62.0F * u; rz = -22.0F * u;
                    } else {
                        float spin = snapOut((t - 0.12F) / 0.88F) * 415.0F;
                        tx = -0.20F + Mth.cos(spin * Mth.DEG_TO_RAD) * 0.35F + biteVib;
                        ty = -0.12F;
                        tz = -0.25F;
                        rx = -84.0F;
                        ry = spin - 55.0F;
                        rz = 58.0F;
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_FLOWING_DANCE -> {
                    // 参之型·流流舞 / 炎舞：极短转髋蓄势 -> 鞭击闪电斜切 -> 干脆定格
                    float u = t < 0.10F ? 0.0F : whipCurve((t - 0.10F) / 0.78F);
                    int step = a.paramInt % 3;
                    if (step == 0) {
                        tx = Mth.lerp(u, -0.60F, 0.34F) + biteVib;
                        ty = Mth.lerp(u, -0.38F, 0.30F);
                        tz = -0.24F - Mth.sin(u * Mth.PI) * 0.18F;
                        rx = Mth.lerp(u, -24.0F, -110.0F);
                        ry = Mth.lerp(u, 82.0F, -68.0F);
                        rz = Mth.lerp(u, 48.0F, -42.0F);
                    } else if (step == 1) {
                        tx = Mth.lerp(u, 0.38F, -0.68F) + biteVib;
                        ty = Mth.lerp(u, 0.28F, -0.36F);
                        tz = -0.24F - Mth.sin(u * Mth.PI) * 0.20F;
                        rx = Mth.lerp(u, -114.0F, -28.0F);
                        ry = Mth.lerp(u, -70.0F, 86.0F);
                        rz = Mth.lerp(u, -46.0F, 52.0F);
                    } else {
                        tx = Mth.lerp(u, 0.38F, -0.78F) + biteVib;
                        ty = -0.10F;
                        tz = -0.28F - Mth.sin(u * Mth.PI) * 0.22F;
                        rx = -82.0F;
                        ry = Mth.lerp(u, -86.0F, 108.0F);
                        rz = Mth.lerp(u, -38.0F, 68.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_WHIRLPOOL -> {
                    // 陆之型·扭转漩涡 / 灼骨炎阳：前 11% 收刀压息 -> 随后加速狂卷螺旋升腾！
                    if (t < 0.11F) {
                        float u = smoothStep(t / 0.11F);
                        tx = -0.22F * u; ty = -0.30F * u; tz = 0.16F * u;
                        rx = -52.0F * u; ry = 46.0F * u; rz = 32.0F * u;
                    } else {
                        float u = (t - 0.11F) / 0.89F;
                        float spin = (float) Math.pow(u, 0.84) * 940.0F;
                        tx = -0.14F + Mth.cos(spin * Mth.DEG_TO_RAD) * 0.38F + biteVib;
                        ty = -0.24F + u * 0.44F;
                        tz = -0.26F;
                        rx = -68.0F - u * 24.0F;
                        ry = spin * 0.85F;
                        rz = 42.0F + Mth.sin(spin * Mth.DEG_TO_RAD) * 28.0F;
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_WATERFALL_DIVE -> {
                    // 捌之型·泷壶 / 阳华突：前 14% 凌空悬停高举蓄势微颤 -> 随后雷霆俯冲！
                    float u = smoothStep(Mth.clamp(t / 0.14F, 0.0F, 1.0F));
                    float apexVib = t < 0.14F ? Mth.sin((player.tickCount + partialTick) * 3.2F) * 0.015F : 0.0F;
                    tx = Mth.lerp(u, 0.0F, -0.22F) + apexVib;
                    ty = Mth.lerp(u, 0.0F, 0.48F);
                    tz = Mth.lerp(u, 0.0F, -0.16F);
                    rx = Mth.lerp(u, -6.0F, 48.0F);
                    ry = Mth.lerp(u, -8.0F, 16.0F);
                    rz = Mth.lerp(u, 4.0F, 12.0F);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_WATERFALL_IMPACT -> {
                    // 捌之型·泷壶 / 日晕终结触地：爆发下劈直插大地 -> 重压定格 -> 缓步抬刀
                    if (t < 0.20F) {
                        float u = snapOut(t / 0.20F);
                        tx = -0.22F + biteVib;
                        ty = Mth.lerp(u, 0.48F, -0.48F);
                        tz = Mth.lerp(u, -0.16F, -0.46F);
                        rx = Mth.lerp(u, 48.0F, -122.0F);
                        ry = 14.0F;
                        rz = 8.0F;
                    } else if (t < 0.42F) {
                        tx = -0.22F + biteVib; ty = -0.48F; tz = -0.46F;
                        rx = -122.0F; ry = 14.0F; rz = 8.0F;
                    } else {
                        float u = smoothStep((t - 0.42F) / 0.58F);
                        tx = Mth.lerp(u, -0.22F, 0.0F);
                        ty = Mth.lerp(u, -0.48F, 0.0F);
                        tz = Mth.lerp(u, -0.46F, 0.0F);
                        rx = Mth.lerp(u, -122.0F, -6.0F);
                        ry = Mth.lerp(u, 14.0F, -8.0F);
                        rz = Mth.lerp(u, 8.0F, 4.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_CONSTANT_FLUX -> {
                    // 拾之型·生生流转（单圈动态叠势旋斩：paramInt=1..4，正反交替 360° 越转越凌厉！）
                    int stage = Mth.clamp(a.paramInt, 1, 4);
                    float dirSign = (stage % 2 == 1) ? 1.0F : -1.0F;
                    float spin = snapOut(t) * 360.0F * dirSign;
                    float rad = spin * Mth.DEG_TO_RAD;
                    tx = -0.16F + Mth.cos(rad) * 0.42F + biteVib;
                    ty = -0.08F + Mth.sin(rad) * 0.24F;
                    tz = -0.32F - Math.abs(Mth.sin(rad)) * 0.14F;
                    rx = -78.0F + Mth.sin(rad) * 28.0F;
                    ry = spin * 0.85F;
                    rz = 50.0F * Mth.cos(rad);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_DEAD_CALM -> {
                    // 拾壹之型·凪：止水横刀架势 + 弹反/斩箭瞬间 0.15s 极速闪斩！
                    if (a.parrySnapTicks > 0) {
                        float su = snapOut(1.0F - (a.parrySnapTicks - partialTick) / Math.max(1.0F, a.parrySnapMax));
                        float dir = a.swingAlt ? 1.0F : -1.0F;
                        tx = Mth.lerp(su, 0.28F * dir, -0.64F * dir) + biteVib;
                        ty = -0.12F;
                        tz = -0.30F;
                        rx = -86.0F;
                        ry = Mth.lerp(su, -76.0F * dir, 96.0F * dir);
                        rz = 58.0F * dir;
                    } else {
                        tx = -0.24F;
                        ty = -0.16F + Mth.sin(time * 0.55F) * 0.008F;
                        tz = -0.10F;
                        rx = -82.0F;
                        ry = 52.0F;
                        rz = 68.0F;
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_FINALE_IAI_FLASH -> {
                    // 奥义穿阵一闪终结：0..28% 闪电居合横贯穿斩 -> 28..72% 极帅残心斜压定格（静待身后真龙撞阵爆裂！）-> 干脆收刀！
                    if (t < 0.28F) {
                        float u = snapOut(t / 0.28F);
                        tx = Mth.lerp(u, 0.44F, -0.84F) + biteVib;
                        ty = Mth.lerp(u, -0.26F, -0.06F) + Mth.sin(u * Mth.PI) * 0.12F;
                        tz = Mth.lerp(u, 0.24F, -0.40F);
                        rx = Mth.lerp(u, -78.0F, -92.0F);
                        ry = Mth.lerp(u, -104.0F, 122.0F);
                        rz = Mth.lerp(u, -52.0F, 76.0F);
                    } else if (t < 0.72F) {
                        // 残心定格：刀锋低沉横驻左侧，静候身后 0.2 秒真龙破穹炸天！
                        float settle = smoothStep((t - 0.28F) / 0.16F);
                        tx = -0.84F + settle * 0.08F;
                        ty = -0.06F - settle * 0.10F;
                        tz = -0.40F + settle * 0.06F;
                        rx = -92.0F + settle * 6.0F;
                        ry = 122.0F - settle * 8.0F;
                        rz = 76.0F - settle * 6.0F;
                    } else {
                        float u = smoothStep((t - 0.72F) / 0.28F);
                        tx = Mth.lerp(u, -0.76F, 0.0F);
                        ty = Mth.lerp(u, -0.16F, 0.0F);
                        tz = Mth.lerp(u, -0.34F, 0.0F);
                        rx = Mth.lerp(u, -86.0F, -6.0F);
                        ry = Mth.lerp(u, 114.0F, -8.0F);
                        rz = Mth.lerp(u, 70.0F, 4.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_DRAGON_LEAP_CLEAVE -> {
                    if (t < 0.34F) {
                        float u = smoothStep(t / 0.34F);
                        float vib = Mth.sin((player.tickCount + partialTick) * 3.4F) * 0.015F * u;
                        tx = Mth.lerp(u, 0.0F, 0.26F) + vib;
                        ty = Mth.lerp(u, 0.0F, 0.44F);
                        tz = Mth.lerp(u, 0.0F, 0.16F);
                        rx = Mth.lerp(u, -6.0F, 52.0F);
                        ry = Mth.lerp(u, -8.0F, -28.0F);
                        rz = Mth.lerp(u, 4.0F, -18.0F);
                    } else {
                        float u = snapOut((t - 0.34F) / 0.66F);
                        tx = Mth.lerp(u, 0.26F, -0.32F) + biteVib;
                        ty = Mth.lerp(u, 0.44F, -0.44F);
                        tz = Mth.lerp(u, 0.16F, -0.48F);
                        rx = Mth.lerp(u, 52.0F, -118.0F);
                        ry = Mth.lerp(u, -28.0F, 34.0F);
                        rz = Mth.lerp(u, -18.0F, 26.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_STRIKING_TIDE -> {
                    // 肆之型·打潮：四段如潮汐连绵拍岸的高低起伏连续挥斩！
                    int step = Math.floorMod(a.paramInt, 4);
                    float u = t < 0.08F ? 0.0F : whipCurve((t - 0.08F) / 0.82F);
                    if (step == 0) {
                        tx = Mth.lerp(u, 0.38F, -0.62F) + biteVib;
                        ty = Mth.lerp(u, -0.42F, 0.34F);
                        tz = -0.24F - Mth.sin(u * Mth.PI) * 0.18F;
                        rx = Mth.lerp(u, -22.0F, -116.0F);
                        ry = Mth.lerp(u, -78.0F, 82.0F);
                        rz = Mth.lerp(u, -44.0F, 52.0F);
                    } else if (step == 1) {
                        tx = Mth.lerp(u, -0.62F, 0.36F) + biteVib;
                        ty = Mth.lerp(u, 0.36F, -0.38F);
                        tz = -0.24F - Mth.sin(u * Mth.PI) * 0.20F;
                        rx = Mth.lerp(u, -118.0F, -26.0F);
                        ry = Mth.lerp(u, 86.0F, -72.0F);
                        rz = Mth.lerp(u, 54.0F, -42.0F);
                    } else if (step == 2) {
                        tx = Mth.lerp(u, 0.40F, -0.78F) + biteVib;
                        ty = -0.06F + Mth.sin(u * Mth.TWO_PI) * 0.14F;
                        tz = -0.28F - Mth.sin(u * Mth.PI) * 0.22F;
                        rx = -82.0F;
                        ry = Mth.lerp(u, -92.0F, 108.0F);
                        rz = Mth.lerp(u, -42.0F, 68.0F);
                    } else {
                        // 第 4 拍：浪涛登顶猛然拍岸下压重斩！
                        tx = Mth.lerp(u, 0.28F, -0.46F) + biteVib;
                        ty = Mth.lerp(u, 0.46F, -0.48F);
                        tz = Mth.lerp(u, 0.14F, -0.46F);
                        rx = Mth.lerp(u, 46.0F, -124.0F);
                        ry = Mth.lerp(u, -36.0F, 48.0F);
                        rz = Mth.lerp(u, -24.0F, 36.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_BLESSED_RAIN -> {
                    // 伍之型·乾天的慈雨：极度柔和舒展的高位落雨缓弧斩
                    float u = t < 0.16F ? 0.0F : smoothStep((t - 0.16F) / 0.70F);
                    tx = Mth.lerp(u, 0.34F, -0.66F);
                    ty = Mth.lerp(u, 0.38F, -0.28F) + Mth.sin(u * Mth.PI) * 0.12F;
                    tz = -0.22F - Mth.sin(u * Mth.PI) * 0.16F;
                    rx = Mth.lerp(u, 22.0F, -74.0F);
                    ry = Mth.lerp(u, -64.0F, 76.0F);
                    rz = Mth.lerp(u, -26.0F, 44.0F);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_DROP_RIPPLE_THRUST -> {
                    // 柒之型·雫波纹突刺：收腕平端刀尖 -> 如水滴落面般闪电直线/微弧突刺！
                    boolean curved = a.paramInt != 0;
                    if (t < 0.12F) {
                        float u = smoothStep(t / 0.12F);
                        tx = 0.18F * u; ty = -0.14F * u; tz = 0.26F * u;
                        rx = -86.0F * u; ry = -12.0F * u; rz = 12.0F * u;
                    } else if (t < 0.56F) {
                        float u = snapOut((t - 0.12F) / 0.44F);
                        float arc = curved ? Mth.sin(u * Mth.PI) * 0.24F : 0.0F;
                        tx = Mth.lerp(u, 0.18F, -0.12F) + arc + biteVib;
                        ty = -0.10F;
                        tz = Mth.lerp(u, 0.26F, -0.82F);
                        rx = -88.0F;
                        ry = curved ? Mth.sin(u * Mth.PI) * -28.0F : 0.0F;
                        rz = 8.0F;
                    } else {
                        float u = smoothStep((t - 0.56F) / 0.44F);
                        tx = Mth.lerp(u, -0.12F, 0.0F);
                        ty = Mth.lerp(u, -0.10F, 0.0F);
                        tz = Mth.lerp(u, -0.82F, 0.0F);
                        rx = Mth.lerp(u, -88.0F, -6.0F);
                        ry = Mth.lerp(u, 0.0F, -8.0F);
                        rz = Mth.lerp(u, 8.0F, 4.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SPLASH_FLOW -> {
                    // 玖之型·水流飞沫·乱：轻巧迅疾的错身掠斩
                    float u = snapOut(Mth.clamp((t - 0.06F) / 0.72F, 0.0F, 1.0F));
                    float side = (a.paramInt % 2 == 0) ? 1.0F : -1.0F;
                    tx = Mth.lerp(u, 0.34F * side, -0.58F * side) + biteVib;
                    ty = -0.14F;
                    tz = -0.26F;
                    rx = -80.0F;
                    ry = Mth.lerp(u, -68.0F * side, 78.0F * side);
                    rz = 48.0F * side;
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_WATERFALL_CLEAVE -> {
                    // 捌之型·泷壶 原版垂直瀑布劈斩：双手高举过顶 -> 雷霆垂直压劈砸地 -> 沉稳定格！
                    if (t < 0.16F) {
                        float u = smoothStep(t / 0.16F);
                        tx = -0.18F * u; ty = 0.52F * u; tz = 0.10F * u;
                        rx = Mth.lerp(u, -6.0F, 64.0F);
                        ry = 8.0F * u;
                        rz = 6.0F * u;
                    } else if (t < 0.62F) {
                        float u = whipCurve((t - 0.16F) / 0.46F);
                        tx = -0.18F + biteVib;
                        ty = Mth.lerp(u, 0.52F, -0.52F);
                        tz = Mth.lerp(u, 0.10F, -0.50F);
                        rx = Mth.lerp(u, 64.0F, -128.0F);
                        ry = 8.0F;
                        rz = 6.0F;
                    } else {
                        float u = smoothStep((t - 0.62F) / 0.38F);
                        tx = Mth.lerp(u, -0.18F, 0.0F);
                        ty = Mth.lerp(u, -0.52F, 0.0F);
                        tz = Mth.lerp(u, -0.50F, 0.0F);
                        rx = Mth.lerp(u, -128.0F, -6.0F);
                        ry = Mth.lerp(u, 8.0F, -8.0F);
                        rz = Mth.lerp(u, 6.0F, 4.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_CLEAR_BLUE_SKY -> {
                    // 日·碧罗之天：压低刀锋由下而上逆向 360° 垂直大炎轮升天挑斩！
                    float u = t < 0.10F ? 0.0F : snapOut((t - 0.10F) / 0.90F);
                    tx = -0.14F + Mth.sin(u * Mth.TWO_PI) * 0.16F + biteVib;
                    ty = -0.12F + Mth.sin(u * Mth.PI) * 0.42F;
                    tz = -0.26F;
                    rx = -115.0F + u * 365.0F;
                    ry = 12.0F;
                    rz = 14.0F * Mth.sin(u * Mth.TWO_PI);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_RAGING_SUN -> {
                    // 日·烈日红镜：左右「∞」字高速交叉双弧烈焰斩！
                    if (t < 0.50F) {
                        float u = whipCurve(Mth.clamp((t - 0.08F) / 0.40F, 0.0F, 1.0F));
                        tx = Mth.lerp(u, 0.44F, -0.78F) + biteVib;
                        ty = -0.06F + Mth.sin(u * Mth.TWO_PI) * 0.20F;
                        tz = -0.32F;
                        rx = -84.0F;
                        ry = Mth.lerp(u, -94.0F, 108.0F);
                        rz = Mth.lerp(u, -46.0F, 68.0F);
                    } else {
                        float u = whipCurve(Mth.clamp((t - 0.52F) / 0.40F, 0.0F, 1.0F));
                        tx = Mth.lerp(u, -0.78F, 0.44F) + biteVib;
                        ty = 0.08F - Mth.sin(u * Mth.TWO_PI) * 0.20F;
                        tz = -0.34F;
                        rx = -82.0F;
                        ry = Mth.lerp(u, 108.0F, -94.0F);
                        rz = Mth.lerp(u, 68.0F, -48.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_SETTING_SUN -> {
                    // 日·斜阳转身：凌空倒转反手自上而下赤红奇袭横斩！
                    float u = t < 0.16F ? 0.0F : whipCurve((t - 0.16F) / 0.66F);
                    tx = Mth.lerp(u, -0.68F, 0.48F) + biteVib;
                    ty = Mth.lerp(u, 0.42F, -0.34F);
                    tz = -0.36F;
                    rx = Mth.lerp(u, -52.0F, -106.0F);
                    ry = Mth.lerp(u, 118.0F, -98.0F);
                    rz = Mth.lerp(u, 155.0F, 15.0F);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_HEAT_HAZE -> {
                    // 日·飞轮阳炎：高频热浪微颤、斜向前方极远伸展阳炎刀锋！
                    float u = t < 0.14F ? 0.0F : whipCurve((t - 0.14F) / 0.60F);
                    float shimmer = Mth.sin((player.tickCount + partialTick) * 3.4F) * 0.032F;
                    tx = Mth.lerp(u, 0.36F, -0.68F) + shimmer + biteVib;
                    ty = -0.04F + shimmer * 0.8F;
                    tz = -0.58F - Mth.sin(u * Mth.PI) * 0.24F;
                    rx = -86.0F;
                    ry = Mth.lerp(u, -82.0F, 92.0F);
                    rz = Mth.lerp(u, -36.0F, 52.0F);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_FLAME_DANCE -> {
                    // 日·炎舞：第一刀宽幅斜挑重斩 / 第二刀回旋炽炎暴劈！
                    boolean second = (a.paramInt & 1) != 0;
                    float u = t < 0.08F ? 0.0F : whipCurve((t - 0.08F) / 0.76F);
                    if (!second) {
                        tx = Mth.lerp(u, 0.42F, -0.74F) + biteVib;
                        ty = Mth.lerp(u, -0.38F, 0.36F);
                        tz = -0.32F;
                        rx = Mth.lerp(u, -24.0F, -112.0F);
                        ry = Mth.lerp(u, -88.0F, 98.0F);
                        rz = Mth.lerp(u, -44.0F, 62.0F);
                    } else {
                        tx = Mth.lerp(u, -0.74F, 0.44F) + biteVib;
                        ty = Mth.lerp(u, 0.44F, -0.44F);
                        tz = -0.42F;
                        rx = Mth.lerp(u, -118.0F, -28.0F);
                        ry = Mth.lerp(u, 108.0F, -92.0F);
                        rz = Mth.lerp(u, 72.0F, -54.0F);
                    }
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_HALO_DRAGON_DASH -> {
                    // 日·日晕之龙·头舞：四段极速游龙狂舞大弧！
                    float u = snapOut(Mth.clamp((t - 0.06F) / 0.86F, 0.0F, 1.0F));
                    float side = (a.paramInt % 2 == 0) ? 1.0F : -1.0F;
                    tx = Mth.lerp(u, 0.48F * side, -0.80F * side) + biteVib;
                    ty = Mth.lerp(u, -0.30F * side, 0.32F * side);
                    tz = -0.44F - Mth.sin(u * Mth.PI) * 0.20F;
                    rx = -88.0F;
                    ry = Mth.lerp(u, -104.0F * side, 114.0F * side);
                    rz = Mth.lerp(u, -58.0F * side, 72.0F * side);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                case ANIM_SUN_DANCING_FLASH -> {
                    // 日·圆舞一闪：极速雷步瞬闪大弧一刀断首！
                    float u = snapOut(Mth.clamp((t - 0.06F) / 0.50F, 0.0F, 1.0F));
                    tx = Mth.lerp(u, 0.46F, -0.86F) + biteVib;
                    ty = -0.10F;
                    tz = -0.52F;
                    rx = -86.0F;
                    ry = Mth.lerp(u, -110.0F, 124.0F);
                    rz = Mth.lerp(u, -52.0F, 82.0F);
                    return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
                }
                default -> {}
            }
        }

        // 搓招按键轻拂微动作（录入 R/L 时干脆亮刀蓄势）
        if (a != null && a.comboFlourishTicks > 0) {
            float fu = (a.comboFlourishTicks - partialTick) / 6.0F;
            float side = a.swingAlt ? 1.0F : -1.0F;
            tx += 0.14F * fu * side;
            ty -= 0.08F * fu;
            rx -= 24.0F * fu;
            ry -= 26.0F * fu * side;
            rz -= 14.0F * fu * side;
            return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
        }

        // 普通左键平A：起势微蓄 -> 非线性鞭击斜斩 -> 干脆收锋（带卡肉微震！）
        if (vanillaSwing > 0.001F) {
            if (a != null && vanillaSwing < 0.16F && !a.lastVanillaSwinging) {
                a.swingAlt = !a.swingAlt;
                a.lastVanillaSwinging = true;
            }
            boolean alt = a != null && a.swingAlt;
            float biteVib = (a != null && a.hitstopTicks > 0.0F)
                    ? Mth.sin((player.tickCount + partialTick) * 3.8F) * 0.020F * a.hitstopIntensity : 0.0F;
            float s = Mth.sin(Mth.sqrt(vanillaSwing) * Mth.PI);
            float sweep = whipCurve(Mth.clamp((vanillaSwing - 0.04F) * 1.38F, 0.0F, 1.0F));
            if (!alt) {
                tx = Mth.lerp(sweep, 0.28F, -0.62F) * s + biteVib;
                ty = Mth.lerp(sweep, 0.18F, -0.30F) * s;
                tz = -0.24F * s;
                rx = -6.0F - 78.0F * s;
                ry = -8.0F + Mth.lerp(sweep, -52.0F, 88.0F) * s;
                rz = 4.0F + Mth.lerp(sweep, -36.0F, 52.0F) * s;
            } else {
                tx = Mth.lerp(sweep, -0.56F, 0.30F) * s + biteVib;
                ty = Mth.lerp(sweep, 0.16F, -0.26F) * s;
                tz = -0.24F * s;
                rx = -6.0F - 74.0F * s;
                ry = -8.0F + Mth.lerp(sweep, 82.0F, -56.0F) * s;
                rz = 4.0F + Mth.lerp(sweep, 48.0F, -40.0F) * s;
            }
        } else if (a != null) {
            a.lastVanillaSwinging = false;
        }

        return new FirstPersonPose(tx, ty, tz, rx, ry, rz);
    }

    // ==================== 2. 第三人称人物本体翻滚 / 旋身 / 冲锋姿态 ====================

    public static ThirdPersonBodyPose computeThirdPersonBodyPose(int entityId, float partialTick) {
        PlayerSwordAnim a = get(entityId);
        if (a == null || a.animType == ANIM_NONE) {
            return ThirdPersonBodyPose.IDENTITY;
        }
        float t = a.getProgress(partialTick);
        return switch (a.animType) {
            case ANIM_SURFACE_SLASH -> {
                if (t < 0.20F) {
                    float u = smoothStep(t / 0.20F);
                    yield new ThirdPersonBodyPose(0.0F, -46.0F * u, 16.0F * u, -8.0F * u);
                }
                float u = whipCurve(Mth.clamp((t - 0.20F) / 0.44F, 0.0F, 1.0F));
                float fade = t > 0.78F ? 1.0F - smoothStep((t - 0.78F) / 0.22F) : 1.0F;
                yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, -46.0F, 54.0F) * fade, 18.0F * fade, 6.0F * u * fade);
            }
            case ANIM_WATER_WHEEL -> {
                if (t < 0.12F) {
                    float u = smoothStep(t / 0.12F);
                    yield new ThirdPersonBodyPose(0.0F, 0.0F, 24.0F * u, 0.0F);
                }
                yield new ThirdPersonBodyPose(snapOut((t - 0.12F) / 0.88F) * 360.0F, 0.0F, 0.0F, 0.0F);
            }
            case ANIM_LATERAL_WHEEL -> {
                if (t < 0.12F) {
                    float u = smoothStep(t / 0.12F);
                    yield new ThirdPersonBodyPose(0.0F, -32.0F * u, 14.0F * u, 0.0F);
                }
                float u = (t - 0.12F) / 0.88F;
                yield new ThirdPersonBodyPose(0.0F, snapOut(u) * 360.0F, 14.0F * Mth.sin(u * Mth.PI), 0.0F);
            }
            case ANIM_FLOWING_DANCE -> {
                float u = t < 0.10F ? 0.0F : whipCurve((t - 0.10F) / 0.78F);
                float s = Mth.sin(t * Mth.PI);
                float roll = (a.paramInt % 2 == 0 ? -20.0F : 20.0F) * s;
                yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, -34.0F, 34.0F) * s, 24.0F * s, roll);
            }
            case ANIM_WHIRLPOOL -> {
                if (t < 0.11F) {
                    float u = smoothStep(t / 0.11F);
                    yield new ThirdPersonBodyPose(0.0F, -35.0F * u, 16.0F * u, 0.0F);
                }
                float u = (t - 0.11F) / 0.89F;
                yield new ThirdPersonBodyPose(0.0F, (float) Math.pow(u, 0.84) * 920.0F, 12.0F * Mth.sin(u * Mth.PI), 0.0F);
            }
            case ANIM_WATERFALL_DIVE -> {
                if (t < 0.14F) {
                    // 凌空举刀后仰悬停蓄势！
                    yield new ThirdPersonBodyPose(-14.0F * smoothStep(t / 0.14F), 0.0F, 0.0F, 0.0F);
                }
                yield new ThirdPersonBodyPose(40.0F, 0.0F, 0.0F, 0.0F);
            }
            case ANIM_WATERFALL_IMPACT -> new ThirdPersonBodyPose(22.0F * (1.0F - smoothStep(t)), 0.0F, 0.0F, 0.0F);
            case ANIM_CONSTANT_FLUX -> {
                int stage = Mth.clamp(a.paramInt, 1, 4);
                float dirSign = (stage % 2 == 1) ? 1.0F : -1.0F;
                float spin = snapOut(t) * 360.0F * dirSign;
                yield new ThirdPersonBodyPose(24.0F, spin, 0.0F, 16.0F * Mth.sin(spin * Mth.DEG_TO_RAD));
            }
            case ANIM_FINALE_IAI_FLASH -> {
                if (t < 0.34F) {
                    float u = snapOut(t / 0.34F);
                    yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, -45.0F, 52.0F), Mth.lerp(u, 32.0F, 20.0F), -14.0F * u);
                } else if (t < 0.72F) {
                    yield new ThirdPersonBodyPose(0.0F, 52.0F, 18.0F, -12.0F);
                } else {
                    float u = smoothStep((t - 0.72F) / 0.28F);
                    yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, 52.0F, 0.0F), Mth.lerp(u, 18.0F, 0.0F), Mth.lerp(u, -12.0F, 0.0F));
                }
            }
            case ANIM_DRAGON_LEAP_CLEAVE -> {
                if (t < 0.34F) {
                    float u = smoothStep(t / 0.34F);
                    yield new ThirdPersonBodyPose(-18.0F * u, -24.0F * u, 0.0F, 0.0F);
                }
                float u = snapOut((t - 0.34F) / 0.66F);
                yield new ThirdPersonBodyPose(Mth.lerp(u, -18.0F, 44.0F), Mth.lerp(u, -24.0F, 26.0F), 0.0F, 0.0F);
            }
            case ANIM_STRIKING_TIDE -> {
                float u = t < 0.08F ? 0.0F : whipCurve((t - 0.08F) / 0.82F);
                float s = Mth.sin(t * Mth.PI);
                int step = Math.floorMod(a.paramInt, 4);
                float dir = (step % 2 == 0) ? 1.0F : -1.0F;
                yield new ThirdPersonBodyPose(step == 3 ? 28.0F * u : 0.0F, Mth.lerp(u, -38.0F * dir, 42.0F * dir) * s, 22.0F * s, 14.0F * dir * s);
            }
            case ANIM_BLESSED_RAIN -> {
                float s = Mth.sin(t * Mth.PI);
                yield new ThirdPersonBodyPose(-10.0F * s, Mth.lerp(t, -28.0F, 34.0F) * s, 12.0F * s, 8.0F * s);
            }
            case ANIM_DROP_RIPPLE_THRUST -> {
                float s = Mth.sin(Mth.clamp(t * 1.4F, 0.0F, 1.0F) * Mth.PI);
                float side = a.paramInt != 0 ? -18.0F * s : 0.0F;
                yield new ThirdPersonBodyPose(0.0F, -22.0F * s, 32.0F * s, side);
            }
            case ANIM_SPLASH_FLOW -> {
                float s = Mth.sin(t * Mth.PI);
                float side = (a.paramInt % 2 == 0) ? 18.0F : -18.0F;
                yield new ThirdPersonBodyPose(0.0F, side * 1.4F * s, 26.0F * s, side * s);
            }
            case ANIM_WATERFALL_CLEAVE -> {
                if (t < 0.16F) {
                    yield new ThirdPersonBodyPose(-20.0F * smoothStep(t / 0.16F), 0.0F, 0.0F, 0.0F);
                }
                float u = whipCurve(Mth.clamp((t - 0.16F) / 0.46F, 0.0F, 1.0F));
                float fade = t > 0.62F ? 1.0F - smoothStep((t - 0.62F) / 0.38F) : 1.0F;
                yield new ThirdPersonBodyPose(Mth.lerp(u, -20.0F, 36.0F) * fade, 0.0F, 0.0F, 0.0F);
            }
            case ANIM_SUN_CLEAR_BLUE_SKY -> {
                // 碧罗之天：由下而上逆向 360° 垂直升天翻转！
                if (t < 0.10F) {
                    yield new ThirdPersonBodyPose(20.0F * smoothStep(t / 0.10F), 0.0F, 0.0F, 0.0F);
                }
                yield new ThirdPersonBodyPose(-snapOut((t - 0.10F) / 0.90F) * 360.0F, 0.0F, 0.0F, 0.0F);
            }
            case ANIM_SUN_RAGING_SUN -> {
                float s = Mth.sin(t * Mth.PI);
                float yaw = t < 0.50F ? Mth.lerp(t / 0.50F, -48.0F, 52.0F) : Mth.lerp((t - 0.50F) / 0.50F, 52.0F, -48.0F);
                yield new ThirdPersonBodyPose(0.0F, yaw * s, 20.0F * s, (t < 0.50F ? -14.0F : 14.0F) * s);
            }
            case ANIM_SUN_SETTING_SUN -> {
                // 斜阳转身：半空优雅 180° 倒立逆转旋身横斩！
                float s = Mth.sin(t * Mth.PI);
                float flip = 180.0F * Mth.sin(Mth.clamp(t * 1.15F, 0.0F, 1.0F) * Mth.PI);
                float spin = whipCurve(Mth.clamp((t - 0.14F) / 0.72F, 0.0F, 1.0F)) * 360.0F;
                yield new ThirdPersonBodyPose(flip, spin, 14.0F * s, 16.0F * s);
            }
            case ANIM_SUN_HEAT_HAZE -> {
                float u = whipCurve(Mth.clamp((t - 0.12F) / 0.62F, 0.0F, 1.0F));
                float fade = t > 0.78F ? 1.0F - smoothStep((t - 0.78F) / 0.22F) : 1.0F;
                yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, -40.0F, 48.0F) * fade, 28.0F * fade, 8.0F * fade);
            }
            case ANIM_SUN_FLAME_DANCE -> {
                boolean second = (a.paramInt & 1) != 0;
                float u = whipCurve(Mth.clamp((t - 0.08F) / 0.78F, 0.0F, 1.0F));
                float s = Mth.sin(t * Mth.PI);
                float yaw = second ? Mth.lerp(u, 52.0F, -64.0F) : Mth.lerp(u, -46.0F, 52.0F);
                yield new ThirdPersonBodyPose(second ? 16.0F * s : 0.0F, yaw * s, 24.0F * s, (second ? 18.0F : -14.0F) * s);
            }
            case ANIM_SUN_HALO_DRAGON_DASH -> {
                float u = snapOut(t);
                float side = (a.paramInt % 2 == 0) ? 1.0F : -1.0F;
                yield new ThirdPersonBodyPose(18.0F, Mth.lerp(u, -58.0F * side, 64.0F * side), 28.0F, 22.0F * side);
            }
            case ANIM_SUN_DANCING_FLASH -> {
                float u = snapOut(Mth.clamp((t - 0.06F) / 0.56F, 0.0F, 1.0F));
                float fade = t > 0.72F ? 1.0F - smoothStep((t - 0.72F) / 0.28F) : 1.0F;
                yield new ThirdPersonBodyPose(0.0F, Mth.lerp(u, -58.0F, 68.0F) * fade, 34.0F * fade, 14.0F * fade);
            }
            default -> ThirdPersonBodyPose.IDENTITY;
        };
    }

    // ==================== 3. 实时 3D 世界空间刀柄/刀尖坐标采样（常态+全剑型刀光带统一源头） ====================

    /**
     * 在真实渲染帧精确采样日轮刀刀柄/刀尖世界坐标：
     * 玩家坐标 lerp(pt, xo, x) 与剑型进度 lerp(pt, prevAge, age) 100% 锁定在同一根平滑连续时间轴上，
     * 彻底杜绝 1-tick 错位与回折抖动！
     */
    public static BladeSample sampleWorldBlade(Player player, float partialTick) {
        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double py = Mth.lerp(partialTick, player.yo, player.getY()) + 1.05;
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());
        float yRot = Mth.lerp(partialTick, player.yRotO, player.getYRot());

        Vec3 fwd = Vec3.directionFromRotation(0.0F, yRot).normalize();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x).normalize();
        Vec3 up = new Vec3(0.0, 1.0, 0.0);

        PlayerSwordAnim a = get(player.getId());
        boolean tc = (player == Minecraft.getInstance().player)
                ? ClientBreathState.isTotalConcentration()
                : (a != null && a.totalConc);

        // --- A0. 凪弹反 / 幻日虹残影反杀瞬闪刀光 ---
        if (a != null && a.isParryActive()) {
            float su = a.getParryProgress(partialTick);
            float side = a.swingAlt ? 1.0F : -1.0F;
            float angDeg = Mth.lerp(su, -108.0F * side, 108.0F * side);
            Vec3 dir = rotateHorizontal(fwd, right, angDeg);
            Vec3 base = new Vec3(px, py, pz).add(dir.scale(1.05));
            Vec3 tip = new Vec3(px, py + 0.08, pz).add(dir.scale(a.sunStyle ? 3.85 : 3.45));
            return new BladeSample(base, tip, true, true, a.sunStyle ? 1.15F : 1.0F, a.sunStyle);
        }

        // --- A. 活跃剑型动作：直接调用服务端/客户端共享的 BladeKinematics，视觉刀光与 140Hz 伤害扫描 100% 同源！ ---
        if (a != null && a.animType != ANIM_NONE) {
            float t = a.getProgress(partialTick);
            BladeKinematics.BladeSegment seg = BladeKinematics.sampleBlade(
                    a.animType, t, a.paramInt, tc, a.sunStyle, px, py, pz, yRot
            );
            if (!seg.active()) {
                return BladeSample.INACTIVE;
            }
            return new BladeSample(seg.base(), seg.tip(), true, true, seg.intensity(), a.sunStyle);
        }

        // 非剑型释放期间不显示浮世绘刀光，保持画面干净利落
        return BladeSample.INACTIVE;
    }

    public static boolean isHoldingWaterBlade(Player player) {
        return WaterNichirinBladeItem.isAnyNichirinBlade(player.getMainHandItem());
    }

    private static Vec3 rotateHorizontal(Vec3 fwd, Vec3 right, float deg) {
        float rad = deg * Mth.DEG_TO_RAD;
        return fwd.scale(Mth.cos(rad)).add(right.scale(-Mth.sin(rad))).normalize();
    }

    public static float smoothStep(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** 爆发瞬发缓收曲线：瞬间起步如闪电，末段稳健刹停 */
    public static float snapOut(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    /** 鬼灭日式鞭击曲线：C1 光滑且前中段爆发占 87.5% 行程，极具“静如止水、动如雷霆”刀势！ */
    public static float whipCurve(float x) {
        return snapOut(smoothStep(x));
    }

    // ==================== 数据结构 ====================

    public static final class PlayerSwordAnim {
        public int animType = ANIM_NONE;
        public float prevAnimTime = 0.0F;
        public float animTime = 0.0F;
        public int duration = 12;
        public int paramInt = 0;
        public boolean totalConc = false;
        public boolean sunStyle = false;

        /** 命中顿刀卡肉剩余 tick 与咬合震动强度 */
        public float hitstopTicks = 0.0F;
        public float hitstopIntensity = 0.0F;

        public int chargingMode = 0;
        public int chargeAge = 0;
        public int chargeDuration = 36;

        public int prevParrySnapTicks = 0;
        public int parrySnapTicks = 0;
        public int parrySnapMax = 6;
        public int comboFlourishTicks = 0;

        public boolean swingAlt = false;
        public boolean lastVanillaSwinging = false;

        public float getProgress(float partialTick) {
            return Mth.clamp(Mth.lerp(partialTick, prevAnimTime, animTime) / Math.max(1.0F, duration), 0.0F, 1.0F);
        }

        public boolean isParryActive() {
            return parrySnapTicks > 0 || prevParrySnapTicks > 0;
        }

        public float getParryProgress(float partialTick) {
            float rem = Mth.lerp(partialTick, prevParrySnapTicks, parrySnapTicks);
            return Mth.clamp(1.0F - rem / Math.max(1.0F, parrySnapMax), 0.0F, 1.0F);
        }
    }

    public record FirstPersonPose(float tx, float ty, float tz, float rotX, float rotY, float rotZ) {}

    public record ThirdPersonBodyPose(float flipPitch, float spinYaw, float leanPitch, float roll) {
        public static final ThirdPersonBodyPose IDENTITY = new ThirdPersonBodyPose(0.0F, 0.0F, 0.0F, 0.0F);

        public boolean isActive() {
            return flipPitch != 0.0F || spinYaw != 0.0F || leanPitch != 0.0F || roll != 0.0F;
        }
    }

    public record BladeSample(Vec3 base, Vec3 tip, boolean active, boolean spawnFoam, float intensity, boolean sunStyle) {
        public static final BladeSample INACTIVE = new BladeSample(Vec3.ZERO, Vec3.ZERO, false, false, 0.0F, false);
    }
}
