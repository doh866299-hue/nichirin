package com.nichirin.client.anim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nichirin.item.WaterNichirinBladeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第一人称日轮刀专属动作渲染 + 第三人称人物翻滚/旋身/化龙冲锋姿态控制器
 */
public final class FirstPersonKatanaRenderer {
    /** 记录在 Pre 中 pushPose 的第三人称玩家 ID，确保 Post 精准成对 popPose */
    private static final Set<Integer> PUSHED_PLAYERS = ConcurrentHashMap.newKeySet();

    private FirstPersonKatanaRenderer() {}

    /**
     * 接管主手水之日轮刀的第一人称渲染，播放专属全剑型挥刀/旋斩/居合蓄力/止水动作（不影响副手）
     */
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!WaterNichirinBladeItem.isWaterBlade(event.getItemStack())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || event.getPlayerState().avatarRenderState == null) {
            return;
        }
        if (event.getPlayerState().firstPersonHandsAndItems.isScoping) {
            return;
        }

        event.setCanceled(true);

        PoseStack poseStack = event.getPoseStack();
        AvatarRenderState avatarState = event.getPlayerState().avatarRenderState;
        int invert = avatarState.mainArm == HumanoidArm.RIGHT ? 1 : -1;

        SwordChoreography.FirstPersonPose fp = SwordChoreography.computeFirstPersonPose(
                player, event.getPartialTick(), event.getSwingProgress(), event.getEquipProgress()
        );

        poseStack.pushPose();
        // 原版基础持刀坐标 + 专属剑术编排偏移与旋转
        poseStack.translate(invert * (0.56F + fp.tx()), -0.50F + fp.ty(), -0.70F + fp.tz());
        poseStack.rotateDegrees(Axis.YP, invert * fp.rotY());
        poseStack.rotateDegrees(Axis.XP, fp.rotX());
        poseStack.rotateDegrees(Axis.ZP, invert * fp.rotZ());

        event.getPlayerState().firstPersonHandsAndItems.mainHandRenderState.submit(
                poseStack,
                event.getSubmitNodeCollector(),
                event.getPackedLight(),
                OverlayTexture.NO_OVERLAY,
                0
        );
        poseStack.popPose();
    }

    /**
     * 第三人称人物动作：水车 360° 前空翻、横水车/扭转漩涡疾旋、生生流转化龙螺旋突进！
     */
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre<?> event) {
        AvatarRenderState state = event.getRenderState();
        SwordChoreography.ThirdPersonBodyPose body =
                SwordChoreography.computeThirdPersonBodyPose(state.id, event.getPartialTick());
        if (!body.isActive()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        PUSHED_PLAYERS.add(state.id);

        // 围绕玩家胸腹质心（y = 0.95）执行前空翻/水平旋身/侧倾
        poseStack.translate(0.0, 0.95, 0.0);
        if (body.spinYaw() != 0.0F) {
            poseStack.rotateDegrees(Axis.YP, -body.spinYaw());
        }
        if (body.flipPitch() != 0.0F) {
            poseStack.rotateDegrees(Axis.XP, body.flipPitch());
        }
        if (body.leanPitch() != 0.0F) {
            poseStack.rotateDegrees(Axis.XP, body.leanPitch());
        }
        if (body.roll() != 0.0F) {
            poseStack.rotateDegrees(Axis.ZP, body.roll());
        }
        poseStack.translate(0.0, -0.95, 0.0);
    }

    public static void onRenderPlayerPost(RenderPlayerEvent.Post<?> event) {
        int id = event.getRenderState().id;
        if (PUSHED_PLAYERS.remove(id)) {
            event.getPoseStack().popPose();
        }
    }

    public static void clearAll() {
        PUSHED_PLAYERS.clear();
    }
}
