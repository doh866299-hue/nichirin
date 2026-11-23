package com.nichirin.client;

import com.nichirin.client.anim.FirstPersonKatanaRenderer;
import com.nichirin.client.anim.SwordChoreography;
import com.nichirin.client.hud.BreathingHudOverlay;
import com.nichirin.client.input.ComboInputController;
import com.nichirin.client.physics.MobPhysicsEngine;
import com.nichirin.client.vfx.WaterVfxManager;
import com.nichirin.client.vfx.WaterVfxRenderer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 客户端 NeoForge 事件总线订阅中心
 */
public final class NichirinClientEvents {

    @SubscribeEvent
    public void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        ComboInputController.onMouseButtonPre(event);
    }

    @SubscribeEvent
    public void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        ComboInputController.onMouseScroll(event);
    }

    @SubscribeEvent
    public void onClientTickPost(ClientTickEvent.Post event) {
        ClientBreathState.clientTick();
        ComboInputController.clientTick();
        SwordChoreography.clientTick();
        WaterVfxManager.clientTick();
        MobPhysicsEngine.clientTick();
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        FirstPersonKatanaRenderer.onRenderHand(event);
    }

    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre<?> event) {
        FirstPersonKatanaRenderer.onRenderPlayerPre(event);
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post<?> event) {
        FirstPersonKatanaRenderer.onRenderPlayerPost(event);
    }

    @SubscribeEvent
    public void onRenderLivingPre(RenderLivingEvent.Pre<?, ?, ?> event) {
        MobPhysicsEngine.onRenderLivingPre(event);
    }

    @SubscribeEvent
    public void onRenderLivingPost(RenderLivingEvent.Post<?, ?, ?> event) {
        MobPhysicsEngine.onRenderLivingPost(event);
    }

    @SubscribeEvent
    public void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        WaterVfxRenderer.onExtractLevelRenderState(event);
        MobPhysicsEngine.onExtractLevelRenderState(event);
    }

    @SubscribeEvent
    public void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        WaterVfxRenderer.onSubmitCustomGeometry(event);
        MobPhysicsEngine.onSubmitCustomGeometry(event);
    }

    @SubscribeEvent
    public void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        WaterVfxManager.applyCameraShake(event);
    }

    @SubscribeEvent
    public void onComputeFov(ViewportEvent.ComputeFov event) {
        WaterVfxManager.applyFovModifier(event);
    }

    @SubscribeEvent
    public void onRenderGuiPost(RenderGuiEvent.Post event) {
        BreathingHudOverlay.onRenderGuiPost(event);
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBreathState.reset();
        ComboInputController.resetAll();
        SwordChoreography.clearAll();
        FirstPersonKatanaRenderer.clearAll();
        WaterVfxManager.clearAll();
        MobPhysicsEngine.clearAll();
    }
}
