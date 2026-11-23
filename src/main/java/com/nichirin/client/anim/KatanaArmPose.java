package com.nichirin.client.anim;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * 鬼灭武士专属第三人称日轮刀持刀手臂姿态（通过 NeoForge EnumProxy + IArmPoseTransformer 实现）：
 * - 常态站立（副手为空）：双手合握日轮刀「中段构」武士持刀势
 * - 常态行走/奔跑：平滑切换为鬼灭招牌「侧后斜垂拖刀」奔跑姿态
 * - 蓄力/全 8 型水之呼吸：双臂严格随剑型编排大开大合挥砍、高举、横扫与止水横刀！
 */
public final class KatanaArmPose {
    public static final EnumProxy<HumanoidModel.ArmPose> KATANA_POSE_PROXY = new EnumProxy<>(
            HumanoidModel.ArmPose.class,
            false,
            true,
            (IArmPoseTransformer) KatanaArmPose::applyTransform
    );

    private KatanaArmPose() {}

    public static HumanoidModel.ArmPose getArmPose() {
        return KATANA_POSE_PROXY.getValue();
    }

    private static void applyTransform(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm) {
        boolean isRight = (arm == HumanoidArm.RIGHT);
        float sign = isRight ? 1.0F : -1.0F;
        ModelPart mainArm = isRight ? model.rightArm : model.leftArm;
        ModelPart offArm = isRight ? model.leftArm : model.rightArm;
        boolean offHandEmpty = isRight ? state.leftHandItemStack.isEmpty() : state.rightHandItemStack.isEmpty();

        int entityId = (state instanceof AvatarRenderState avatar) ? avatar.id : -1;
        SwordChoreography.PlayerSwordAnim anim = entityId >= 0 ? SwordChoreography.get(entityId) : null;
        float pt = state.partialTick;

        // ====== 1. 长按蓄力架势（居合拔刀蓄势 / 止水横刀） ======
        if (anim != null && anim.chargingMode != 0) {
            if (anim.chargingMode == 1) {
                // 生生流转蓄力：右臂向侧后腰际拉满蓄势，左臂护前
                mainArm.xRot = 0.68F;
                mainArm.yRot = 0.34F * sign;
                mainArm.zRot = 0.36F * sign;
                if (offHandEmpty) {
                    offArm.xRot = -0.92F;
                    offArm.yRot = 0.50F * sign;
                    offArm.zRot = -0.10F * sign;
                }
            } else {
                // 拾壹之型·凪 蓄势：双臂平沉横刀置于胸前
                mainArm.xRot = -1.04F;
                mainArm.yRot = -0.68F * sign;
                mainArm.zRot = 0.54F * sign;
                if (offHandEmpty) {
                    offArm.xRot = -0.96F;
                    offArm.yRot = 0.62F * sign;
                    offArm.zRot = -0.22F * sign;
                }
            }
            return;
        }

        // ====== 1.5. 凪弹反 / 幻日虹残影反杀瞬间极速挥刀 ======
        if (anim != null && anim.isParryActive()) {
            float su = anim.getParryProgress(pt);
            float d = anim.swingAlt ? 1.0F : -1.0F;
            mainArm.xRot = -1.36F;
            mainArm.yRot = Mth.lerp(su, 0.85F * d, -1.15F * d) * sign;
            mainArm.zRot = 0.58F * sign;
            if (offHandEmpty) {
                offArm.xRot = -0.96F;
                offArm.yRot = 0.62F * sign;
                offArm.zRot = -0.20F * sign;
            }
            return;
        }

        // ====== 2. 活跃水/日之呼吸剑型第三人称双臂挥舞动作 ======
        if (anim != null && anim.animType != SwordChoreography.ANIM_NONE) {
            float t = anim.getProgress(pt);
            switch (anim.animType) {
                case SwordChoreography.ANIM_SURFACE_SLASH -> {
                    // 壹之型·水面斩 / 圆舞：前 20% 蓄力引刀 -> 鞭击瞬发横扫 -> 残心定格
                    float sweep = t < 0.20F
                            ? Mth.lerp(SwordChoreography.smoothStep(t / 0.20F), -0.45F, 1.05F)
                            : Mth.lerp(SwordChoreography.whipCurve(Mth.clamp((t - 0.20F) / 0.44F, 0.0F, 1.0F)), 1.05F, -1.35F);
                    mainArm.xRot = -1.32F;
                    mainArm.yRot = sweep * sign;
                    mainArm.zRot = 0.62F * sign;
                    if (offHandEmpty) {
                        offArm.xRot = -0.75F;
                        offArm.yRot = (0.35F + sweep * 0.35F) * sign;
                        offArm.zRot = -0.18F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_WATER_WHEEL -> {
                    // 贰之型·水车：起势压刀 -> 凌空伸展 360° 纵翻
                    float u = SwordChoreography.smoothStep(Mth.clamp(t / 0.14F, 0.0F, 1.0F));
                    mainArm.xRot = Mth.lerp(u, -0.75F, -2.25F);
                    mainArm.yRot = -0.22F * sign;
                    mainArm.zRot = 0.08F * sign;
                    if (offHandEmpty) {
                        offArm.xRot = Mth.lerp(u, -0.72F, -2.18F);
                        offArm.yRot = 0.38F * sign;
                        offArm.zRot = -0.08F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_LATERAL_WHEEL -> {
                    // 贰之型·改 横水车：缩身引刀 -> 侧平伸直横展疾旋
                    float u = SwordChoreography.smoothStep(Mth.clamp(t / 0.14F, 0.0F, 1.0F));
                    mainArm.xRot = -1.42F;
                    mainArm.yRot = Mth.lerp(u, 0.72F, 0.18F) * sign;
                    mainArm.zRot = Mth.lerp(u, 0.32F, 1.12F) * sign;
                    if (offHandEmpty) {
                        offArm.xRot = -0.72F;
                        offArm.yRot = 0.55F * sign;
                        offArm.zRot = -0.45F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_FLOWING_DANCE -> {
                    // 参之型·流流舞 / 炎舞：极短蓄势 -> 鞭击闪电斜斩
                    float u = t < 0.10F ? 0.0F : SwordChoreography.whipCurve((t - 0.10F) / 0.74F);
                    int step = anim.paramInt % 3;
                    if (step == 0) {
                        mainArm.xRot = Mth.lerp(u, -0.55F, -2.12F);
                        mainArm.yRot = Mth.lerp(u, -0.88F, 0.74F) * sign;
                        mainArm.zRot = 0.38F * sign;
                    } else if (step == 1) {
                        mainArm.xRot = Mth.lerp(u, -2.18F, -0.56F);
                        mainArm.yRot = Mth.lerp(u, 0.80F, -0.98F) * sign;
                        mainArm.zRot = 0.45F * sign;
                    } else {
                        mainArm.xRot = -1.35F;
                        mainArm.yRot = Mth.lerp(u, 0.92F, -1.30F) * sign;
                        mainArm.zRot = 0.65F * sign;
                    }
                    if (offHandEmpty) {
                        offArm.xRot = -0.85F;
                        offArm.yRot = 0.42F * sign;
                        offArm.zRot = -0.25F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_WHIRLPOOL -> {
                    // 陆之型·扭转漩涡：前 11% 内收蓄力 -> 加速螺旋大展上撩
                    float u = t < 0.11F ? 0.0F : (t - 0.11F) / 0.89F;
                    mainArm.xRot = Mth.lerp(u, -0.72F, -2.35F);
                    mainArm.yRot = (t < 0.11F ? -0.62F : -0.20F) * sign;
                    mainArm.zRot = (0.72F + 0.25F * Mth.sin(u * Mth.TWO_PI * 2.0F)) * sign;
                    if (offHandEmpty) {
                        offArm.xRot = -1.15F;
                        offArm.yRot = 0.58F * sign;
                        offArm.zRot = -0.32F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_WATERFALL_DIVE -> {
                    // 捌之型·泷壶俯冲：双手高举日轮刀过顶准备怒劈！
                    mainArm.xRot = -2.85F;
                    mainArm.yRot = -0.28F * sign;
                    mainArm.zRot = 0.06F * sign;
                    if (offHandEmpty) {
                        offArm.xRot = -2.80F;
                        offArm.yRot = 0.36F * sign;
                        offArm.zRot = -0.06F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_WATERFALL_IMPACT -> {
                    // 捌之型·泷壶触地：狠狠下劈至地面后缓收
                    float u = Mth.clamp(t * 2.8F, 0.0F, 1.0F);
                    mainArm.xRot = Mth.lerp(u, -2.65F, -0.38F);
                    mainArm.yRot = -0.18F * sign;
                    mainArm.zRot = 0.12F * sign;
                    if (offHandEmpty) {
                        offArm.xRot = Mth.lerp(u, -2.55F, -0.42F);
                        offArm.yRot = 0.34F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_CONSTANT_FLUX -> {
                    // 拾之型·生生流转：人刀合一螺旋冲锋挥斩！
                    float phase = t * Mth.TWO_PI * (2 + Math.max(1, anim.paramInt));
                    mainArm.xRot = -1.45F + Mth.sin(phase) * 0.55F;
                    mainArm.yRot = Mth.cos(phase) * 0.65F * sign;
                    mainArm.zRot = (0.75F + 0.32F * Mth.sin(phase)) * sign;
                    if (offHandEmpty) {
                        offArm.xRot = -1.15F;
                        offArm.yRot = 0.52F * sign;
                        offArm.zRot = -0.35F * sign;
                    }
                    return;
                }
                case SwordChoreography.ANIM_DEAD_CALM -> {
                    if (anim.parrySnapTicks > 0) {
                        float su = 1.0F - (anim.parrySnapTicks - pt) / Math.max(1.0F, anim.parrySnapMax);
                        float d = anim.swingAlt ? 1.0F : -1.0F;
                        mainArm.xRot = -1.36F;
                        mainArm.yRot = Mth.lerp(su, 0.85F * d, -1.15F * d) * sign;
                        mainArm.zRot = 0.58F * sign;
                    } else {
                        mainArm.xRot = -1.04F;
                        mainArm.yRot = -0.68F * sign;
                        mainArm.zRot = 0.54F * sign;
                    }
                    if (offHandEmpty) {
                        offArm.xRot = -0.96F;
                        offArm.yRot = 0.62F * sign;
                        offArm.zRot = -0.20F * sign;
                    }
                    return;
                }
                default -> {}
            }
        }

        // ====== 3. 常态鬼灭武士持刀姿态（告别僵硬原版直棍！） ======
        float moveBlend = Mth.clamp(state.walkAnimationSpeed * 1.9F, 0.0F, 1.0F);
        float walkSwing = Mth.cos(state.walkAnimationPos * 0.6662F) * 0.85F * state.walkAnimationSpeed;

        if (offHandEmpty) {
            // 站立：沉稳帅气的双手合握「中段构」武士持刀势
            // 奔跑：平滑切换为鬼灭招牌「右臂后展、侧后斜拖日轮刀」疾跑姿！
            mainArm.xRot = Mth.lerp(moveBlend, -0.84F, 0.56F + walkSwing * 0.16F);
            mainArm.yRot = Mth.lerp(moveBlend, -0.42F, 0.16F) * sign;
            mainArm.zRot = Mth.lerp(moveBlend, 0.08F, 0.28F) * sign;

            // 左臂：站立时自然前收合握刀柄尾端；奔跑时自然前摆保持身体平衡
            offArm.xRot = Mth.lerp(moveBlend, -0.78F, -walkSwing * 0.92F - 0.14F);
            offArm.yRot = Mth.lerp(moveBlend, 0.56F, 0.08F) * sign;
            offArm.zRot = Mth.lerp(moveBlend, -0.06F, -0.14F) * sign;
        } else {
            // 副手持有火把/盾牌等物品时：主手采用潇洒的「富冈义勇单手侧垂斜刀势」
            mainArm.xRot = Mth.lerp(moveBlend, 0.28F, 0.58F + walkSwing * 0.15F);
            mainArm.yRot = 0.14F * sign;
            mainArm.zRot = Mth.lerp(moveBlend, 0.20F, 0.28F) * sign;
            // 保留副手自然持物微抬
            offArm.xRot = offArm.xRot * 0.5F - (float) (Math.PI / 10.0);
            offArm.yRot = 0.0F;
        }
    }

    private static float smoothStep(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
