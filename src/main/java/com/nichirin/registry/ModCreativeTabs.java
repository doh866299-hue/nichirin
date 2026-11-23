package com.nichirin.registry;

import com.nichirin.NichirinMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组专属创造模式物品栏
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, NichirinMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> NICHIRIN_TAB =
            CREATIVE_MODE_TABS.register("nichirin_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.nichirin"))
                    .icon(() -> new ItemStack(ModItems.WATER_NICHIRIN_BLADE.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.WATER_NICHIRIN_BLADE.get());
                        output.accept(ModItems.SCARLET_CRIMSON_INGOT.get());
                        output.accept(ModItems.SCARLET_CRIMSON_IRON_SAND.get());
                    })
                    .build());

    private ModCreativeTabs() {}
}
