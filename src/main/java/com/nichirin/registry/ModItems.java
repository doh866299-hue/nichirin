package com.nichirin.registry;

import com.nichirin.NichirinMod;
import com.nichirin.item.SunNichirinBladeItem;
import com.nichirin.item.WaterNichirinBladeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组物品注册表
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NichirinMod.MOD_ID);

    /** 水之日轮刀：轻盈修长的深海蓝日式打刀，铭刻水之呼吸全十一型 */
    public static final DeferredItem<WaterNichirinBladeItem> WATER_NICHIRIN_BLADE = ITEMS.registerItem(
            "water_nichirin_blade",
            WaterNichirinBladeItem::new,
            props -> props
                    .sword(ToolMaterial.NETHERITE, 4.5F, -2.1F)
                    .durability(2200)
                    .fireResistant()
                    .rarity(Rarity.EPIC)
    );

    /** 日之日轮刀：始源漆黑日轮刀（全集中与第十三型圆环化作炽红赫刀），铭刻日之呼吸·火之神神乐全十三型 */
    public static final DeferredItem<SunNichirinBladeItem> SUN_NICHIRIN_BLADE = ITEMS.registerItem(
            "sun_nichirin_blade",
            SunNichirinBladeItem::new,
            props -> props
                    .sword(ToolMaterial.NETHERITE, 5.0F, -2.0F)
                    .durability(2400)
                    .fireResistant()
                    .rarity(Rarity.EPIC)
    );

    /** 猩猩绯砂铁：锻造日轮刀的核心稀有砂铁 */
    public static final DeferredItem<Item> SCARLET_CRIMSON_IRON_SAND = ITEMS.registerSimpleItem(
            "scarlet_crimson_iron_sand",
            props -> props.rarity(Rarity.UNCOMMON)
    );

    /** 猩猩绯玉钢：由猩猩绯砂铁千锤百炼而成的日轮刀刃钢锭 */
    public static final DeferredItem<Item> SCARLET_CRIMSON_INGOT = ITEMS.registerSimpleItem(
            "scarlet_crimson_ingot",
            props -> props.fireResistant().rarity(Rarity.RARE)
    );

    private ModItems() {}
}
