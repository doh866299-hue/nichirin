package com.nichirin.network;

import com.nichirin.NichirinMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端 -> 客户端 浮世绘水之呼吸 3D 特效与剑术动作广播包
 * 携带施法者 entityId，使所有客户端能够同步驱动人物/日轮刀剑术动作、常态刀光带与跟随式水龙冲锋
 */
public record WaterVfxPayload(
        byte kind,
        int entityId,
        double x,
        double y,
        double z,
        float yaw,
        float param1,
        float param2,
        float param3,
        int duration,
        byte flags
) implements CustomPacketPayload {

    /** 1: 浮世绘斩击动作触发（壹之型水面斩 / 参之型斜斩 / 命中水花） */
    public static final byte KIND_WATER_SLASH = 1;
    /** 2: 贰之型·水车 / 改·横水车 动作与水轮尾迹（param1=半径, param2=0竖直前空翻/1水平旋斩, param3=前进距离） */
    public static final byte KIND_WATER_WHEEL = 2;
    /** 3: 参之型·流流舞 单段滑步舞剑动作（param1=位移长度, param2=弧偏转角, param3=段数0..2） */
    public static final byte KIND_FLOWING_STEP = 3;
    /** 4: 陆之型·扭转漩涡 旋身升龙卷动作 + 反重力方块（param1=半径, param2=高度） */
    public static final byte KIND_WHIRLPOOL = 4;
    /** 5: 捌之型·泷壶 / 生生流转终点 冲天瀑布水柱 + 地面海浪翻涌与方块飞崩（param1=半径, param2=柱高, param3=先一闪后冲天延迟tick数） */
    public static final byte KIND_WATERFALL_BASIN = 5;
    /** 6: 蓄力阵势（生生流转/凪：地面浮世绘水阵 + 旋转升腾方块 + 拔刀蓄势动作） */
    public static final byte KIND_CHARGE_AURA = 6;
    /** 7: 拾之型·生生流转 人刀合一化龙发射冲锋（entityId 跟随本体！param1=推进距离, param2=蓄力档位1..3, param3=俯仰角） */
    public static final byte KIND_WATER_DRAGON = 7;
    /** 8: 拾壹之型·凪 静止深海镜面领域 + 止水持刀架势（param1=领域半径） */
    public static final byte KIND_DEAD_CALM_DOMAIN = 8;
    /** 9: 凪·无形乱斩 / 斩碎弹射物（触发瞬闪拔刀动作 + 黑白水刃与弯月弧） */
    public static final byte KIND_DEAD_CALM_COUNTER = 9;
    /** 10: 全集中呼吸开启脉冲 / 深呼吸冷息波纹（param1=半径, param2=0吸气/1爆发） */
    public static final byte KIND_BREATH_PULSE = 10;
    /** 11: 捌之型·泷壶 空中高举俯冲起手动作（触地前实时下劈刀光） */
    public static final byte KIND_WATERFALL_DIVE_START = 11;
    /** 12: 日之呼吸·日晕之龙 终结冲霄撞击大招（致敬孙策龙限：熔岩焦土神龙图腾 + 巨型烈焰神龙盘旋冲霄咆哮 + 连环裂空斩与地壳飞崩！param1=爆裂半径, param2=冲霄高度, param3=蓄力档位1..3） */
    public static final byte KIND_SUN_DRAGON_FINALE = 12;
    /** 13: 斩击命中实感回馈（顿刀卡肉 + 定向斩痕火花 + 隙之线斩断 + 一闪断首！entityId=攻击者, param1=打击重量, param2=受击实体ID, param3=标记位[1断线/2斩杀/4重击]） */
    public static final byte KIND_HIT_IMPACT = 13;
    /** 14: 鬼灭战斗直觉脉动（看破敌人杀气强开「隙之线」/ 招式起势吸气聚压！entityId=玩家, param1=锁定敌怪ID, param2=1看破/2蓄势起手） */
    public static final byte KIND_INTUITION_PULSE = 14;
    /** 15: 刀先出、龙后跟「二段巨龙尾流碾压」爆点特效（在延迟轨迹处炸开水浪与锐利斩痕！param1=半径, param2=强度, param3=命中数） */
    public static final byte KIND_DRAGON_WAKE_IMPACT = 15;
    /** 16: 柒之型·雫波纹突刺 / 立体同心水波纹冲击环（param1=波纹最大半径, param2=俯仰角pitchDeg, param3=模式[0垂直刺击面波纹/1水平贴地波纹/2柔和慈雨细波]） */
    public static final byte KIND_RIPPLE_THRUST = 16;
    /** 17: 肆之型·打潮 单拍连绵海潮斩（param1=斩幅半径, param2=段数0..3, param3=是否收尾拍岸爆浪） */
    public static final byte KIND_STRIKING_TIDE = 17;
    /** 18: 伍之型·乾天的慈雨 温柔高弧细雨斩与甘霖落雨（param1=范围半径, param2=是否触发慈雨处决, param3=保留） */
    public static final byte KIND_BLESSED_RAIN = 18;
    /** 19: 玖之型·水流飞沫·乱 踏水轻功碎步水环 + 掠斩（param1=冲刺方向yaw, param2=是否空中点水, param3=保留） */
    public static final byte KIND_SPLASH_STEP = 19;
    /** 20: 拾之型·生生流转 动态回旋叠势进阶（param1=当前圈数1..4, param2=本圈时长ticks, param3=速度倍率） */
    public static final byte KIND_DRAGON_SPIN_STAGE = 20;
    /** 21: 日·幻日虹 3D 炽热虹光残像留影（x,y,z=残像立身位置, yaw=面朝角度, param1=闪避偏移方向yaw, param2=规模） */
    public static final byte KIND_SUN_AFTERIMAGE = 21;
    /** 22: 日之呼吸专属剑型动作与炽焰演出统一包（param1=BladeKinematics animType 17..23, param2=范围半径, param3=子阶段/变体） */
    public static final byte KIND_SUN_FORM_VFX = 22;

    /** 标志位：全集中·常中强化状态 */
    public static final byte FLAG_TOTAL_CONCENTRATION = 1;
    /** 标志位：强化/次级变体 */
    public static final byte FLAG_ENHANCED = 2;
    /** 标志位：日之呼吸·火之神神乐流派（黑红金烈焰浮世绘视觉） */
    public static final byte FLAG_SUN_STYLE = 4;

    public static final CustomPacketPayload.Type<WaterVfxPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "water_vfx"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaterVfxPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WaterVfxPayload decode(RegistryFriendlyByteBuf buf) {
                    return new WaterVfxPayload(
                            buf.readByte(),
                            buf.readVarInt(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readFloat(),
                            buf.readVarInt(),
                            buf.readByte()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, WaterVfxPayload p) {
                    buf.writeByte(p.kind);
                    buf.writeVarInt(p.entityId);
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                    buf.writeDouble(p.z);
                    buf.writeFloat(p.yaw);
                    buf.writeFloat(p.param1);
                    buf.writeFloat(p.param2);
                    buf.writeFloat(p.param3);
                    buf.writeVarInt(p.duration);
                    buf.writeByte(p.flags);
                }
            };

    public boolean isTotalConcentration() {
        return (flags & FLAG_TOTAL_CONCENTRATION) != 0;
    }

    public boolean isEnhanced() {
        return (flags & FLAG_ENHANCED) != 0;
    }

    public boolean isSunStyle() {
        return (flags & FLAG_SUN_STYLE) != 0;
    }

    public static byte makeFlags(boolean totalConc, boolean enhanced, boolean sunStyle) {
        byte f = 0;
        if (totalConc) f |= FLAG_TOTAL_CONCENTRATION;
        if (enhanced) f |= FLAG_ENHANCED;
        if (sunStyle) f |= FLAG_SUN_STYLE;
        return f;
    }

    public static void sendNear(
            ServerLevel level,
            byte kind,
            int entityId,
            double x,
            double y,
            double z,
            float yaw,
            float param1,
            float param2,
            float param3,
            int duration,
            boolean totalConc,
            boolean enhanced,
            boolean sunStyle
    ) {
        WaterVfxPayload payload = new WaterVfxPayload(
                kind, entityId, x, y, z, yaw, param1, param2, param3, duration, makeFlags(totalConc, enhanced, sunStyle)
        );
        PacketDistributor.sendToPlayersNear(level, null, x, y, z, 64.0, payload);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
