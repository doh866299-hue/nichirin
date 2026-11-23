package com.nichirin.item;

import com.nichirin.breathing.BreathingStyle;
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
 * 日轮刀：支持按 X 键实时切换「水之呼吸 ↔ 日之呼吸·火之神神乐」，
 * 刀身 3D 模型随流派动态切换（深海蓝刀 / 漆黑日轮刀 / 全集中炽红赫刀！）
 */
public class WaterNichirinBladeItem extends Item {
    public WaterNichirinBladeItem(Properties properties) {
        super(properties);
    }

    public static boolean isWaterBlade(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof WaterNichirinBladeItem;
    }

    /** 任意日轮刀（水之日轮刀 或 日之日轮刀）统一判定 */
    public static boolean isAnyNichirinBlade(ItemStack stack) {
        return isWaterBlade(stack) || SunNichirinBladeItem.isSunBlade(stack);
    }

    /** 根据手持日轮刀直接解析所属呼吸流派 */
    public static BreathingStyle resolveBladeStyle(ItemStack stack) {
        return SunNichirinBladeItem.isSunBlade(stack) ? BreathingStyle.SUN : BreathingStyle.WATER;
    }

    /**
     * 更新刀身 3D 外观对应的 CustomModelData 阈值：
     * 0.0 = 水之日轮刀（深海蓝） | 1.0 = 日之呼吸漆黑日轮刀 | 2.0 = 全集中/奥义炽红赫刀
     */
    public static void syncBladeModelVariant(ItemStack stack, BreathingStyle style, boolean redBladeActive) {
        if (!isWaterBlade(stack)) return;
        float desired = (style == BreathingStyle.SUN) ? (redBladeActive ? 2.0F : 1.0F) : 0.0F;
        CustomModelData cur = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        Float curVal = cur != null ? cur.getFloat(0) : null;
        if (curVal != null && Math.abs(curVal - desired) < 0.05F) {
            return;
        }
        if (desired <= 0.05F) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(desired), List.of(), List.of(), List.of()));
        }
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        // 切换赫刀或呼吸流派刷新刀身外观时不触发难看的物品下沉重拔动画
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
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.water_blade.style")
                .withStyle(ChatFormatting.GOLD));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.water_blade.combo_1")
                .withStyle(ChatFormatting.WHITE));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.water_blade.combo_2")
                .withStyle(ChatFormatting.AQUA));
        tooltipAdder.accept(Component.translatable("tooltip.nichirin.water_blade.breath")
                .withStyle(ChatFormatting.RED));
    }
}
