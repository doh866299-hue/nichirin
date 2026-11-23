package com.nichirin.breathing;

import net.minecraft.network.chat.Component;

/**
 * 呼吸法流派枚举（同一把日轮刀可通过快捷键 X 在已解锁流派间实时切换）
 */
public enum BreathingStyle {
    /** 水之呼吸（深海蓝日轮刀 + 浮世绘蓝白水浪） */
    WATER(0, "style.nichirin.water"),
    /** 日之呼吸 · 火之神神乐（漆黑日轮刀，全集中化作炽红赫刀 + 浮世绘黑红烈焰） */
    SUN(1, "style.nichirin.sun");

    private final int id;
    private final String langKey;

    BreathingStyle(int id, String langKey) {
        this.id = id;
        this.langKey = langKey;
    }

    public int getId() {
        return id;
    }

    public Component getDisplayName() {
        return Component.translatable(langKey);
    }

    public BreathingStyle next() {
        BreathingStyle[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }

    public static BreathingStyle fromId(int id) {
        return id == 1 ? SUN : WATER;
    }
}
