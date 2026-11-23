package com.nichirin;

import com.nichirin.breathing.BreathingManager;
import com.nichirin.breathing.BreathingServerEvents;
import com.nichirin.network.BreathSyncPayload;
import com.nichirin.network.BreathingActionPayload;
import com.nichirin.network.HitPhysicsPayload;
import com.nichirin.network.WaterVfxPayload;
import com.nichirin.registry.ModCreativeTabs;
import com.nichirin.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 鬼灭 · 日轮呼吸法模组主入口（Minecraft 26.3 / NeoForge）
 */
@Mod(NichirinMod.MOD_ID)
public class NichirinMod {
    public static final String MOD_ID = "nichirin";

    public NichirinMod(IEventBus modEventBus) {
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.register(new BreathingServerEvents());
    }

    /**
     * 注册服务端/客户端网络通道（客户端处理函数于 NichirinClient 中独立绑定，保证专用服务端安全）
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                BreathingActionPayload.TYPE,
                BreathingActionPayload.STREAM_CODEC,
                BreathingManager::handleActionPayload
        );
        registrar.playToClient(BreathSyncPayload.TYPE, BreathSyncPayload.STREAM_CODEC);
        registrar.playToClient(WaterVfxPayload.TYPE, WaterVfxPayload.STREAM_CODEC);
        registrar.playToClient(HitPhysicsPayload.TYPE, HitPhysicsPayload.STREAM_CODEC);
    }
}
