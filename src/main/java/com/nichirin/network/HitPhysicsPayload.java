package com.nichirin.network;

import com.nichirin.NichirinMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端 -> 客户端 精确物理命中与布娃娃动量同步包：
 * 携带 140Hz 刀刃扫描算出的精确 3D 接触点、刀锋切线方向、冲量大小与双向卡肉时长，
 * 同步驱动：出刀顿刀卡肉 + 活体受击物理歪斜后仰 + 斩杀 144Hz 关节布娃娃飞出 + 顺刀势斩痕火花！
 */
public record HitPhysicsPayload(
        int attackerId,
        int targetId,
        double hitX,
        double hitY,
        double hitZ,
        float dirX,
        float dirY,
        float dirZ,
        float force,
        float hitstopTicks,
        byte flags
) implements CustomPacketPayload {

    public static final byte FLAG_THREAD_BREAK = 1;
    public static final byte FLAG_LETHAL = 2;
    public static final byte FLAG_HEAVY = 4;
    public static final byte FLAG_SUN_STYLE = 8;
    public static final byte FLAG_TOTAL_CONC = 16;

    public static final CustomPacketPayload.Type<HitPhysicsPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "hit_physics"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HitPhysicsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public HitPhysicsPayload decode(RegistryFriendlyByteBuf buf) {
                    return new HitPhysicsPayload(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readByte()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, HitPhysicsPayload p) {
                    buf.writeVarInt(p.attackerId);
                    buf.writeVarInt(p.targetId);
                    buf.writeDouble(p.hitX);
                    buf.writeDouble(p.hitY);
                    buf.writeDouble(p.hitZ);
                    buf.writeFloat(p.dirX);
                    buf.writeFloat(p.dirY);
                    buf.writeFloat(p.dirZ);
                    buf.writeFloat(p.force);
                    buf.writeFloat(p.hitstopTicks);
                    buf.writeByte(p.flags);
                }
            };

    public boolean isThreadBreak() { return (flags & FLAG_THREAD_BREAK) != 0; }
    public boolean isLethal() { return (flags & FLAG_LETHAL) != 0; }
    public boolean isHeavy() { return (flags & FLAG_HEAVY) != 0; }
    public boolean isSunStyle() { return (flags & FLAG_SUN_STYLE) != 0; }
    public boolean isTotalConc() { return (flags & FLAG_TOTAL_CONC) != 0; }

    public static byte makeFlags(boolean threadBreak, boolean lethal, boolean heavy, boolean sunStyle, boolean totalConc) {
        byte f = 0;
        if (threadBreak) f |= FLAG_THREAD_BREAK;
        if (lethal) f |= FLAG_LETHAL;
        if (heavy) f |= FLAG_HEAVY;
        if (sunStyle) f |= FLAG_SUN_STYLE;
        if (totalConc) f |= FLAG_TOTAL_CONC;
        return f;
    }

    public static void sendNear(
            ServerLevel level, int attackerId, int targetId,
            double hitX, double hitY, double hitZ,
            float dirX, float dirY, float dirZ,
            float force, float hitstopTicks,
            boolean threadBreak, boolean lethal, boolean heavy, boolean sunStyle, boolean totalConc
    ) {
        PacketDistributor.sendToPlayersNear(
                level, null, hitX, hitY, hitZ, 96.0,
                new HitPhysicsPayload(
                        attackerId, targetId, hitX, hitY, hitZ,
                        dirX, dirY, dirZ, force, hitstopTicks,
                        makeFlags(threadBreak, lethal, heavy, sunStyle, totalConc)
                )
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
