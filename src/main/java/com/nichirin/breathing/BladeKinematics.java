package com.nichirin.breathing;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 服务端 / 客户端 100% 共享的日轮刀 3D 运动学与 140Hz 子步连续扫掠判定引擎（Single Source of Truth）：
 * - 客户端 144Hz 浮世绘刀光带 与 服务端 140Hz 伤害碰撞 严格读取完全同一份刀柄→刀尖世界坐标数学公式！
 * - 视觉上刀光从右向左挥过哪里，物理碰撞就按真实扫掠先后顺序、以厘米级精度切过哪里，并算出精确的 3D 刀锋切线受力矢量！
 */
public final class BladeKinematics {
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
    /** 10: 生生流转高势能收尾龙尾横斩 + 干脆残心定格！ */
    public static final int ANIM_FINALE_IAI_FLASH = 10;
    /** 11: 腾空蓄刀斜劈（预留） */
    public static final int ANIM_DRAGON_LEAP_CLEAVE = 11;
    /** 12: 肆之型·打潮（paramInt=0..3：连绵起伏四段浪潮重斩！） */
    public static final int ANIM_STRIKING_TIDE = 12;
    /** 13: 伍之型·乾天的慈雨（优雅凌空轻柔弧斩，如细雨轻拂！） */
    public static final int ANIM_BLESSED_RAIN = 13;
    /** 14: 柒之型·雫波纹突刺（paramInt=0 极速直线突刺 / 1 弧线变向突刺「曲」） */
    public static final int ANIM_DROP_RIPPLE_THRUST = 14;
    /** 15: 玖之型·水流飞沫·乱（高速点水碎步轻盈掠斩） */
    public static final int ANIM_SPLASH_FLOW = 15;
    /** 16: 捌之型·泷壶 原版垂直瀑布重劈（举刀登顶 -> 175° 垂直天降劈地！） */
    public static final int ANIM_WATERFALL_CLEAVE = 16;

    /** 每服务端 tick 细分为 7 个物理子步（20 TPS * 7 = 140Hz 连续刀刃扫描，彻底杜绝快刀跳帧穿模！） */
    public static final int SWEEP_SUB_STEPS = 7;

    private BladeKinematics() {}

    /**
     * 共享 3D 刀锋采样：在任意连续进度 t ∈ [0, 1] 与玩家锚点 (px, py, pz, yRot) 下，
     * 精确返回此刻日轮刀刀柄/刀尖的 3D 世界坐标与有效状态。
     */
    public static BladeSegment sampleBlade(
            int animType, float t, int paramInt, boolean tc, boolean sun,
            double px, double py, double pz, float yRot
    ) {
        Vec3 fwd = Vec3.directionFromRotation(0.0F, yRot).normalize();
        Vec3 right = new Vec3(-fwd.z, 0.0, fwd.x).normalize();
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        float tcReach = tc ? 1.15F : 1.0F;

        switch (animType) {
            case ANIM_SURFACE_SLASH -> {
                // 前 20% 凝神右拉蓄势（不出刀光、无判定）-> 中段鞭击 258° 全幅横斩 -> 残心定格
                if (t < 0.20F || t > 0.74F) {
                    return BladeSegment.INACTIVE;
                }
                float u = whipCurve(Mth.clamp((t - 0.20F) / 0.44F, 0.0F, 1.0F));
                float angDeg = Mth.lerp(u, -130.0F, 128.0F);
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                double innerR = 1.25 * tcReach;
                double outerR = (sun ? 5.10 : 4.70) * tcReach;
                double waveY = 0.16 * Mth.sin(u * Mth.TWO_PI);
                Vec3 base = new Vec3(px, py + waveY, pz).add(dir.scale(innerR));
                Vec3 tip = new Vec3(px, py + waveY + 0.12, pz).add(dir.scale(outerR));
                return new BladeSegment(base, tip, true, sun ? 1.24F : 1.18F);
            }
            case ANIM_WATER_WHEEL -> {
                // 前 12% 屈膝压刀蓄势 -> 竖直面爆发加速 395° 纵翻轮斩！
                if (t < 0.12F) return BladeSegment.INACTIVE;
                float rad = (snapOut((t - 0.12F) / 0.88F) * 395.0F - 35.0F) * Mth.DEG_TO_RAD;
                Vec3 dir = fwd.scale(Mth.cos(rad)).add(up.scale(Mth.sin(rad)));
                double innerR = 1.15 * tcReach;
                double outerR = (sun ? 4.00 : 3.70) * tcReach;
                Vec3 base = new Vec3(px, py + 0.15, pz).add(dir.scale(innerR)).add(right.scale(0.18));
                Vec3 tip = new Vec3(px, py + 0.15, pz).add(dir.scale(outerR)).add(right.scale(0.18));
                return new BladeSegment(base, tip, true, sun ? 1.22F : 1.16F);
            }
            case ANIM_LATERAL_WHEEL -> {
                // 前 12% 空中缩身引刀 -> 水平 415° 疾旋烈环！
                if (t < 0.12F) return BladeSegment.INACTIVE;
                float angDeg = snapOut((t - 0.12F) / 0.88F) * 415.0F - 35.0F;
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                double tiltY = Mth.sin(angDeg * Mth.DEG_TO_RAD) * 0.42;
                Vec3 base = new Vec3(px, py + tiltY * 0.4, pz).add(dir.scale(1.40 * tcReach));
                Vec3 tip = new Vec3(px, py + tiltY, pz).add(dir.scale((sun ? 5.25 : 4.90) * tcReach));
                return new BladeSegment(base, tip, true, sun ? 1.24F : 1.18F);
            }
            case ANIM_FLOWING_DANCE -> {
                // 三段交替灵动斜斩：0=左下→右上挑斩，1=右上→左下劈斩，2=宽幅水平横扫
                if (t < 0.10F || t > 0.88F) return BladeSegment.INACTIVE;
                int step = Math.floorMod(paramInt, 3);
                float u = whipCurve((t - 0.10F) / 0.72F);
                float angDeg;
                double yB, yT;
                if (step == 0) {
                    angDeg = Mth.lerp(u, -110.0F, 102.0F);
                    yB = Mth.lerp(u, -0.35, 0.35);
                    yT = Mth.lerp(u, -0.65, 0.72);
                } else if (step == 1) {
                    angDeg = Mth.lerp(u, 110.0F, -102.0F);
                    yB = Mth.lerp(u, 0.35, -0.25);
                    yT = Mth.lerp(u, 0.72, -0.55);
                } else {
                    angDeg = Mth.lerp(u, -122.0F, 122.0F);
                    yB = 0.04;
                    yT = 0.16;
                }
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                Vec3 base = new Vec3(px, py + yB, pz).add(dir.scale(1.05 * tcReach));
                double reach = (step == 2 ? (sun ? 4.90 : 4.40) : (sun ? 4.30 : 3.80)) * tcReach;
                Vec3 tip = new Vec3(px, py + yT, pz).add(dir.scale(reach));
                return new BladeSegment(base, tip, true, sun ? 1.18F : 1.08F);
            }
            case ANIM_WHIRLPOOL -> {
                // 前 11% 内收聚气 -> 螺旋加速上升 940°！
                if (t < 0.11F) return BladeSegment.INACTIVE;
                float u = (t - 0.11F) / 0.89F;
                float angDeg = (float) Math.pow(u, 0.84) * 940.0F;
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                double rise = -0.55 + u * 3.85;
                double outerR = ((sun ? 4.0 : 3.6) + u * 1.45) * tcReach;
                Vec3 base = new Vec3(px, py + rise * 0.82, pz).add(dir.scale(1.05 * tcReach));
                Vec3 tip = new Vec3(px, py + rise + 0.55, pz).add(dir.scale(outerR));
                return new BladeSegment(base, tip, true, sun ? 1.22F : 1.15F);
            }
            case ANIM_WATERFALL_DIVE -> {
                // 前 14% 凌空悬停举刀蓄势 -> 雷霆斜坠刀锋！
                if (t < 0.14F) return BladeSegment.INACTIVE;
                Vec3 dir = fwd.scale(0.55).add(up.scale(-0.82)).normalize();
                Vec3 base = new Vec3(px, py + 0.4, pz).add(dir.scale(0.75)).add(right.scale(0.2));
                Vec3 tip = new Vec3(px, py + 0.4, pz).add(dir.scale((sun ? 3.80 : 3.40) * tcReach)).add(right.scale(0.2));
                return new BladeSegment(base, tip, true, sun ? 1.18F : 1.10F);
            }
            case ANIM_CONSTANT_FLUX -> {
                // 拾之型·生生流转（单圈动态回旋叠势！paramInt = 当前第 1..4 圈，每转一圈刀幅与高低起伏更具压迫力！）
                int stage = Mth.clamp(paramInt, 1, 4);
                float dirSign = (stage % 2 == 1) ? 1.0F : -1.0F;
                float angDeg = snapOut(t) * 360.0F * dirSign;
                float rad = angDeg * Mth.DEG_TO_RAD;
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                double coilY = Mth.sin(rad) * (0.62 + stage * 0.14);
                double outerR = (3.40 + stage * 0.42) * tcReach;
                Vec3 base = new Vec3(px, py + coilY * 0.45, pz).add(dir.scale(0.95 * tcReach));
                Vec3 tip = new Vec3(px, py + coilY, pz).add(dir.scale(outerR));
                return new BladeSegment(base, tip, true, 1.12F + stage * 0.08F);
            }
            case ANIM_FINALE_IAI_FLASH -> {
                // 生生流转高势能收尾龙尾斩：前 40% 顺着最高转速猛然挥出极宽断首弧 -> 残心稳停！
                if (t > 0.42F) return BladeSegment.INACTIVE;
                int stage = Mth.clamp(paramInt, 1, 4);
                float u = whipCurve(t / 0.42F);
                float angDeg = Mth.lerp(u, -138.0F, 144.0F);
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                double innerR = 1.15 * tcReach;
                double outerR = (4.55 + stage * 0.32) * tcReach;
                double slashY = Mth.lerp(u, -0.28, 0.24);
                Vec3 base = new Vec3(px, py + slashY * 0.45, pz).add(dir.scale(innerR));
                Vec3 tip = new Vec3(px, py + slashY, pz).add(dir.scale(outerR));
                return new BladeSegment(base, tip, true, 1.24F + stage * 0.06F);
            }
            case ANIM_DRAGON_LEAP_CLEAVE -> {
                // 前 34% 凌空跃起举刀蓄势 -> 随后雷霆斜劈俯冲拉出修长贯地刀光
                if (t < 0.34F) return BladeSegment.INACTIVE;
                float u = snapOut((t - 0.34F) / 0.66F);
                float pitchRad = Mth.lerp(u, 62.0F, -58.0F) * Mth.DEG_TO_RAD;
                Vec3 dir = fwd.scale(Mth.cos(pitchRad)).add(up.scale(Mth.sin(pitchRad))).normalize();
                Vec3 base = new Vec3(px, py + 0.35, pz).add(dir.scale(0.85 * tcReach)).add(right.scale(0.18));
                Vec3 tip = new Vec3(px, py + 0.35, pz).add(dir.scale(4.20 * tcReach)).add(right.scale(0.18));
                return new BladeSegment(base, tip, true, 1.24F);
            }
            case ANIM_STRIKING_TIDE -> {
                // 肆之型·打潮：连绵四段高低起伏海潮连斩（0右下挑 -> 1左上劈 -> 2宽幅逆卷 -> 3拍岸重劈！）
                if (t < 0.08F || t > 0.90F) return BladeSegment.INACTIVE;
                int step = Math.floorMod(paramInt, 4);
                float u = whipCurve((t - 0.08F) / 0.80F);
                if (step == 3) {
                    // 第 4 拍：浪涛登顶猛然拍岸下压斜劈！
                    float pitchRad = Mth.lerp(u, 66.0F, -48.0F) * Mth.DEG_TO_RAD;
                    float yawOff = Mth.lerp(u, -42.0F, 48.0F);
                    Vec3 hDir = rotateHorizontal(fwd, right, yawOff);
                    Vec3 dir = hDir.scale(Mth.cos(pitchRad)).add(up.scale(Mth.sin(pitchRad))).normalize();
                    Vec3 base = new Vec3(px, py + 0.20, pz).add(dir.scale(1.05 * tcReach));
                    Vec3 tip = new Vec3(px, py + 0.20, pz).add(dir.scale(4.85 * tcReach));
                    return new BladeSegment(base, tip, true, 1.26F);
                }
                float angDeg;
                double yB, yT;
                if (step == 0) {
                    angDeg = Mth.lerp(u, -108.0F, 96.0F);
                    yB = Mth.lerp(u, -0.45, 0.42);
                    yT = Mth.lerp(u, -0.82, 0.88);
                } else if (step == 1) {
                    angDeg = Mth.lerp(u, 112.0F, -98.0F);
                    yB = Mth.lerp(u, 0.48, -0.36);
                    yT = Mth.lerp(u, 0.92, -0.68);
                } else {
                    angDeg = Mth.lerp(u, -118.0F, 118.0F);
                    yB = Mth.sin(u * Mth.TWO_PI) * 0.22;
                    yT = Mth.sin(u * Mth.TWO_PI) * 0.48 + 0.14;
                }
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                Vec3 base = new Vec3(px, py + yB, pz).add(dir.scale(1.00 * tcReach));
                Vec3 tip = new Vec3(px, py + yT, pz).add(dir.scale(4.25 * tcReach));
                return new BladeSegment(base, tip, true, 1.14F);
            }
            case ANIM_BLESSED_RAIN -> {
                // 伍之型·乾天的慈雨：优雅凌空滑翔，划出一道柔和细长、悲悯轻盈的下落弧斩！
                if (t < 0.16F || t > 0.86F) return BladeSegment.INACTIVE;
                float u = smoothStep((t - 0.16F) / 0.68F);
                float angDeg = Mth.lerp(u, -86.0F, 88.0F);
                Vec3 hDir = rotateHorizontal(fwd, right, angDeg);
                double arcY = Mth.lerp(u, 0.82, -0.58) + Mth.sin(u * Mth.PI) * 0.25;
                Vec3 base = new Vec3(px, py + arcY * 0.55, pz).add(hDir.scale(1.10 * tcReach));
                Vec3 tip = new Vec3(px, py + arcY, pz).add(hDir.scale(4.60 * tcReach));
                return new BladeSegment(base, tip, true, 0.95F);
            }
            case ANIM_DROP_RIPPLE_THRUST -> {
                // 柒之型·雫波纹突刺（paramInt==1 为带弧线绕防的「曲」）：刀刃水平锐刺，速度最快！
                if (t < 0.08F || t > 0.72F) return BladeSegment.INACTIVE;
                boolean curved = paramInt != 0;
                float u = snapOut(Mth.clamp((t - 0.08F) / 0.44F, 0.0F, 1.0F));
                float curveYaw = curved ? Mth.sin(u * Mth.PI) * 26.0F : 0.0F;
                Vec3 dir = rotateHorizontal(fwd, right, curveYaw);
                double thrustOut = Mth.lerp(u, 0.65, 4.95) * tcReach;
                // 狭长锐利的水滴针刃（刀柄紧随剑尖突进）
                Vec3 base = new Vec3(px, py + 0.04, pz).add(dir.scale(Math.max(0.45, thrustOut - 1.85))).add(right.scale(0.14));
                Vec3 tip = new Vec3(px, py + 0.06, pz).add(dir.scale(thrustOut)).add(right.scale(0.14));
                return new BladeSegment(base, tip, true, 1.02F);
            }
            case ANIM_SPLASH_FLOW -> {
                // 玖之型·水流飞沫·乱：踏水高速闪身途中顺势拉出一记极轻快的掠身斩！
                if (t < 0.08F || t > 0.78F) return BladeSegment.INACTIVE;
                float u = snapOut((t - 0.08F) / 0.70F);
                float side = (paramInt % 2 == 0) ? 1.0F : -1.0F;
                float angDeg = Mth.lerp(u, -78.0F * side, 82.0F * side);
                Vec3 dir = rotateHorizontal(fwd, right, angDeg);
                Vec3 base = new Vec3(px, py - 0.10, pz).add(dir.scale(0.95 * tcReach));
                Vec3 tip = new Vec3(px, py + 0.08, pz).add(dir.scale(3.45 * tcReach));
                return new BladeSegment(base, tip, true, 0.96F);
            }
            case ANIM_WATERFALL_CLEAVE -> {
                // 捌之型·泷壶 原版重做：高举刀锋过顶 -> 175° 垂直天降瀑布劈地！
                if (t < 0.16F || t > 0.84F) return BladeSegment.INACTIVE;
                float u = whipCurve((t - 0.16F) / 0.56F);
                float pitchRad = Mth.lerp(u, 96.0F, -64.0F) * Mth.DEG_TO_RAD;
                Vec3 dir = fwd.scale(Mth.cos(pitchRad)).add(up.scale(Mth.sin(pitchRad))).normalize();
                Vec3 base = new Vec3(px, py + 0.30, pz).add(dir.scale(0.95 * tcReach)).add(right.scale(0.16));
                Vec3 tip = new Vec3(px, py + 0.30, pz).add(dir.scale(4.95 * tcReach)).add(right.scale(0.16));
                return new BladeSegment(base, tip, true, 1.32F);
            }
            default -> {
                return BladeSegment.INACTIVE;
            }
        }
    }

    /**
     * 服务端 140Hz 连续刀刃子步扫掠碰撞检测：
     * 在当前 tick [tStart -> tEnd] 内做 7 个连续子步，逐帧计算真实 3D 刀刃线段与周围敌怪包围盒的精确碰撞，
     * 按真实被刀锋扫到的先后顺序返回精确命中点与 3D 刀锋切线运动方向！
     */
    public static List<BladeContact> sweepBladeContacts(
            int animType, float tStart, float tEnd, int paramInt, boolean tc, boolean sun,
            Vec3 originStart, Vec3 originEnd, float yawStart, float yawEnd,
            List<LivingEntity> candidates, Map<Integer, Integer> entityHitCooldowns,
            int rehitCooldownTicks, double bladeThickness
    ) {
        List<BladeContact> contacts = new ArrayList<>();
        if (candidates.isEmpty() || tEnd <= tStart) return contacts;

        Vec3 prevMid = null;
        Vec3 prevTip = null;
        // 采样上一微步起点用于计算子步 0 的真实切线方向
        float preT = Math.max(0.0F, tStart - (tEnd - tStart) / SWEEP_SUB_STEPS);
        BladeSegment preSeg = sampleBlade(animType, preT, paramInt, tc, sun, originStart.x, originStart.y, originStart.z, yawStart);
        if (preSeg.active()) {
            prevTip = preSeg.tip();
            prevMid = preSeg.base().lerp(preSeg.tip(), 0.65);
        }

        for (int s = 1; s <= SWEEP_SUB_STEPS; s++) {
            float frac = s / (float) SWEEP_SUB_STEPS;
            float t = Mth.lerp(frac, tStart, tEnd);
            double ox = Mth.lerp(frac, originStart.x, originEnd.x);
            double oy = Mth.lerp(frac, originStart.y, originEnd.y);
            double oz = Mth.lerp(frac, originStart.z, originEnd.z);
            float yaw = Mth.rotLerp(frac, yawStart, yawEnd);

            BladeSegment seg = sampleBlade(animType, t, paramInt, tc, sun, ox, oy, oz, yaw);
            if (!seg.active()) {
                prevTip = null;
                prevMid = null;
                continue;
            }

            Vec3 curTip = seg.tip();
            Vec3 curMid = seg.base().lerp(curTip, 0.65);
            Vec3 cutTangent;
            if (prevTip != null && curTip.distanceToSqr(prevTip) > 1.0E-5) {
                cutTangent = curTip.subtract(prevTip).normalize();
            } else if (prevMid != null && curMid.distanceToSqr(prevMid) > 1.0E-5) {
                cutTangent = curMid.subtract(prevMid).normalize();
            } else {
                cutTangent = Vec3.directionFromRotation(0.0F, yaw).normalize();
            }

            // 同时把玩家身体核心向刀刃延伸（避免敌人贴脸站进刀柄内侧漏判）
            Vec3 hiltRoot = new Vec3(ox, oy, oz).lerp(seg.base(), 0.35);

            for (LivingEntity ent : candidates) {
                if (!ent.isAlive()) continue;
                int id = ent.getId();
                if (entityHitCooldowns.getOrDefault(id, 0) > 0) continue;

                AABB box = ent.getBoundingBox().inflate(bladeThickness);
                Vec3 hitPt = closestSegmentPointIfIntersecting(hiltRoot, curTip, box);
                if (hitPt != null) {
                    entityHitCooldowns.put(id, rehitCooldownTicks);
                    contacts.add(new BladeContact(ent, hitPt, cutTangent, frac));
                }
            }

            prevTip = curTip;
            prevMid = curMid;
        }
        return contacts;
    }

    /**
     * 精确 3D 线段 [a, b] 与轴对齐包围盒 AABB 相交检测（Slab 算法 + 内部点保底）：
     * 若相交返回最贴近刀锋切入处的精确 3D 接触点，否则返回 null。
     */
    public static Vec3 closestSegmentPointIfIntersecting(Vec3 a, Vec3 b, AABB box) {
        if (box.contains(a)) return a;
        if (box.contains(b)) return b;

        Vec3 d = b.subtract(a);
        double tMin = 0.0;
        double tMax = 1.0;

        // X slab
        if (Math.abs(d.x) < 1.0E-7) {
            if (a.x < box.minX || a.x > box.maxX) return null;
        } else {
            double inv = 1.0 / d.x;
            double t1 = (box.minX - a.x) * inv;
            double t2 = (box.maxX - a.x) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        // Y slab
        if (Math.abs(d.y) < 1.0E-7) {
            if (a.y < box.minY || a.y > box.maxY) return null;
        } else {
            double inv = 1.0 / d.y;
            double t1 = (box.minY - a.y) * inv;
            double t2 = (box.maxY - a.y) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        // Z slab
        if (Math.abs(d.z) < 1.0E-7) {
            if (a.z < box.minZ || a.z > box.maxZ) return null;
        } else {
            double inv = 1.0 / d.z;
            double t1 = (box.minZ - a.z) * inv;
            double t2 = (box.maxZ - a.z) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return null;
        }

        double hitT = Mth.clamp((tMin + tMax) * 0.5, 0.0, 1.0);
        return a.add(d.scale(hitT));
    }

    public static Vec3 rotateHorizontal(Vec3 fwd, Vec3 right, float deg) {
        float rad = deg * Mth.DEG_TO_RAD;
        return fwd.scale(Mth.cos(rad)).add(right.scale(-Mth.sin(rad))).normalize();
    }

    public static float smoothStep(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    public static float snapOut(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    public static float whipCurve(float x) {
        return snapOut(smoothStep(x));
    }

    public record BladeSegment(Vec3 base, Vec3 tip, boolean active, float intensity) {
        public static final BladeSegment INACTIVE = new BladeSegment(Vec3.ZERO, Vec3.ZERO, false, 0.0F);
    }

    public record BladeContact(LivingEntity target, Vec3 contactPoint, Vec3 cutTangent, float subStepFrac) {}
}
