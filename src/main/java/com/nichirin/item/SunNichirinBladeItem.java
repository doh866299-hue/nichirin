package com.nichirin.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * 日之日轮刀（始源漆黑刀身，全集中 / 第十三型圆环 / 日晕之龙爆发时实时烧红化作「炽红赫刀」！）
 * 铭刻原版《鬼灭之刃》日之呼吸 · 火之神神乐全十三型。
 */
public class SunNichirinBladeItem extends Item {
    public SunNichirinBladeItem(Properties properties) {
        super(properties);
    }

    public static boolean isSunBlade(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SunNichirinBladeItem;
    }

    /**
     * 更新日之日轮刀 3D 模型变体：
     * 0.0 = 漆黑日轮刀（常态） | 1.0 = 炽红赫刀（全集中 / 第十三型圆环 / 奥义爆发）
     */
    public static void syncRedBladeVisual(ItemStack stack, boolean redBladeActive) {
        if (!isSunBlade(stack)) return;
        float desired = redBladeActive ? 1.0F : 0.0F;
        CustomModelData cur = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        Float curVal = cur != null ? cur.getFloat(0) : null;
        if (curVal != null && Math.abs(curVal - desired) < 0.05F) {
            return;
        }
        if (!redBladeActive) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(desired), List.of(), List.of(), List.of()));
        }
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        // 赫刀点燃/熄灭刷新模型时不触发物品下沉重拔动画
        return slotChanged;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay tooltipDisplay,
            Consumer<Component> tooltipAdder,
            TooltipFlag flag
    ) {
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.sun_blade.style")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.sun_blade.combo_1")
                .withStyle(ChatFormatting.WHITE));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.sun_blade.combo_2")
                .withStyle(ChatFormatting.YELLOW));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.sun_blade.breath")
                .withStyle(ChatFormatting.RED));
    }
}
