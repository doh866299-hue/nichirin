package com.nichirin.client;

import com.nichirin.breathing.BreathingStyle;
import com.nichirin.breathing.WaterForm;
import com.nichirin.network.BreathSyncPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端同步的本地呼吸法状态缓存：
 * 包含当前流派、呼吸气力值、全集中/凪领域状态、当前选中的水之呼吸剑型（方案 A 切型出招）、
 * 以及拾之型·生生流转实时回旋叠势圈数（1..4 圈）！
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

    /** 方案 A：当前选中的水之呼吸剑型索引（0..10 对应 壹之型 ~ 拾壹之型） */
    private static int selectedFormIndex = 0;
    private static int formSwitchFlashTicks = 0;
    private static final int[] predictedCooldowns = new int[WaterForm.values().length];

    /** 拾之型·生生流转 实时回旋叠势圈数（1..4） */
    private static int fluxSpinStage = 0;
    private static int fluxSpinTicks = 0;

    private static int lastCastCounter = 0;
    private static WaterForm bannerForm = null;
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

        if (payload.castCounter() != lastCastCounter && payload.lastCastFormOrdinal() >= 0) {
            lastCastCounter = payload.castCounter();
            bannerForm = WaterForm.fromOrdinal(payload.lastCastFormOrdinal());
            bannerStyle = currentStyle;
            bannerTicks = BANNER_MAX_TICKS;
            // 同步记录本地冷却预测以便 HUD 显示对应剑型的精确冷却进度条
            WaterForm base = bannerForm == WaterForm.SECOND_LATERAL_WHEEL ? WaterForm.SECOND_WATER_WHEEL
                    : (bannerForm == WaterForm.SEVENTH_DROP_RIPPLE_CURVE ? WaterForm.SEVENTH_DROP_RIPPLE : bannerForm);
            predictedCooldowns[base.ordinal()] = base.getCooldownTicks();
        }
    }

    public static void cycleSelectedForm(int delta) {
        int count = WaterForm.selectableCount();
        selectedFormIndex = Math.floorMod(selectedFormIndex + delta, count);
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
        for (int i = 0; i < predictedCooldowns.length; i++) {
            if (predictedCooldowns[i] > 0) predictedCooldowns[i]--;
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
        selectedFormIndex = 0;
        formSwitchFlashTicks = 0;
        fluxSpinStage = 0;
        fluxSpinTicks = 0;
        for (int i = 0; i < predictedCooldowns.length; i++) predictedCooldowns[i] = 0;
        lastCastCounter = 0;
        bannerForm = null;
        bannerStyle = BreathingStyle.WATER;
        bannerTicks = 0;
    }

    public static BreathingStyle getCurrentStyle() { return currentStyle; }
    public static boolean isSunStyle() { return false; }
    public static boolean isRedBladeActive() { return false; }
    public static float getBreath() { return breath; }
    public static float getDisplayBreath() { return displayBreath; }
    public static float getMaxBreath() { return maxBreath; }
    public static boolean isTotalConcentration() { return totalConcentration; }
    public static boolean isInhaling() { return inhaling; }
    public static int getSpecialDomainTicks() { return specialDomainTicks; }
    public static int getGlobalRecoveryTicks() { return globalRecoveryTicks; }
    public static int getSelectedFormIndex() { return selectedFormIndex; }
    public static WaterForm getSelectedForm() { return WaterForm.getSelectable(selectedFormIndex); }
    public static int getFormSwitchFlashTicks() { return formSwitchFlashTicks; }
    public static int getPredictedCooldown(WaterForm form) { return predictedCooldowns[form.ordinal()]; }
    public static int getFluxSpinStage() { return fluxSpinStage; }
    public static int getFluxSpinTicks() { return fluxSpinTicks; }
    public static WaterForm getBannerForm() { return bannerForm; }
    public static BreathingStyle getBannerStyle() { return bannerStyle; }
    public static int getBannerTicks() { return bannerTicks; }
    public static boolean isShowGuide() { return showGuide; }
    public static void toggleGuide() { showGuide = !showGuide; }
}
