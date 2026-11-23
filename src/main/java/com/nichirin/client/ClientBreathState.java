package com.nichirin.client;

import com.nichirin.breathing.BreathingStyle;
import com.nichirin.breathing.SunForm;
import com.nichirin.breathing.WaterForm;
import com.nichirin.item.SunNichirinBladeItem;
import com.nichirin.item.WaterNichirinBladeItem;
import com.nichirin.network.BreathSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端同步的本地呼吸法状态缓存（双独立日轮刀流派：水之呼吸全十一型 & 日之呼吸全十三型）：
 * 分别独立记忆水呼 / 日呼选中的剑型索引、冷却预测、生生流转叠势圈数与第十三型圆环连舞进度！
 */
public final class ClientBreathState {
    private static BreathingStyle currentStyle = BreathingStyle.WATER;
    private static float breath = 100.0F;
    private static float maxBreath = 100.0F;
    private static float displayBreath = 100.0F;
    private static boolean totalConcentration = false;
    private static boolean inhaling = false;
    private static int specialDomainTicks = 0;
    private static int globalRecoveryTicks = 0;

    /** 方案 A：分别记忆水之呼吸（0..10）与日之呼吸（0..12）当前选中的剑型索引，切刀不丢记忆！ */
    private static int selectedWaterFormIndex = 0;
    private static int selectedSunFormIndex = 0;
    private static int formSwitchFlashTicks = 0;
    private static final int[] predictedWaterCooldowns = new int[WaterForm.values().length];
    private static final int[] predictedSunCooldowns = new int[SunForm.values().length];

    /** 水·拾之型·生生流转 实时回旋叠势圈数（1..4） */
    private static int fluxSpinStage = 0;
    private static int fluxSpinTicks = 0;

    /** 日·第十三型·日之圆环 实时连舞进度（1..12）与剩余衔接窗口 tick */
    private static int sunCycleStep = 0;
    private static int sunCycleTicks = 0;

    private static int lastCastCounter = 0;
    private static WaterForm bannerWaterForm = null;
    private static SunForm bannerSunForm = null;
    private static BreathingStyle bannerStyle = BreathingStyle.WATER;
    private static int bannerTicks = 0;
    public static final int BANNER_MAX_TICKS = 42;

    private static boolean showGuide = false;

    private ClientBreathState() {}

    public static void handleSyncPayload(BreathSyncPayload payload, IPayloadContext context) {
        currentStyle = BreathingStyle.fromId(payload.styleId());
        breath = payload.breath();
        maxBreath = Math.max(1.0F, payload.maxBreath());
        totalConcentration = payload.totalConcentration();
        inhaling = payload.inhaling();
        specialDomainTicks = payload.specialDomainTicks();
        globalRecoveryTicks = payload.globalRecoveryTicks();
        sunCycleStep = payload.sunCycleStep();
        sunCycleTicks = payload.sunCycleTicks();

        if (payload.castCounter() != lastCastCounter && payload.lastCastFormOrdinal() >= 0) {
            lastCastCounter = payload.castCounter();
            bannerStyle = currentStyle;
            bannerTicks = BANNER_MAX_TICKS;
            if (currentStyle == BreathingStyle.SUN) {
                bannerSunForm = SunForm.fromOrdinal(payload.lastCastFormOrdinal());
                bannerWaterForm = null;
                if (sunCycleStep <= 0) {
                    SunForm base = bannerSunForm == SunForm.FIRST_DANCING_FLASH ? SunForm.FIRST_DANCE : bannerSunForm;
                    predictedSunCooldowns[base.ordinal()] = base.getCooldownTicks();
                }
            } else {
                bannerWaterForm = WaterForm.fromOrdinal(payload.lastCastFormOrdinal());
                bannerSunForm = null;
                WaterForm base = bannerWaterForm == WaterForm.SECOND_LATERAL_WHEEL ? WaterForm.SECOND_WATER_WHEEL
                        : (bannerWaterForm == WaterForm.SEVENTH_DROP_RIPPLE_CURVE ? WaterForm.SEVENTH_DROP_RIPPLE : bannerWaterForm);
                predictedWaterCooldowns[base.ordinal()] = base.getCooldownTicks();
            }
        }
    }

    /** 结合客户端实时主手物品判定当前流派（切换水/日之日轮刀瞬间 0 延迟即时切皮肤与选招表！） */
    public static BreathingStyle getEffectiveStyle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack main = mc.player.getMainHandItem();
            if (SunNichirinBladeItem.isSunBlade(main)) return BreathingStyle.SUN;
            if (WaterNichirinBladeItem.isWaterBlade(main)) return BreathingStyle.WATER;
        }
        return currentStyle;
    }

    public static void cycleSelectedForm(int delta) {
        if (isSunStyle()) {
            int count = SunForm.selectableCount();
            selectedSunFormIndex = Math.floorMod(selectedSunFormIndex + delta, count);
        } else {
            int count = WaterForm.selectableCount();
            selectedWaterFormIndex = Math.floorMod(selectedWaterFormIndex + delta, count);
        }
        formSwitchFlashTicks = 12;
    }

    public static void setFluxSpinStage(int stage, int displayTicks) {
        fluxSpinStage = Math.max(0, stage);
        fluxSpinTicks = Math.max(0, displayTicks);
    }

    public static void clearFluxSpin() {
        fluxSpinStage = 0;
        fluxSpinTicks = 0;
    }

    public static void clientTick() {
        displayBreath += (breath - displayBreath) * 0.28F;
        if (Math.abs(displayBreath - breath) < 0.08F) {
            displayBreath = breath;
        }
        if (bannerTicks > 0) bannerTicks--;
        if (specialDomainTicks > 0) specialDomainTicks--;
        if (globalRecoveryTicks > 0) globalRecoveryTicks--;
        if (formSwitchFlashTicks > 0) formSwitchFlashTicks--;
        if (fluxSpinTicks > 0) {
            fluxSpinTicks--;
            if (fluxSpinTicks == 0) fluxSpinStage = 0;
        }
        if (sunCycleTicks > 0) {
            sunCycleTicks--;
            if (sunCycleTicks == 0) sunCycleStep = 0;
        }
        for (int i = 0; i < predictedWaterCooldowns.length; i++) {
            if (predictedWaterCooldowns[i] > 0) predictedWaterCooldowns[i]--;
        }
        for (int i = 0; i < predictedSunCooldowns.length; i++) {
            if (predictedSunCooldowns[i] > 0) predictedSunCooldowns[i]--;
        }
    }

    public static void reset() {
        currentStyle = BreathingStyle.WATER;
        breath = 100.0F;
        maxBreath = 100.0F;
        displayBreath = 100.0F;
        totalConcentration = false;
        inhaling = false;
        specialDomainTicks = 0;
        globalRecoveryTicks = 0;
        selectedWaterFormIndex = 0;
        selectedSunFormIndex = 0;
        formSwitchFlashTicks = 0;
        fluxSpinStage = 0;
        fluxSpinTicks = 0;
        sunCycleStep = 0;
        sunCycleTicks = 0;
        for (int i = 0; i < predictedWaterCooldowns.length; i++) predictedWaterCooldowns[i] = 0;
        for (int i = 0; i < predictedSunCooldowns.length; i++) predictedSunCooldowns[i] = 0;
        lastCastCounter = 0;
        bannerWaterForm = null;
        bannerSunForm = null;
        bannerStyle = BreathingStyle.WATER;
        bannerTicks = 0;
    }

    public static BreathingStyle getCurrentStyle() { return getEffectiveStyle(); }
    public static boolean isSunStyle() { return getEffectiveStyle() == BreathingStyle.SUN; }
    public static boolean isRedBladeActive() { return isSunStyle() && (totalConcentration || sunCycleStep > 0); }
    public static float getBreath() { return breath; }
    public static float getDisplayBreath() { return displayBreath; }
    public static float getMaxBreath() { return maxBreath; }
    public static boolean isTotalConcentration() { return totalConcentration; }
    public static boolean isInhaling() { return inhaling; }
    public static int getSpecialDomainTicks() { return specialDomainTicks; }
    public static int getGlobalRecoveryTicks() { return globalRecoveryTicks; }
    public static int getSelectedFormIndex() { return isSunStyle() ? selectedSunFormIndex : selectedWaterFormIndex; }
    public static WaterForm getSelectedForm() { return WaterForm.getSelectable(selectedWaterFormIndex); }
    public static SunForm getSelectedSunForm() { return SunForm.getSelectable(selectedSunFormIndex); }
    public static int getFormSwitchFlashTicks() { return formSwitchFlashTicks; }
    public static int getPredictedCooldown(WaterForm form) { return predictedWaterCooldowns[form.ordinal()]; }
    public static int getPredictedSunCooldown(SunForm form) { return predictedSunCooldowns[form.ordinal()]; }
    public static int getFluxSpinStage() { return fluxSpinStage; }
    public static int getFluxSpinTicks() { return fluxSpinTicks; }
    public static int getSunCycleStep() { return sunCycleStep; }
    public static int getSunCycleTicks() { return sunCycleTicks; }
    public static WaterForm getBannerForm() { return bannerWaterForm; }
    public static SunForm getBannerSunForm() { return bannerSunForm; }
    public static BreathingStyle getBannerStyle() { return bannerStyle; }
    public static int getBannerTicks() { return bannerTicks; }
    public static boolean isShowGuide() { return showGuide; }
    public static void toggleGuide() { showGuide = !showGuide; }
}
