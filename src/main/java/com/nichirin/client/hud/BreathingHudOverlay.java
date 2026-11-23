package com.nichirin.client.hud;

import com.nichirin.breathing.BreathingStyle;
import com.nichirin.breathing.SunForm;
import com.nichirin.breathing.WaterForm;
import com.nichirin.client.ClientBreathState;
import com.nichirin.client.vfx.WaterVfxManager;
import com.nichirin.item.SunNichirinBladeItem;
import com.nichirin.item.WaterNichirinBladeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 原版《鬼灭之刃》双流派浮世绘切型 HUD 渲染器：
 * - 持水之日轮刀：深海青蓝浮世绘主题（水之呼吸全十一型 + 生生流转 ×1~×4 叠势条 + 凪静止领域倒计时）
 * - 持日之日轮刀：黑红炽金火神主题（日之呼吸全十三型 + 赫刀炽红气力槽 + 第十三型 12 格日之圆环进度条 + 幻日虹倒计时）
 */
public final class BreathingHudOverlay {
    private BreathingHudOverlay() {}

    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.hud.isHidden()) {
            return;
        }

        GuiGraphicsExtractor gui = event.getGuiGraphics();
        Font font = mc.font;
        int sw = gui.guiWidth();
        int sh = gui.guiHeight();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = (player.tickCount + partialTick) * 0.12F;

        boolean holdingBlade = WaterNichirinBladeItem.isAnyNichirinBlade(player.getMainHandItem());
        boolean isSun = holdingBlade
                ? SunNichirinBladeItem.isSunBlade(player.getMainHandItem())
                : ClientBreathState.isSunStyle();

        // 1. 屏幕氛围滤镜（闪光 + 暗角）
        renderScreenOverlays(gui, sw, sh, partialTick, time, isSun);

        boolean showBreathBar = holdingBlade
                || ClientBreathState.isTotalConcentration()
                || ClientBreathState.getSpecialDomainTicks() > 0
                || ClientBreathState.getSunCycleTicks() > 0
                || ClientBreathState.getDisplayBreath() < ClientBreathState.getMaxBreath() - 0.5F;

        // 2. 呼吸气力槽（热键栏右侧）
        if (showBreathBar) {
            renderBreathGauge(gui, font, sw, sh, time, isSun);
        }

        // 3. 极简浮世绘切型铭牌 + 水·生生流转叠势条 / 日·第十三型 12 格日之圆环进度条 + 杀气危机折射
        if (holdingBlade) {
            if (isSun) {
                boolean flashTrigger = player.isSprinting() || player.isShiftKeyDown();
                renderSunFormSelectorCard(gui, font, sw, sh, flashTrigger);
                renderSunCycleMeter(gui, font, sw, sh);
            } else {
                boolean airborne = !player.onGround() && !player.isInWater();
                boolean curving = player.isShiftKeyDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
                renderWaterFormSelectorCard(gui, font, sw, sh, airborne, curving);
                renderFluxSpinMeter(gui, font, sw, sh);
            }
            renderIntuitionCues(gui, sw, sh, partialTick, isSun);
        }

        // 4. 招式释放浮世绘卷轴横幅
        renderFormBanner(gui, font, sw, sh, partialTick);

        // 5. 按 G 展开的全剑型速查目录（水十一型 / 日十三型自动切换）
        if (holdingBlade && ClientBreathState.isShowGuide() && sw >= 360) {
            if (isSun) {
                renderFullSunFormGuide(gui, font, sw, sh);
            } else {
                renderFullElevenFormGuide(gui, font, sw, sh);
            }
        }
    }

    private static void renderScreenOverlays(
            GuiGraphicsExtractor gui, int sw, int sh, float partialTick, float time, boolean isSun
    ) {
        float flash = WaterVfxManager.getFlashAlpha(partialTick);
        if (flash > 0.004F) {
            int a = Mth.clamp((int) (flash * 255.0F), 0, 140);
            int rgb = WaterVfxManager.getFlashRgb() & 0x00FFFFFF;
            gui.fill(0, 0, sw, sh, (a << 24) | rgb);
        }

        float vignette = WaterVfxManager.getVignetteAlpha(partialTick);
        boolean specialActive = ClientBreathState.getSpecialDomainTicks() > 0;
        int sunCycleStep = ClientBreathState.getSunCycleStep();
        if (specialActive) {
            vignette = Math.max(vignette, 0.30F + 0.04F * Mth.sin(time * 1.6F));
        } else if (isSun && sunCycleStep > 0) {
            vignette = Math.max(vignette, 0.14F + Math.min(0.18F, sunCycleStep * 0.015F) + 0.03F * Mth.sin(time * 2.6F));
        } else if (!isSun && ClientBreathState.getFluxSpinStage() > 0) {
            vignette = Math.max(vignette, 0.14F + ClientBreathState.getFluxSpinStage() * 0.045F);
        } else if (ClientBreathState.isTotalConcentration()) {
            vignette = Math.max(vignette, 0.10F + 0.03F * Mth.sin(time * 2.2F));
        }

        if (vignette > 0.005F) {
            int edgeA = Mth.clamp((int) (vignette * 210.0F), 0, 165);
            int rgb = isSun
                    ? (specialActive ? 0x421004 : 0x360C04)
                    : (specialActive ? 0x031936 : 0x052852);
            int bandH = Math.max(26, sh / 5);
            int bandW = Math.max(24, sw / 9);
            int edgeCol = (edgeA << 24) | rgb;
            int clearCol = rgb;

            gui.fillGradient(0, 0, sw, bandH, edgeCol, clearCol);
            gui.fillGradient(0, sh - bandH, sw, sh, clearCol, edgeCol);
            int sideCol = ((edgeA / 2) << 24) | rgb;
            gui.fill(0, 0, bandW / 2, sh, sideCol);
            gui.fill(sw - bandW / 2, 0, sw, sh, sideCol);
        }
    }

    private static void renderBreathGauge(
            GuiGraphicsExtractor gui, Font font, int sw, int sh, float time, boolean isSun
    ) {
        int barW = 108;
        int barH = 10;
        int x = Math.min(sw / 2 + 100, sw - barW - 8);
        int y = sh - 18;

        float ratio = Mth.clamp(ClientBreathState.getDisplayBreath() / ClientBreathState.getMaxBreath(), 0.0F, 1.0F);
        int fillW = Math.round((barW - 2) * ratio);
        boolean tc = ClientBreathState.isTotalConcentration();
        boolean redBlade = isSun && ClientBreathState.isRedBladeActive();
        boolean inhaling = ClientBreathState.isInhaling();

        int bgOuter = isSun ? 0xCC1E0704 : 0xCC040D1E;
        int bgInner = isSun ? 0xCC2C0B05 : 0xCC071A33;
        int borderCol = isSun
                ? (redBlade ? 0xFFFFB84A : (inhaling ? 0xFFFFDA80 : 0xFFC84618))
                : (tc ? 0xFF66EEFF : (inhaling ? 0xFF9BEFFF : 0xFF2878B8));

        gui.fill(x - 2, y - 2, x + barW + 2, y + barH + 2, bgOuter);
        gui.outline(x - 1, y - 1, barW + 2, barH + 2, borderCol);
        gui.fill(x, y, x + barW, y + barH, bgInner);

        if (fillW > 0) {
            int topCol = isSun
                    ? (redBlade ? 0xFFFFD858 : 0xFFFF8C28)
                    : (tc ? 0xFF56EAFF : 0xFF25B0F8);
            int botCol = isSun
                    ? (redBlade ? 0xFFCC1C08 : 0xFF9E1E08)
                    : (tc ? 0xFF0E68D8 : 0xFF0B4696);
            gui.fillGradient(x + 1, y + 1, x + 1 + fillW, y + barH - 1, topCol, botCol);

            int shimmerPos = (int) ((time * 22.0F) % Math.max(12, fillW));
            int sx0 = x + 1 + shimmerPos;
            int sx1 = Math.min(x + 1 + fillW, sx0 + 10);
            if (sx1 > sx0) {
                gui.fill(sx0, y + 1, sx1, y + 3, isSun ? 0x99FFF2B8 : 0x88E2FCFF);
            }
            gui.fill(x + fillW, y + 1, x + fillW + 1, y + barH - 1, isSun ? 0xFFFFF6D0 : 0xFFE8FDFF);
        }

        for (int i = 1; i <= 3; i++) {
            int tx = x + (barW * i) / 4;
            gui.fill(tx, y + barH - 3, tx + 1, y + barH, isSun ? 0x88240804 : 0x88061428);
        }

        String statusText;
        int labelCol;
        if (inhaling) {
            statusText = Component.translatable("hud.nichirin.inhaling").getString();
            labelCol = isSun ? 0xFFFFE6A0 : 0xFFB8F8FF;
        } else if (redBlade) {
            statusText = "★ " + Component.translatable("hud.nichirin.red_blade_on").getString();
            labelCol = 0xFFFFB048;
        } else if (tc) {
            statusText = "★ " + Component.translatable("hud.nichirin.total_conc_on").getString();
            labelCol = 0xFF66F2FF;
        } else {
            statusText = Component.translatable(isSun ? "hud.nichirin.sun_breath" : "hud.nichirin.water_breath").getString();
            labelCol = isSun ? 0xFFFFB86C : 0xFF8CD6FF;
        }
        String numText = String.valueOf(Math.round(ClientBreathState.getBreath()));
        gui.text(font, statusText, x, y - 11, labelCol, true);
        gui.text(font, numText, x + barW - font.width(numText), y - 11, isSun ? 0xFFFFF4DC : 0xFFE6FAFF, true);

        // 水·拾壹之型·凪 / 日·幻日虹 倒计时条
        int specialTicks = ClientBreathState.getSpecialDomainTicks();
        if (specialTicks > 0) {
            int cy = y - 22;
            float maxT = isSun ? 40.0F : 60.0F;
            float spRatio = Mth.clamp(specialTicks / maxT, 0.0F, 1.0F);
            int cFill = Math.round(barW * spRatio);
            gui.fill(x - 1, cy - 1, x + barW + 1, cy + 5, isSun ? 0xB8260804 : 0xB8031226);
            gui.fill(x, cy, x + cFill, cy + 4, isSun ? 0xFFFFC458 : 0xFF9DF6FF);
            gui.text(font,
                    Component.translatable(isSun ? "hud.nichirin.fake_rainbow_active" : "hud.nichirin.dead_calm_active"),
                    x, cy - 10, isSun ? 0xFFFFE096 : 0xFFB8FAFF, true);
        }
    }

    /** 水之呼吸 · 左下角切型卷轴卡 */
    private static void renderWaterFormSelectorCard(
            GuiGraphicsExtractor gui, Font font, int sw, int sh, boolean airborne, boolean curving
    ) {
        int selIdx = ClientBreathState.getSelectedFormIndex();
        WaterForm prevForm = WaterForm.getSelectable(selIdx - 1);
        WaterForm curBase = WaterForm.getSelectable(selIdx);
        WaterForm curResolved = curBase.resolveVariant(airborne, curving);
        WaterForm nextForm = WaterForm.getSelectable(selIdx + 1);

        String prevText = "  " + formatShortWaterForm(prevForm);
        String curText = "▸ " + formatShortWaterForm(curResolved);
        String nextText = "  " + formatShortWaterForm(nextForm);
        String keyHint = "[Shift+滚轮 / Z·C] 切型  [右键] 出招  [G] 目录";

        int cardW = Math.max(128, Math.max(font.width(curText) + 34, font.width(prevText) + 16));
        int cardH = 38;
        int x = 10;
        int y = sh - cardH - 18;

        int flash = ClientBreathState.getFormSwitchFlashTicks();
        int borderCol = flash > 0 ? 0xFF9DF6FF : (curBase.isUltimate() ? 0xFF56E4FF : 0xCC2A82C4);

        gui.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, 0xB8040E1E);
        gui.outline(x - 1, y - 1, cardW + 2, cardH + 2, borderCol);

        gui.fill(x, y + 12, x + cardW, y + 25, flash > 0 ? 0xCC1662B8 : 0xBB0B3B78);
        gui.fill(x, y + 12, x + 2, y + 25, curBase.isUltimate() ? 0xFF9CF6FF : 0xFF42CEFF);

        int cd = ClientBreathState.getPredictedCooldown(curBase);
        if (cd > 0 && curBase.getCooldownTicks() > 0) {
            float cdRatio = Mth.clamp(cd / (float) curBase.getCooldownTicks(), 0.0F, 1.0F);
            gui.fill(x, y + 23, x + Math.round(cardW * cdRatio), y + 25, 0xDDFF785A);
        }

        gui.text(font, prevText, x + 4, y + 2, 0x887BA8C8, false);
        int curCol = cd > 0 ? 0xFF98B4C8 : (curBase.isUltimate() ? 0xFFFFFFFF : 0xFFE8FDFF);
        gui.text(font, curText, x + 4, y + 15, curCol, true);
        gui.text(font, nextText, x + 4, y + 27, 0x887BA8C8, false);

        float cost = curBase.getBreathCost() * (ClientBreathState.isTotalConcentration() ? 0.85F : 1.0F);
        String costStr = Math.round(cost) + "气";
        int costCol = ClientBreathState.getBreath() >= cost ? 0xFF7CE8FF : 0xFFFF7A7A;
        gui.text(font, costStr, x + cardW - font.width(costStr) - 4, y + 15, costCol, true);

        gui.text(font, keyHint, x, y + cardH + 4, 0xBB9EC8E4, true);
    }

    /** 日之呼吸 · 火之神神乐 左下角黑红炽金切型卷轴卡 */
    private static void renderSunFormSelectorCard(
            GuiGraphicsExtractor gui, Font font, int sw, int sh, boolean flashTrigger
    ) {
        int selIdx = ClientBreathState.getSelectedFormIndex();
        SunForm prevForm = SunForm.getSelectable(selIdx - 1);
        SunForm curBase = SunForm.getSelectable(selIdx);
        SunForm curResolved = curBase.resolveVariant(flashTrigger);
        SunForm nextForm = SunForm.getSelectable(selIdx + 1);

        String prevText = "  " + formatShortSunForm(prevForm);
        String curText = "▸ " + formatShortSunForm(curResolved);
        String nextText = "  " + formatShortSunForm(nextForm);
        String keyHint = curBase == SunForm.THIRTEENTH_SUN_CYCLE
                ? "[点按/长按右键] 1→12 无限日之圆环连舞  [G] 目录"
                : "[Shift+滚轮 / Z·C] 切型  [右键] 出招  [G] 目录";

        int cardW = Math.max(138, Math.max(font.width(curText) + 34, font.width(prevText) + 16));
        int cardH = 38;
        int x = 10;
        int y = sh - cardH - 18;

        int flash = ClientBreathState.getFormSwitchFlashTicks();
        int borderCol = flash > 0 ? 0xFFFFE688 : (curBase.isUltimate() ? 0xFFFFB238 : 0xCCC84418);

        gui.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, 0xC41C0604);
        gui.outline(x - 1, y - 1, cardW + 2, cardH + 2, borderCol);

        gui.fill(x, y + 12, x + cardW, y + 25, flash > 0 ? 0xCC9C280A : 0xBB641406);
        gui.fill(x, y + 12, x + 2, y + 25, curBase.isUltimate() ? 0xFFFFE67A : 0xFFFF7A26);

        int cd = ClientBreathState.getPredictedSunCooldown(curBase);
        if (cd > 0 && curBase.getCooldownTicks() > 0 && curBase != SunForm.THIRTEENTH_SUN_CYCLE) {
            float cdRatio = Mth.clamp(cd / (float) curBase.getCooldownTicks(), 0.0F, 1.0F);
            gui.fill(x, y + 23, x + Math.round(cardW * cdRatio), y + 25, 0xDDFFF096);
        }

        gui.text(font, prevText, x + 4, y + 2, 0x88C88A72, false);
        int curCol = cd > 0 ? 0xFFC29684 : (curBase.isUltimate() ? 0xFFFFF4C2 : 0xFFFFEED6);
        gui.text(font, curText, x + 4, y + 15, curCol, true);
        gui.text(font, nextText, x + 4, y + 27, 0x88C88A72, false);

        float cost = curBase.getBreathCost() * (ClientBreathState.isTotalConcentration() ? 0.85F : 1.0F);
        String costStr = curBase == SunForm.THIRTEENTH_SUN_CYCLE ? "连舞" : (Math.round(cost) + "气");
        int costCol = (curBase == SunForm.THIRTEENTH_SUN_CYCLE || ClientBreathState.getBreath() >= cost)
                ? 0xFFFFCC68 : 0xFFFF5C5C;
        gui.text(font, costStr, x + cardW - font.width(costStr) - 4, y + 15, costCol, true);

        gui.text(font, keyHint, x, y + cardH + 4, 0xCCF4C08A, true);
    }

    /** 水·拾之型·生生流转 回旋叠势 ×1..×4 实时动量指示器 */
    private static void renderFluxSpinMeter(GuiGraphicsExtractor gui, Font font, int sw, int sh) {
        int stage = ClientBreathState.getFluxSpinStage();
        if (stage <= 0) return;

        int cx = sw / 2;
        int cy = sh / 2 + 22;
        int pipW = 18;
        int gap = 4;
        int totalW = pipW * 4 + gap * 3;
        int startX = cx - totalW / 2;

        for (int i = 1; i <= 4; i++) {
            int px = startX + (i - 1) * (pipW + gap);
            boolean active = i <= stage;
            int fillCol = active ? (i == 4 ? 0xFFFFFFFF : 0xFF56EEFF) : 0x77091C34;
            int outCol = active ? 0xFFE8FDFF : 0x882E6E9E;
            gui.fill(px, cy, px + pipW, cy + 5, fillCol);
            gui.outline(px - 1, cy - 1, pipW + 2, 7, outCol);
        }

        String label = stage >= 4
                ? "生生流转 · 回旋叠势 ×4 [极势·松手断首]"
                : "生生流转 · 回旋叠势 ×" + stage + "（按住继续加速 / 松手收尾）";
        gui.centeredText(font, label, cx, cy + 9, stage >= 4 ? 0xFFFFFFFF : 0xFF98F2FF);
    }

    /** 日·第十三型·日之圆环 12 格无缝循环连舞实时指示器（准星下方！） */
    private static void renderSunCycleMeter(GuiGraphicsExtractor gui, Font font, int sw, int sh) {
        int step = ClientBreathState.getSunCycleStep();
        int windowTicks = ClientBreathState.getSunCycleTicks();
        if (step <= 0 || windowTicks <= 0) return;

        int cx = sw / 2;
        int cy = sh / 2 + 22;
        int pipW = 10;
        int gap = 2;
        int totalW = pipW * 12 + gap * 11;
        int startX = cx - totalW / 2;

        for (int i = 1; i <= 12; i++) {
            int px = startX + (i - 1) * (pipW + gap);
            boolean lit = i <= step;
            boolean current = i == step;
            int fillCol = current ? 0xFFFFF4A8 : (lit ? 0xFFFF681E : 0x77280B06);
            int outCol = current ? 0xFFFFFFFF : (lit ? 0xFFFFC866 : 0x8884321A);
            gui.fill(px, cy, px + pipW, cy + 5, fillCol);
            gui.outline(px - 1, cy - 1, pipW + 2, 7, outCol);
        }

        // 衔接窗口剩余时间细条
        float wRatio = Mth.clamp(windowTicks / 36.0F, 0.0F, 1.0F);
        int barFill = Math.round(totalW * wRatio);
        gui.fill(startX, cy + 7, startX + totalW, cy + 9, 0x66220804);
        gui.fill(startX, cy + 7, startX + barFill, cy + 9, 0xFFFFD066);

        SunForm cur = SunForm.getCycleStep(step - 1);
        SunForm next = SunForm.getCycleStep(step);
        String label = "日之圆环 " + step + "/12 · " + cur.getNameComponent().getString().replace(" ", "")
                + "  ▸ 下一型：" + next.getNameComponent().getString().replace(" ", "");
        gui.centeredText(font, label, cx, cy + 12, step == 12 ? 0xFFFFF4A8 : 0xFFFFCA78);
    }

    private static String formatShortWaterForm(WaterForm form) {
        String cnNum = switch (form.getFormNumber()) {
            case 1 -> "壹";
            case 2 -> form == WaterForm.SECOND_LATERAL_WHEEL ? "贰·改" : "贰";
            case 3 -> "参";
            case 4 -> "肆";
            case 5 -> "伍";
            case 6 -> "陆";
            case 7 -> form == WaterForm.SEVENTH_DROP_RIPPLE_CURVE ? "柒·曲" : "柒";
            case 8 -> "捌";
            case 9 -> "玖";
            case 10 -> "拾";
            case 11 -> "拾壹";
            default -> "?";
        };
        return cnNum + " · " + form.getNameComponent(BreathingStyle.WATER).getString().replace(" ", "");
    }

    private static String formatShortSunForm(SunForm form) {
        String cnNum = switch (form.getFormNumber()) {
            case 1 -> form == SunForm.FIRST_DANCING_FLASH ? "壹·闪" : "壹";
            case 2 -> "贰";
            case 3 -> "参";
            case 4 -> "肆";
            case 5 -> "伍";
            case 6 -> "陆";
            case 7 -> "柒";
            case 8 -> "捌";
            case 9 -> "玖";
            case 10 -> "拾";
            case 11 -> "拾壹";
            case 12 -> "拾贰";
            case 13 -> "拾参";
            default -> "?";
        };
        return cnNum + " · " + form.getNameComponent().getString().replace(" ", "");
    }

    private static void renderFormBanner(GuiGraphicsExtractor gui, Font font, int sw, int sh, float partialTick) {
        int ticks = ClientBreathState.getBannerTicks();
        BreathingStyle style = ClientBreathState.getBannerStyle();
        boolean isSun = style == BreathingStyle.SUN;
        WaterForm wForm = ClientBreathState.getBannerForm();
        SunForm sForm = ClientBreathState.getBannerSunForm();
        if (ticks <= 0 || (isSun ? sForm == null : wForm == null)) {
            return;
        }

        float elapsed = (ClientBreathState.BANNER_MAX_TICKS - ticks) + partialTick;
        float alpha;
        if (elapsed < 4.5F) {
            alpha = elapsed / 4.5F;
        } else if (elapsed < 28.0F) {
            alpha = 1.0F;
        } else {
            alpha = Mth.clamp(1.0F - (elapsed - 28.0F) / 14.0F, 0.0F, 1.0F);
        }
        if (alpha <= 0.02F) {
            return;
        }

        String prefix = isSun ? sForm.getPrefixComponent().getString() : wForm.getPrefixComponent(style).getString();
        String mainName = isSun ? sForm.getNameComponent().getString() : wForm.getNameComponent(style).getString();
        boolean ultimate = isSun ? sForm.isUltimate() : wForm.isUltimate();

        int cx = sw / 2;
        int cy = Math.max(26, sh / 6);
        int textW = Math.max(font.width(prefix), font.width(mainName));
        int panelHalfW = Math.max(84, textW / 2 + 28);

        int bgA = Mth.clamp((int) (alpha * 192.0F), 0, 210);
        int borderA = Mth.clamp((int) (alpha * 240.0F), 0, 255);
        int textA = Mth.clamp((int) (alpha * 255.0F), 0, 255);

        int darkCol = (bgA << 24) | (isSun ? 0x220704 : 0x041226);
        int edgeCol = ((bgA / 4) << 24) | (isSun ? 0x3D1006 : 0x062042);
        gui.fill(cx - panelHalfW, cy - 6, cx + panelHalfW, cy + 25, darkCol);
        gui.fill(cx - panelHalfW - 18, cy - 4, cx - panelHalfW, cy + 23, edgeCol);
        gui.fill(cx + panelHalfW, cy - 4, cx + panelHalfW + 18, cy + 23, edgeCol);

        int lineRgb = isSun ? (ultimate ? 0xFFDC64 : 0xFF6E24) : (ultimate ? 0x7CF4FF : 0x3AC2FF);
        int subRgb = isSun ? 0xFFF2B8 : 0xD8FCFF;
        int lineCol = (borderA << 24) | lineRgb;
        int subLineCol = ((borderA * 2 / 3) << 24) | subRgb;
        gui.fill(cx - panelHalfW - 8, cy - 6, cx + panelHalfW + 8, cy - 5, lineCol);
        gui.fill(cx - panelHalfW - 8, cy + 24, cx + panelHalfW + 8, cy + 25, lineCol);
        gui.fill(cx - panelHalfW + 10, cy - 4, cx + panelHalfW - 10, cy - 3, subLineCol);

        int prefixRgb = isSun ? 0xFFC670 : 0x8CE6FF;
        int nameRgb = isSun ? 0xFFFFF4D8 : 0xFFFFFFFF;
        gui.centeredText(font, prefix, cx, cy - 1, (textA << 24) | prefixRgb);
        gui.centeredText(font, "『 " + mainName + " 』", cx, cy + 11, (textA << 24) | (nameRgb & 0x00FFFFFF));
    }

    private static void renderIntuitionCues(
            GuiGraphicsExtractor gui, int sw, int sh, float partialTick, boolean isSun
    ) {
        int cx = sw / 2;
        int cy = sh / 2;

        float dangerA = WaterVfxManager.getDangerAlertAlpha(partialTick);
        if (dangerA > 0.05F) {
            int a = Mth.clamp((int) (dangerA * 170.0F), 0, 180);
            int col = (a << 24) | (isSun ? 0xFFD068 : 0x9EEFFF);
            int r = 11 + Math.round((1.0F - dangerA) * 5.0F);
            int arm = 4;
            gui.fill(cx - r, cy - r, cx - r + arm, cy - r + 1, col);
            gui.fill(cx - r, cy - r, cx - r + 1, cy - r + arm, col);
            gui.fill(cx + r - arm, cy - r, cx + r, cy - r + 1, col);
            gui.fill(cx + r - 1, cy - r, cx + r, cy - r + arm, col);
            gui.fill(cx - r, cy + r - 1, cx - r + arm, cy + r, col);
            gui.fill(cx - r, cy + r - arm, cx - r + 1, cy + r, col);
            gui.fill(cx + r - arm, cy + r - 1, cx + r, cy + r, col);
            gui.fill(cx + r - 1, cy + r - arm, cx + r, cy + r, col);
        }
    }

    /** G 键展开：水之呼吸 · 原版全十一型完整速查卷轴 */
    private static void renderFullElevenFormGuide(
            GuiGraphicsExtractor gui, Font font, int sw, int sh
    ) {
        int count = WaterForm.selectableCount();
        String title = Component.translatable("hud.nichirin.guide_title").getString();
        String footer = "[Shift+滚轮 / Z·C]切型  [右键]出招  [R]全集中/蓄气";

        String[] lines = new String[count + 2];
        lines[0] = title;
        for (int i = 0; i < count; i++) {
            WaterForm f = WaterForm.getSelectable(i);
            String marker = (i == ClientBreathState.getSelectedFormIndex()) ? "▸ " : "  ";
            lines[i + 1] = marker + formatShortWaterForm(f) + " - " + f.getDescComponent().getString();
        }
        lines[count + 1] = footer;

        renderGuideBox(gui, font, sw, sh, lines, ClientBreathState.getSelectedFormIndex() + 1, false);
    }

    /** G 键展开：日之呼吸 · 火之神神乐 全十三型完整速查卷轴 */
    private static void renderFullSunFormGuide(
            GuiGraphicsExtractor gui, Font font, int sw, int sh
    ) {
        int count = SunForm.selectableCount();
        String title = Component.translatable("hud.nichirin.sun_guide_title").getString();
        String footer = "[Shift+滚轮 / Z·C]切型  [右键]出招  [R]赫刀全集中/蓄气";

        String[] lines = new String[count + 2];
        lines[0] = title;
        for (int i = 0; i < count; i++) {
            SunForm f = SunForm.getSelectable(i);
            String marker = (i == ClientBreathState.getSelectedFormIndex()) ? "▸ " : "  ";
            lines[i + 1] = marker + formatShortSunForm(f) + " - " + f.getDescComponent().getString();
        }
        lines[count + 1] = footer;

        renderGuideBox(gui, font, sw, sh, lines, ClientBreathState.getSelectedFormIndex() + 1, true);
    }

    private static void renderGuideBox(
            GuiGraphicsExtractor gui, Font font, int sw, int sh, String[] lines, int selLine, boolean isSun
    ) {
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, font.width(line));
        }
        int pad = 5;
        int boxW = maxW + pad * 2;
        int boxH = lines.length * 10 + pad * 2;
        int x = sw - boxW - 8;
        int y = sh - boxH - 42;
        if (y < 8) y = 8;

        gui.fill(x, y, x + boxW, y + boxH, isSun ? 0xB41C0604 : 0xAA040E1E);
        gui.outline(x, y, boxW, boxH, isSun ? 0x99D8561E : 0x883296D8);

        gui.fill(x + 2, y + pad + selLine * 10 - 1, x + boxW - 2, y + pad + (selLine + 1) * 10 - 1,
                isSun ? 0xAA882408 : 0x99124E96);

        for (int i = 0; i < lines.length; i++) {
            int col;
            if (i == 0) {
                col = isSun ? 0xFFFFCC58 : 0xFF66EEFF;
            } else if (i == selLine) {
                col = isSun ? 0xFFFFF8D6 : 0xFFFFFFFF;
            } else if (i == lines.length - 1) {
                col = isSun ? 0xFFF4BC80 : 0xFF9CDCF8;
            } else {
                col = isSun ? 0xCCF2D4BE : 0xCCCEE8F8;
            }
            gui.text(font, lines[i], x + pad, y + pad + i * 10, col, true);
        }
    }
}
