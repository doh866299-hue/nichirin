package com.nichirin.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.nichirin.breathing.SunForm;
import com.nichirin.breathing.WaterForm;
import com.nichirin.client.ClientBreathState;
import com.nichirin.client.NichirinClient;
import com.nichirin.item.SunNichirinBladeItem;
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
 * 方案 A：水之日轮刀（全十一型）与 日之日轮刀（全十三型 · 含第十三型无缝连舞圆环）统一输入控制器
 * <ul>
 *   <li>Shift + 鼠标滚轮 或 Z / C 键：0 延迟丝滑切换当前所持日轮刀对应的呼吸法剑型</li>
 *   <li>右键按下：瞬间释放当前选中的剑型（携带空中/潜行/疾跑/WASD 上下文自动变招）</li>
 *   <li>水·拾之型「生生流转」：按住右键持续回旋叠势 1→4 圈，松手立即斩出收尾一击</li>
 *   <li>日·第十三型「日之圆环」：点按或按住右键将 1→12 型无缝串联高速循环连舞！</li>
 *   <li>R 键：短按切换「全集中·常中」（日呼点燃炽红赫刀），长按深呼吸蓄气；G 键：开关全剑型速查目录</li>
 * </ul>
 */
public final class ComboInputController {
    private static boolean fluxRightHeld = false;
    private static boolean sunCycleRightHeld = false;
    private static int sunCycleHoldSyncTicker = 0;

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
        if (!WaterNichirinBladeItem.isAnyNichirinBlade(player.getMainHandItem())) {
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
        if (!WaterNichirinBladeItem.isAnyNichirinBlade(player.getMainHandItem())) {
            return;
        }

        int button = event.getButton();
        int action = event.getAction();
        boolean isSun = SunNichirinBladeItem.isSunBlade(player.getMainHandItem());

        // ====== 右键（SDL3: MOUSE_BUTTON_RIGHT = 3）：一键瞬发当前选中的剑型！ ======
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (action == InputConstants.PRESS) {
                if (!player.isShiftKeyDown() && isTargetingInteractable(mc)) {
                    return;
                }
                event.setCanceled(true);

                int ctx = packCurrentContext(mc, player);
                if (isSun) {
                    SunForm selected = ClientBreathState.getSelectedSunForm();
                    if (selected == SunForm.THIRTEENTH_SUN_CYCLE) {
                        sunCycleRightHeld = true;
                        sunCycleHoldSyncTicker = 0;
                    }
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    ClientPacketDistributor.sendToServer(
                            new BreathingActionPayload(BreathingActionPayload.ACTION_CAST_FORM, selected.ordinal(), ctx)
                    );
                } else {
                    WaterForm selected = ClientBreathState.getSelectedForm();
                    if (selected == WaterForm.TENTH_CONSTANT_FLUX) {
                        fluxRightHeld = true;
                    }
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                    ClientPacketDistributor.sendToServer(
                            new BreathingActionPayload(BreathingActionPayload.ACTION_CAST_FORM, selected.ordinal(), ctx)
                    );
                }
            } else if (action == InputConstants.RELEASE) {
                if (fluxRightHeld) {
                    event.setCanceled(true);
                    fluxRightHeld = false;
                    ClientPacketDistributor.sendToServer(
                            new BreathingActionPayload(BreathingActionPayload.ACTION_FLUX_RELEASE, 0, 0)
                    );
                }
                if (sunCycleRightHeld) {
                    event.setCanceled(true);
                    sunCycleRightHeld = false;
                    ClientPacketDistributor.sendToServer(
                            new BreathingActionPayload(BreathingActionPayload.ACTION_SUN_CYCLE_HOLD, 0, 0)
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

        // G 键：开关全剑型速查目录
        while (NichirinClient.KEY_TOGGLE_GUIDE.consumeClick()) {
            ClientBreathState.toggleGuide();
            if (player != null) {
                player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.55F, 1.3F);
            }
        }

        boolean validPlay = player != null && mc.level != null
                && mc.gui.screen() == null && mc.gui.overlay() == null
                && WaterNichirinBladeItem.isAnyNichirinBlade(player.getMainHandItem());

        if (!validPlay) {
            while (NichirinClient.KEY_PREV_FORM.consumeClick()) {}
            while (NichirinClient.KEY_NEXT_FORM.consumeClick()) {}
            while (NichirinClient.KEY_SWITCH_STYLE.consumeClick()) {}
            resetTransientInput(player != null && mc.getConnection() != null);
            return;
        }

        // 切刀保护：从水刀换日刀（或反向）时安全松开旧流派长按状态
        boolean isSun = SunNichirinBladeItem.isSunBlade(player.getMainHandItem());
        if (fluxRightHeld && isSun) {
            fluxRightHeld = false;
            ClientPacketDistributor.sendToServer(new BreathingActionPayload(BreathingActionPayload.ACTION_FLUX_RELEASE, 0, 0));
        }
        if (sunCycleRightHeld && (!isSun || ClientBreathState.getSelectedSunForm() != SunForm.THIRTEENTH_SUN_CYCLE)) {
            sunCycleRightHeld = false;
            ClientPacketDistributor.sendToServer(new BreathingActionPayload(BreathingActionPayload.ACTION_SUN_CYCLE_HOLD, 0, 0));
        }
        // 按住右键进行第十三型圆环连舞时，定期同步最新 WASD/空中/潜行上下文！
        if (sunCycleRightHeld) {
            if (++sunCycleHoldSyncTicker >= 3) {
                sunCycleHoldSyncTicker = 0;
                int ctx = packCurrentContext(mc, player);
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_SUN_CYCLE_HOLD, 1, ctx)
                );
            }
        }

        // Z / C 键：0 延迟快捷切型（上一型 / 下一型）
        while (NichirinClient.KEY_PREV_FORM.consumeClick()) {
            switchFormWithFeedback(player, -1);
        }
        while (NichirinClient.KEY_NEXT_FORM.consumeClick()) {
            switchFormWithFeedback(player, 1);
        }

        // X 键：流派说明提示（水/日呼吸已分别绑定各自独立的日轮刀）
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

    private static int packCurrentContext(Minecraft mc, LocalPlayer player) {
        boolean airborne = !player.onGround() && !player.isInWater();
        boolean sneak = player.isShiftKeyDown();
        boolean sprinting = player.isSprinting();
        int fwdSign = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        int strafeSign = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        return BreathingActionPayload.packContext(airborne, sneak, fwdSign, strafeSign, sprinting);
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
        if (sunCycleRightHeld) {
            sunCycleRightHeld = false;
            if (sendPacket) {
                ClientPacketDistributor.sendToServer(
                        new BreathingActionPayload(BreathingActionPayload.ACTION_SUN_CYCLE_HOLD, 0, 0)
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
        sunCycleRightHeld = false;
        sunCycleHoldSyncTicker = 0;
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

    /** 兼容保留接口 */
    public static int getChargingMode() { return 0; }
    public static float getChargeProgress() { return 0.0F; }
    public static boolean isFluxHolding() { return fluxRightHeld; }
}
