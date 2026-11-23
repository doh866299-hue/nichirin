package com.nichirin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nichirin.NichirinMod;
import com.nichirin.client.anim.KatanaArmPose;
import com.nichirin.client.physics.MobPhysicsEngine;
import com.nichirin.client.vfx.WaterVfxManager;
import com.nichirin.client.vfx.WaterVfxRenderer;
import com.nichirin.network.BreathSyncPayload;
import com.nichirin.network.HitPhysicsPayload;
import com.nichirin.network.WaterVfxPayload;
import com.nichirin.registry.ModItems;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 模组客户端专属入口：注册按键映射、浮世绘 3D 渲染管线、客户端网络包处理器与游戏内事件
 */
@Mod(value = NichirinMod.MOD_ID, dist = Dist.CLIENT)
public final class NichirinClient {
    public static final KeyMapping.Category BREATHING_CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "breathing"));

    /** R 键：短按切换全集中·常中 / 长按深呼吸快速蓄气 */
    public static final KeyMapping KEY_BREATH = new KeyMapping(
            "key.nichirin.total_concentration",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R,
            BREATHING_CATEGORY
    );

    /** G 键：开关右下角出招提示表 */
    public static final KeyMapping KEY_TOGGLE_GUIDE = new KeyMapping(
            "key.nichirin.toggle_guide",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_G,
            BREATHING_CATEGORY
    );

    /** Z 键：切换至上一剑型（也可按住 Shift + 鼠标滚轮极速切换！） */
    public static final KeyMapping KEY_PREV_FORM = new KeyMapping(
            "key.nichirin.prev_form",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_Z,
            BREATHING_CATEGORY
    );

    /** C 键：切换至下一剑型（也可按住 Shift + 鼠标滚轮极速切换！） */
    public static final KeyMapping KEY_NEXT_FORM = new KeyMapping(
            "key.nichirin.next_form",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_C,
            BREATHING_CATEGORY
    );

    /** X 键：流派切换（预留独立日之呼吸十三型入口） */
    public static final KeyMapping KEY_SWITCH_STYLE = new KeyMapping(
            "key.nichirin.switch_style",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_X,
            BREATHING_CATEGORY
    );

    public NichirinClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeyMappings);
        modEventBus.addListener(this::registerRenderPipelines);
        modEventBus.addListener(this::registerClientPayloads);
        modEventBus.addListener(this::registerClientExtensions);
        modEventBus.addListener(MobPhysicsEngine::registerRenderStateModifier);

        NeoForge.EVENT_BUS.register(new NichirinClientEvents());
    }

    private void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return hand == InteractionHand.MAIN_HAND ? KatanaArmPose.getArmPose() : null;
            }
        }, ModItems.WATER_NICHIRIN_BLADE.get());
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(BREATHING_CATEGORY);
        event.register(KEY_BREATH);
        event.register(KEY_PREV_FORM);
        event.register(KEY_NEXT_FORM);
        event.register(KEY_TOGGLE_GUIDE);
        event.register(KEY_SWITCH_STYLE);
    }

    private void registerRenderPipelines(RegisterRenderPipelinesEvent event) {
        WaterVfxRenderer.registerPipelines(event);
    }

    private void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(BreathSyncPayload.TYPE, ClientBreathState::handleSyncPayload);
        event.register(WaterVfxPayload.TYPE, WaterVfxManager::handlePayload);
        event.register(HitPhysicsPayload.TYPE, MobPhysicsEngine::handleHitPhysicsPayload);
    }
}
