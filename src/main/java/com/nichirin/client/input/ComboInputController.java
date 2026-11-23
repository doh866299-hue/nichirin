package com.nichirin.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.nichirin.breathing.WaterForm;
import com.nichirin.client.ClientBreathState;
import com.nichirin.client.NichirinClient;
import com.nichirin.item.WaterNichirinBladeItem;
import com.nichirin.network.BreathingActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 方案 A：原版《鬼灭之刃》水之呼吸「极速切型 + 右键一键瞬发 + 生生流转长按回旋叠势」输入控制器
 * <ul>
 *   <li>Shift + 鼠标滚轮 或 Z / C 键：0 延迟丝滑切换『水之呼吸 · 壹之型 ~ 拾壹之型』</li>
 *   <li>右键按下：瞬间释放当前选中的剑型（带空中/侧移/WASD 上下文自动变招，如横水车、雫波纹·曲、全向水流飞沫）</li>
 *   <li>选中『拾之型·生生流转』时：按住右键持续回旋叠势（1→4圈越转越快越大），松开右键立即斩出高势能收尾一击！</li>
 *   <li>左键：保留原版清脆平A与真实刀锋物理互动</li>
 *   <li>R 键：短按切换『全集中·常中』，长按深呼吸快速蓄气；G 键：开关全十一型速查表</li>
 * </ul>
 */
public final class ComboInputController {
    private static boolean fluxRightHeld = false;

    private static boolean breathKeyWasDown = false;
    private static int breathKeyHoldTicks = 0;
    private static boolean inhalingSent = false;

    private ComboInputController() {}

    /** Shift + 鼠标滚轮丝滑切型（不按 Shift 时 100% 放行原版热键栏滚动！） */
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null
                || mc.gui.screen() != null || mc.gui.overlay() != null
                || !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        if (!WaterNichirinBladeItem.isWaterBlade(player.getMainHandItem())) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }
        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.01) {
            return;
        }
        event.setCanceled(true);
        // 向上滚切上一型，向下滚切下一型
        int delta = dy > 0.0 ? -1 : 1;
        switchFormWithFeedback(player, delta);
    }

    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null
                || mc.gui.screen() != null || mc.gui.overlay() != null
                || !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (!WaterNichirinBladeItem.isWaterBlade(player.getMainHandItem())) {
            return;
        }

        int button = event.getButton();
        int action = event.getAction();

        // ====== 右键（SDL3: MOUSE_BUTTON_RIGHT = 3）：一键瞬发当前选中的水之呼吸剑型！ ======
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (action == InputConstants.PRESS) {
                if (!player.isShiftKeyDown() && isTargetingInteractable(mc)) {
                    return;
                }
                event.setCanceled(true);

                WaterForm selected = ClientBreathState.getSelectedForm();
                boolean airborne = !player.onGround() && !player.isInWater();
                boolean sneak = player.isShiftKeyDown();
                int fwdSign = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
                int strafeSign = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
                int ctx = BreathingActionPayload.packContext(airborne, sneak, fwdSign, strafeSign);

                if (selected == WaterForm.TENTH_CONSTANT_FLUX) {
                    fluxRightHeld = true;
                }
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_CAST_FORM, selected.ordinal(), ctx)
                );
            } else if (action == InputConstants.RELEASE) {
                if (fluxRightHeld) {
                    event.setCanceled(true);
                    fluxRightHeld = false;
                    ClientPacketDistributor.sendToServer(
                            new BreathingActionPayload(BreathingActionPayload.ACTION_FLUX_RELEASE, 0, 0)
                    );
                }
            }
            return;
        }

        // ====== 左键（SDL3: MOUSE_BUTTON_LEFT = 1）：原生清脆平A + 刀锋底层物理扫掠 ======
        if (button == InputConstants.MOUSE_BUTTON_LEFT && action == InputConstants.PRESS) {
            if (player.getAttackStrengthScale(0.5F) >= 0.35F) {
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_BASIC_SWING, 0, 0)
                );
            }
        }
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        // G 键：开关全十一型速查目录
        while (NichirinClient.KEY_TOGGLE_GUIDE.consumeClick()) {
            ClientBreathState.toggleGuide();
            if (player != null) {
                player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.55F, 1.3F);
            }
        }

        boolean validPlay = player != null && mc.level != null
                && mc.gui.screen() == null && mc.gui.overlay() == null
                && WaterNichirinBladeItem.isWaterBlade(player.getMainHandItem());

        if (!validPlay) {
            while (NichirinClient.KEY_PREV_FORM.consumeClick()) {}
            while (NichirinClient.KEY_NEXT_FORM.consumeClick()) {}
            while (NichirinClient.KEY_SWITCH_STYLE.consumeClick()) {}
            resetTransientInput(player != null && mc.getConnection() != null);
            return;
        }

        // Z / C 键：0 延迟快捷切型（上一型 / 下一型）
        while (NichirinClient.KEY_PREV_FORM.consumeClick()) {
            switchFormWithFeedback(player, -1);
        }
        while (NichirinClient.KEY_NEXT_FORM.consumeClick()) {
            switchFormWithFeedback(player, 1);
        }

        // X 键：流派切换（预留日呼独立十三型入口）
        while (NichirinClient.KEY_SWITCH_STYLE.consumeClick()) {
            ClientPacketDistributor.sendToServer(
                    new BreathingActionPayload(BreathingActionPayload.ACTION_SWITCH_STYLE, 0, 0)
            );
        }

        // R 键：短按切换全集中·常中，长按深呼吸快速回气
        boolean breathDown = NichirinClient.KEY_BREATH.isDown();
        if (breathDown) {
            if (!breathKeyWasDown) {
                breathKeyWasDown = true;
                breathKeyHoldTicks = 0;
                inhalingSent = false;
            }
            breathKeyHoldTicks++;
            if (breathKeyHoldTicks >= 7 && !inhalingSent) {
                inhalingSent = true;
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_SET_INHALE, 1, 0)
                );
            }
        } else if (breathKeyWasDown) {
            if (inhalingSent) {
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_SET_INHALE, 0, 0)
                );
            } else if (breathKeyHoldTicks > 0) {
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_TOGGLE_TOTAL_CONCENTRATION, 0, 0)
                );
            }
            breathKeyWasDown = false;
            breathKeyHoldTicks = 0;
            inhalingSent = false;
        }
    }

    private static void switchFormWithFeedback(LocalPlayer player, int delta) {
        ClientBreathState.cycleSelectedForm(delta);
        float pitch = delta < 0 ? 1.24F : 1.44F;
        player.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.36F, pitch);
    }

    private static void resetTransientInput(boolean sendPacket) {
        if (fluxRightHeld) {
            fluxRightHeld = false;
            if (sendPacket) {
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_FLUX_RELEASE, 0, 0)
                );
            }
        }
        if (inhalingSent && sendPacket) {
            ClientPacketDistributor.sendToServer(
                    new BreathingActionPayload(BreathingActionPayload.ACTION_SET_INHALE, 0, 0)
            );
        }
        breathKeyWasDown = false;
        breathKeyHoldTicks = 0;
        inhalingSent = false;
    }

    public static void resetAll() {
        fluxRightHeld = false;
        breathKeyWasDown = false;
        breathKeyHoldTicks = 0;
        inhalingSent = false;
    }

    private static boolean isTargetingInteractable(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (hit instanceof EntityHitResult ehr) {
            return ehr.getEntity() instanceof AbstractVillager;
        }
        if (hit instanceof BlockHitResult bhr && mc.level != null) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            if (mc.level.getBlockEntity(pos) != null) {
                return true;
            }
            Block block = state.getBlock();
            return block instanceof DoorBlock
                    || block instanceof TrapDoorBlock
                    || block instanceof FenceGateBlock
                    || block instanceof ButtonBlock
                    || block instanceof LeverBlock
                    || block instanceof BedBlock;
        }
        return false;
    }

    /** 兼容保留接口（方案 A 无旧式站桩蓄力） */
    public static int getChargingMode() { return 0; }
    public static float getChargeProgress() { return 0.0F; }
    public static boolean isFluxHolding() { return fluxRightHeld; }
}
