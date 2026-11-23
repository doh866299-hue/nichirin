package com.nichirin.network;

import com.nichirin.NichirinMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端 呼吸法动作同步包
 * 支持方案 A 一键出型、WASD 方向身法传递、生生流转持续回旋松手收尾、吸气与全集中切换
 */
public record BreathingActionPayload(byte action, int arg1, int arg2) implements CustomPacketPayload {
    /** 释放当前选中的剑型：arg1 = WaterForm ordinal, arg2 = 打包上下文位(方向/空中/潜行) */
    public static final byte ACTION_CAST_FORM = 1;
    public static final byte ACTION_START_CHARGE = 2;
    public static final byte ACTION_STOP_CHARGE = 3;
    public static final byte ACTION_SET_INHALE = 4;
    public static final byte ACTION_TOGGLE_TOTAL_CONCENTRATION = 5;
    public static final byte ACTION_SWITCH_STYLE = 6;
    /** 普通左键挥刀物理扫掠通知（平A斩箭、弹火球、割草、灭火、刮壁火花） */
    public static final byte ACTION_BASIC_SWING = 7;
    /** 拾之型·生生流转 松开右键信号：立即借当前已叠加的回旋势能斩出收尾一击！ */
    public static final byte ACTION_FLUX_RELEASE = 8;

    /** arg2 上下文打包辅助：bit0=空中, bit1=潜行, bits2..3=前向(0无/1前/2后), bits4..5=横向(0无/1左/2右) */
    public static int packContext(boolean airborne, boolean sneak, int fwdSign, int strafeSign) {
        int bits = 0;
        if (airborne) bits |= 1;
        if (sneak) bits |= 2;
        int fCode = fwdSign > 0 ? 1 : (fwdSign < 0 ? 2 : 0);
        int sCode = strafeSign > 0 ? 1 : (strafeSign < 0 ? 2 : 0);
        bits |= (fCode << 2);
        bits |= (sCode << 4);
        return bits;
    }

    public static boolean unpackAirborne(int ctx) { return (ctx & 1) != 0; }
    public static boolean unpackSneak(int ctx) { return (ctx & 2) != 0; }
    public static int unpackFwdSign(int ctx) {
        int c = (ctx >> 2) & 3;
        return c == 1 ? 1 : (c == 2 ? -1 : 0);
    }
    public static int unpackStrafeSign(int ctx) {
        int c = (ctx >> 4) & 3;
        return c == 1 ? 1 : (c == 2 ? -1 : 0);
    }

    public static final CustomPacketPayload.Type<BreathingActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "breathing_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BreathingActionPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public BreathingActionPayload decode(RegistryFriendlyByteBuf buf) {
                    byte action = buf.readByte();
                    int arg1 = buf.readVarInt();
                    int arg2 = buf.readVarInt();
                    return new BreathingActionPayload(action, arg1, arg2);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, BreathingActionPayload payload) {
                    buf.writeByte(payload.action);
                    buf.writeVarInt(payload.arg1);
                    buf.writeVarInt(payload.arg2);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
