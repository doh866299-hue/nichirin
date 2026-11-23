package com.nichirin.network;

import com.nichirin.NichirinMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端 玩家呼吸气力槽、当前呼吸流派与状态同步包
 */
public record BreathSyncPayload(
        byte styleId,
        float breath,
        float maxBreath,
        boolean totalConcentration,
        boolean inhaling,
        int specialDomainTicks,
        int globalRecoveryTicks,
        int lastCastFormOrdinal,
        int castCounter
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BreathSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "breath_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BreathSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BreathSyncPayload decode(RegistryFriendlyByteBuf buf) {
                    byte styleId = buf.readByte();
                    float breath = buf.readFloat();
                    float maxBreath = buf.readFloat();
                    byte flags = buf.readByte();
                    boolean totalConc = (flags & 1) != 0;
                    boolean inhaling = (flags & 2) != 0;
                    int specialTicks = buf.readVarInt();
                    int globalRecoveryTicks = buf.readVarInt();
                    int lastCastFormOrdinal = buf.readVarInt();
                    int castCounter = buf.readVarInt();
                    return new BreathSyncPayload(
                            styleId, breath, maxBreath, totalConc, inhaling,
                            specialTicks, globalRecoveryTicks, lastCastFormOrdinal, castCounter
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BreathSyncPayload p) {
                    buf.writeByte(p.styleId);
                    buf.writeFloat(p.breath);
                    buf.writeFloat(p.maxBreath);
                    byte flags = 0;
                    if (p.totalConcentration) flags |= 1;
                    if (p.inhaling) flags |= 2;
                    buf.writeByte(flags);
                    buf.writeVarInt(p.specialDomainTicks);
                    buf.writeVarInt(p.globalRecoveryTicks);
                    buf.writeVarInt(p.lastCastFormOrdinal);
                    buf.writeVarInt(p.castCounter);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
