package com.nichirin.breathing;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 原版《鬼灭之刃》日之呼吸 · 火之神神乐（全十三型，采用漫画第 192 话「廻る縁」正统圆环顺序）
 * <ul>
 *   <li>1~12 型为可自由切换释放的独立剑型（以同音「圆舞 Enbu」起手、「炎舞 Enbu」闭环）</li>
 *   <li>「圆舞」在疾跑或潜行瞬间释放时，自动触发衍生技「圆舞一闪」（融合雷之呼吸瞬步）</li>
 *   <li>第 13 型「第十三型 · 日之圆环」无独立招式名：选中后点按或按住右键，将 1→12 型无缝串联高速循环连舞！</li>
 * </ul>
 */
public enum SunForm {
    /** 1. 圆舞：双手画大弧斩出的烈焰重斩（疾跑/潜行自动变招为「圆舞一闪」） */
    FIRST_DANCE(
            1, true, 14.0F, 12, 6,
            "form.nichirin.sun.first.prefix", "form.nichirin.sun.first.name",
            "form.nichirin.sun.first.desc", false
    ),
    /** 1·衍生. 圆舞一闪：融合雷之呼吸步法的高速瞬闪圆斩（不单独占切型槽位） */
    FIRST_DANCING_FLASH(
            1, false, 16.0F, 14, 6,
            "form.nichirin.sun.first_flash.prefix", "form.nichirin.sun.first_flash.name",
            "form.nichirin.sun.first.desc", false
    ),
    /** 2. 碧罗之天：扭腰由下而上 360° 垂直大圆挑斩（强力对空与挑飞斩首） */
    SECOND_CLEAR_BLUE_SKY(
            2, true, 18.0F, 18, 8,
            "form.nichirin.sun.second.prefix", "form.nichirin.sun.second.name",
            "form.nichirin.sun.second.desc", false
    ),
    /** 3. 烈日红镜：左右「∞」字高速交叉双弧火焰斩（攻防一体，大范围斩碎弹射物） */
    THIRD_RAGING_SUN(
            3, true, 18.0F, 18, 8,
            "form.nichirin.sun.third.prefix", "form.nichirin.sun.third.name",
            "form.nichirin.sun.third.desc", false
    ),
    /** 4. 幻日虹：高速扭转闪避，原地留下炽热虹光残像诱敌，敌击中触发完美闪避炽炎反杀 */
    FOURTH_FAKE_RAINBOW(
            4, true, 14.0F, 24, 4,
            "form.nichirin.sun.fourth.prefix", "form.nichirin.sun.fourth.name",
            "form.nichirin.sun.fourth.desc", false
    ),
    /** 5. 火车：凌空跃起翻越敌人上方，借下坠之势挥出烈焰纵轮劈斩 */
    FIFTH_FIRE_WHEEL(
            5, true, 20.0F, 20, 8,
            "form.nichirin.sun.fifth.prefix", "form.nichirin.sun.fifth.name",
            "form.nichirin.sun.fifth.desc", false
    ),
    /** 6. 灼骨炎阳：旋身向前卷出巨大螺旋焚骨烈焰，造成持续炽烧并大幅抑制目标回复 */
    SIXTH_BURNING_BONES(
            6, true, 24.0F, 28, 10,
            "form.nichirin.sun.sixth.prefix", "form.nichirin.sun.sixth.name",
            "form.nichirin.sun.sixth.desc", false
    ),
    /** 7. 阳华突：日之呼吸唯一突刺技，高速直线贯刺并绽放 3D 炽金日冕火环 */
    SEVENTH_SUNFLOWER_THRUST(
            7, true, 16.0F, 16, 6,
            "form.nichirin.sun.seventh.prefix", "form.nichirin.sun.seventh.name",
            "form.nichirin.sun.seventh.desc", false
    ),
    /** 8. 飞轮阳炎：刀刃缠绕晃动扭曲的夏日阳炎，超长 7.5 格热浪延伸斩并附带破甲破盾 */
    EIGHTH_SOLAR_HEAT_HAZE(
            8, true, 18.0F, 20, 7,
            "form.nichirin.sun.eighth.prefix", "form.nichirin.sun.eighth.name",
            "form.nichirin.sun.eighth.desc", false
    ),
    /** 9. 斜阳转身：腾空 180° 倒立逆转身体，自上而下挥出猛烈的赤红奇袭横斩 */
    NINTH_SETTING_SUN(
            9, true, 20.0F, 22, 8,
            "form.nichirin.sun.ninth.prefix", "form.nichirin.sun.ninth.name",
            "form.nichirin.sun.ninth.desc", false
    ),
    /** 10. 辉辉恩光：跃起向下螺旋轰坠，卷出炽烈太阳炎瀑与环形灼热爆浪 */
    TENTH_BENEFICENT_RADIANCE(
            10, true, 25.0F, 30, 10,
            "form.nichirin.sun.tenth.prefix", "form.nichirin.sun.tenth.name",
            "form.nichirin.sun.tenth.desc", false
    ),
    /** 11. 日晕之龙·头舞：召唤 3D 太阳烈焰神龙绕身咆哮，高速折返多目标穿锁 4 段狂舞连斩！ */
    ELEVENTH_DRAGON_SUN_HALO(
            11, true, 30.0F, 46, 12,
            "form.nichirin.sun.eleventh.prefix", "form.nichirin.sun.eleventh.name",
            "form.nichirin.sun.eleventh.desc", true
    ),
    /** 12. 炎舞：脱胎自圆舞的强力火焰二连斩（重斩接回旋追斩），自然首尾衔接回第 1 招圆舞 */
    TWELFTH_FLAME_DANCE(
            12, true, 22.0F, 22, 9,
            "form.nichirin.sun.twelfth.prefix", "form.nichirin.sun.twelfth.name",
            "form.nichirin.sun.twelfth.desc", false
    ),
    /** 13. 第十三型 · 日之圆环：选中后点按或按住右键，将 1→12 型无缝串联无限循环连舞并燃起赫刀！ */
    THIRTEENTH_SUN_CYCLE(
            13, true, 12.0F, 8, 5,
            "form.nichirin.sun.thirteenth.prefix", "form.nichirin.sun.thirteenth.name",
            "form.nichirin.sun.thirteenth.desc", true
    );

    public static final List<SunForm> SELECTABLE = Arrays.stream(values())
            .filter(SunForm::isSelectable)
            .toList();

    /** 漫画 192 话正统圆环 1→12 循环序列（供第十三型无缝连舞调用） */
    public static final SunForm[] CYCLE_ORDER = {
            FIRST_DANCE,
            SECOND_CLEAR_BLUE_SKY,
            THIRD_RAGING_SUN,
            FOURTH_FAKE_RAINBOW,
            FIFTH_FIRE_WHEEL,
            SIXTH_BURNING_BONES,
            SEVENTH_SUNFLOWER_THRUST,
            EIGHTH_SOLAR_HEAT_HAZE,
            NINTH_SETTING_SUN,
            TENTH_BENEFICENT_RADIANCE,
            ELEVENTH_DRAGON_SUN_HALO,
            TWELFTH_FLAME_DANCE
    };

    private final int formNumber;
    private final boolean selectable;
    private final float breathCost;
    private final int cooldownTicks;
    private final int recoveryTicks;
    private final String prefixKey;
    private final String nameKey;
    private final String descKey;
    private final boolean ultimate;

    SunForm(
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

    public Component getPrefixComponent() {
        return Component.translatable(prefixKey);
    }

    public Component getNameComponent() {
        return Component.translatable(nameKey);
    }

    public Component getDescComponent() {
        return Component.translatable(descKey);
    }

    public static SunForm fromOrdinal(int ordinal) {
        SunForm[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return FIRST_DANCE;
        return values[ordinal];
    }

    public static SunForm getSelectable(int index) {
        int size = SELECTABLE.size();
        return SELECTABLE.get(Math.floorMod(index, size));
    }

    public static int selectableCount() {
        return SELECTABLE.size();
    }

    public static SunForm getCycleStep(int zeroBasedIndex) {
        return CYCLE_ORDER[Math.floorMod(zeroBasedIndex, CYCLE_ORDER.length)];
    }

    /** 圆舞在疾跑或潜行瞬间施放时，自动升华为「圆舞一闪」 */
    public SunForm resolveVariant(boolean flashTrigger) {
        if (this == FIRST_DANCE && flashTrigger) {
            return FIRST_DANCING_FLASH;
        }
        return this;
    }
}
