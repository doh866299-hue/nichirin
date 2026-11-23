package com.nichirin.breathing;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 原版《鬼灭之刃》水之呼吸 · 全十一型剑型定义（方案 A：切型一键瞬发 + 招式无缝派生）
 * <p>
 * 保留底层流派扩展接口，当前专注把水之呼吸 11 型 100% 忠实还原为标杆体系。
 */
public enum WaterForm {
    /** 壹之型 · 水面斩：凝腰蓄势一闪而出的平直一文字断首斩 */
    FIRST_SURFACE_SLASH(
            1, true, 14.0F, 12, 6,
            "form.nichirin.water.first.prefix", "form.nichirin.water.first.name",
            "form.nichirin.water.first.desc", false
    ),
    /** 贰之型 · 水车：地面纵翻 360° 车轮斩（空中自动变招为「贰之型·改 横水车」） */
    SECOND_WATER_WHEEL(
            2, true, 18.0F, 18, 8,
            "form.nichirin.water.second.prefix", "form.nichirin.water.second.name",
            "form.nichirin.water.second.desc", false
    ),
    /** 贰之型·改 · 横水车：水车的空中自动变招（水平 360° 疾旋轮斩，不单独占切型槽位） */
    SECOND_LATERAL_WHEEL(
            2, false, 18.0F, 18, 8,
            "form.nichirin.water.second_alt.prefix", "form.nichirin.water.second_alt.name",
            "form.nichirin.water.second.desc", false
    ),
    /** 参之型 · 流流舞：如无骨流水般三段 S 形高速弧线绕敌掠斩 */
    THIRD_FLOWING_DANCE(
            3, true, 20.0F, 22, 9,
            "form.nichirin.water.third.prefix", "form.nichirin.water.third.name",
            "form.nichirin.water.third.desc", false
    ),
    /** 肆之型 · 打潮：如海潮连绵拍岸的 4 段高低起伏压制连斩 */
    FOURTH_STRIKING_TIDE(
            4, true, 22.0F, 24, 10,
            "form.nichirin.water.fourth.prefix", "form.nichirin.water.fourth.name",
            "form.nichirin.water.fourth.desc", false
    ),
    /** 伍之型 · 乾天的慈雨：如久旱甘霖般温柔轻盈的高弧斩（对残血/隙之线目标触发极高慈雨处决！） */
    FIFTH_BLESSED_RAIN(
            5, true, 18.0F, 26, 9,
            "form.nichirin.water.fifth.prefix", "form.nichirin.water.fifth.name",
            "form.nichirin.water.fifth.desc", false
    ),
    /** 陆之型 · 扭转漩涡：上下身反向剧烈扭转拉出贴身水涡绞杀与爆发（水中范围威力大增） */
    SIXTH_WHIRLPOOL(
            6, true, 25.0F, 34, 11,
            "form.nichirin.water.sixth.prefix", "form.nichirin.water.sixth.name",
            "form.nichirin.water.sixth.desc", false
    ),
    /** 柒之型 · 雫波纹突刺：水呼最快最准的一点突刺，命中荡开 3D 同心水波纹（横移/空中自动变招「曲」） */
    SEVENTH_DROP_RIPPLE(
            7, true, 16.0F, 15, 6,
            "form.nichirin.water.seventh.prefix", "form.nichirin.water.seventh.name",
            "form.nichirin.water.seventh.desc", false
    ),
    /** 柒之型·曲 · 雫波纹突刺·曲：带弧度绕防变向突刺（横幅显示变体，不单独占切型槽位） */
    SEVENTH_DROP_RIPPLE_CURVE(
            7, false, 16.0F, 15, 6,
            "form.nichirin.water.seventh_curve.prefix", "form.nichirin.water.seventh_curve.name",
            "form.nichirin.water.seventh.desc", false
    ),
    /** 捌之型 · 泷壶：居高临下垂直劈出如瀑布倾泻砸地般的毁灭重斩（地面自动跃起衔接） */
    EIGHTH_WATERFALL_BASIN(
            8, true, 24.0F, 28, 11,
            "form.nichirin.water.eighth.prefix", "form.nichirin.water.eighth.name",
            "form.nichirin.water.eighth.desc", false
    ),
    /** 玖之型 · 水流飞沫·乱：极速踏水碎步全向突进闪身 + 掠斩，极短后摇无缝衔接下一型！ */
    NINTH_SPLASHING_FLOW(
            9, true, 10.0F, 8, 2,
            "form.nichirin.water.ninth.prefix", "form.nichirin.water.ninth.name",
            "form.nichirin.water.ninth.desc", false
    ),
    /** 拾之型 · 生生流转：按住右键持续回旋叠势（1→4圈越转越快越猛、水流化龙），松手斩出高势能龙尾水爆！ */
    TENTH_CONSTANT_FLUX(
            10, true, 20.0F, 50, 14,
            "form.nichirin.water.tenth.prefix", "form.nichirin.water.tenth.name",
            "form.nichirin.water.tenth.desc", true
    ),
    /** 拾壹之型 · 凪：富冈义勇极意，静立垂刀展开绝对平静水镜，踏入范围内的攻击瞬息归零 */
    ELEVENTH_DEAD_CALM(
            11, true, 28.0F, 58, 10,
            "form.nichirin.water.eleventh.prefix", "form.nichirin.water.eleventh.name",
            "form.nichirin.water.eleventh.desc", true
    );

    public static final List<WaterForm> SELECTABLE = Arrays.stream(values())
            .filter(WaterForm::isSelectable)
            .toList();

    private final int formNumber;
    private final boolean selectable;
    private final float breathCost;
    private final int cooldownTicks;
    private final int recoveryTicks;
    private final String prefixKey;
    private final String nameKey;
    private final String descKey;
    private final boolean ultimate;

    WaterForm(
            int formNumber, boolean selectable, float breathCost, int cooldownTicks, int recoveryTicks,
            String prefixKey, String nameKey, String descKey, boolean ultimate
    ) {
        this.formNumber = formNumber;
        this.selectable = selectable;
        this.breathCost = breathCost;
        this.cooldownTicks = cooldownTicks;
        this.recoveryTicks = recoveryTicks;
        this.prefixKey = prefixKey;
        this.nameKey = nameKey;
        this.descKey = descKey;
        this.ultimate = ultimate;
    }

    public int getFormNumber() { return formNumber; }
    public boolean isSelectable() { return selectable; }
    public float getBreathCost() { return breathCost; }
    public int getCooldownTicks() { return cooldownTicks; }
    public int getRecoveryTicks() { return recoveryTicks; }
    public boolean isUltimate() { return ultimate; }

    public Component getPrefixComponent(BreathingStyle style) {
        return Component.translatable(prefixKey);
    }

    public Component getNameComponent(BreathingStyle style) {
        return Component.translatable(nameKey);
    }

    public Component getDescComponent() {
        return Component.translatable(descKey);
    }

    public static WaterForm fromOrdinal(int ordinal) {
        WaterForm[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return FIRST_SURFACE_SLASH;
        return values[ordinal];
    }

    public static WaterForm getSelectable(int index) {
        int size = SELECTABLE.size();
        return SELECTABLE.get(Math.floorMod(index, size));
    }

    public static int selectableCount() {
        return SELECTABLE.size();
    }

    public static int indexOfSelectable(WaterForm form) {
        int idx = SELECTABLE.indexOf(form);
        return idx >= 0 ? idx : 0;
    }

    /** 根据当前空中/横移/潜行状态，将选中主型映射为实际触发的变体（如空中水车 -> 横水车，侧移突刺 -> 曲） */
    public WaterForm resolveVariant(boolean airborne, boolean curving) {
        if (this == SECOND_WATER_WHEEL && airborne) {
            return SECOND_LATERAL_WHEEL;
        }
        if (this == SEVENTH_DROP_RIPPLE && (airborne || curving)) {
            return SEVENTH_DROP_RIPPLE_CURVE;
        }
        return this;
    }
}
