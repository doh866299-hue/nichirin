package com.nichirin.breathing;

import com.nichirin.network.WaterVfxPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旷野之息式 · 日轮刀底层物理与元素化学引擎（World Physics & Chemistry Engine）
 * <p>
 * 遵循自然直觉，而非孤立的数值特判——只要刀锋、水浪、烈焰真实扫过世界，所有交互皆自然涌现：
 * 1. 刀锋物理扫掠：任意挥刀皆可割草断蛛网、斩断来袭箭矢、击飞弹反火球、刮擦硬壁迸火花、卷动掉落物
 * 2. 水火元素化学：水灭火/润湿/高空水垫缓震/水雨增速；火点营火蜡烛/引爆TNT/融雪/烤熟生肉/生成真实上升热气流；水火相激触发蒸汽爆裂
 * 3. 真实动量与势能：移速与下坠高度直接转化为刀势威力、冲击半径与击退冲量
 */
public final class BladeWorldPhysics {
    private static final Map<Integer, Long> WET_UNTIL = new ConcurrentHashMap<>();
    private static final List<ThermalUpdraftZone> UPDRAFTS = new ArrayList<>();

    private BladeWorldPhysics() {}

    /**
     * 核心统一入口：沿刀锋真实扫掠的 3D 弧面，自动与弹射物、地形材质、火焰/冰雪/水面、掉落物发生物理交互！
     */
    public static void processBladeSweep(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            Vec3 center, Vec3 forward, float radius, double minDot, boolean sun, boolean heavy
    ) {
        // 1. 水之呼吸扑灭自身火焰（水能灭火的直接直觉！）
        if (!sun && player.isOnFire()) {
            player.clearFire();
            level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.35, 0.45, 0.35, 0.06);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.85F, 1.25F);
        }

        // 2. 物理斩箭 / 击飞弹反火球（无需特定防御技能，挥刀扫中即斩断/反弹！）
        deflectProjectilesInArc(player, level, state, center, forward, radius + 1.25F, minDot - 0.18, sun);

        // 3. 刀风卷动散落物品（日之呼吸高温顺手烤熟地上的生肉！）
        swirlAndCookLooseItems(player, level, center, forward, radius + 1.3F, minDot, sun);

        // 4. 方块与元素化学交互（割草、灭火、点营火/蜡烛、引爆TNT、融雪、水/熔岩蒸汽、硬壁刮擦火花）
        interactBlocksInArc(player, level, center, forward, Math.min(radius, 6.2F), minDot, sun, heavy);
    }

    /** 任意挥刀扫过飞行中的弹射物：箭矢当场劈断，火球/风弹沿视线完美击飞弹反！ */
    private static void deflectProjectilesInArc(
            ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state,
            Vec3 center, Vec3 forward, float radius, double minDot, boolean sun
    ) {
        AABB box = new AABB(center.x - radius, center.y - 2.0, center.z - radius,
                center.x + radius, center.y + 2.4, center.z + radius);
        List<Projectile> list = level.getEntitiesOfClass(Projectile.class, box,
                pr -> pr.isAlive() && !pr.onGround() && pr.getOwner() != player);
        if (list.isEmpty()) return;

        Vec3 aim = player.getLookAngle().normalize();
        int handled = 0;
        for (Projectile proj : list) {
            Vec3 toProj = proj.position().subtract(player.position().add(0, 1.0, 0));
            double dist = toProj.length();
            if (dist > radius + 0.8) continue;
            if (dist > 0.7 && toProj.scale(1.0 / dist).dot(forward) < minDot) continue;

            double px = proj.getX(), py = proj.getY(), pz = proj.getZ();
            if (proj instanceof AbstractHurtingProjectile hurting) {
                // 火球 / 烈焰弹 / 风弹：以玩家准星方向轰然击飞弹反！
                hurting.setOwner(player);
                double spd = Math.max(1.35, hurting.getDeltaMovement().length() * 1.45);
                hurting.setDeltaMovement(aim.scale(spd));
                BreathingManager.syncEntityVelocity(hurting);
                level.playSound(null, px, py, pz, SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.15F, 1.35F);
                level.playSound(null, px, py, pz, SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 0.95F, 1.45F);
                level.sendParticles(sun ? ParticleTypes.FLAME : ParticleTypes.ELECTRIC_SPARK, px, py, pz, 14, 0.25, 0.25, 0.25, 0.14);
            } else {
                // 箭矢 / 三叉戟 / 潜影弹：凌空一刀劈断！
                proj.discard();
                level.playSound(null, px, py, pz, SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 0.85F, 1.70F);
                level.playSound(null, px, py, pz, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.75F, 1.90F);
                level.sendParticles(ParticleTypes.CRIT, px, py, pz, 8, 0.2, 0.2, 0.2, 0.12);
            }

            if (handled < 2) {
                // 触发清脆的截断顿刀反馈与斩痕
                WaterVfxPayload.sendNear(
                        level, WaterVfxPayload.KIND_HIT_IMPACT, player.getId(),
                        px, py, pz, player.getYRot(),
                        1.35F, -1.0F, 4.0F,
                        10, state.totalConcentration, false, sun
                );
            }
            handled++;
        }
    }

    /** 刀风卷动物品 + 日之呼吸高温瞬间炙烤地面生肉！ */
    private static void swirlAndCookLooseItems(
            ServerPlayer player, ServerLevel level, Vec3 center, Vec3 forward, float radius, double minDot, boolean sun
    ) {
        AABB box = new AABB(center.x - radius, center.y - 1.8, center.z - radius,
                center.x + radius, center.y + 2.0, center.z + radius);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive)) {
            Vec3 toItem = item.position().subtract(player.position());
            double d = toItem.horizontalDistance();
            if (d > radius) continue;
            if (d > 0.8 && new Vec3(toItem.x, 0, toItem.z).normalize().dot(forward) < minDot) continue;

            // 真实刀风：沿横向挥刀方向与微向上气流卷飞轻物
            item.push(forward.x * 0.22 - right.x * 0.26, 0.22, forward.z * 0.22 - right.z * 0.26);
            BreathingManager.syncEntityVelocity(item);

            if (sun) {
                ItemStack cooked = cookRawMeatStack(item.getItem());
                if (cooked != null) {
                    item.setItem(cooked);
                    level.sendParticles(ParticleTypes.FLAME, item.getX(), item.getY() + 0.25, item.getZ(), 5, 0.15, 0.15, 0.15, 0.03);
                    level.sendParticles(ParticleTypes.SMOKE, item.getX(), item.getY() + 0.3, item.getZ(), 4, 0.12, 0.15, 0.12, 0.02);
                }
            }
        }
    }

    /** 沿刀弧与世界方块发生自然材质与元素化学交互（严格只处理柔韧植被/火/雪/营火/TNT/水面/熔岩，绝不破坏建筑与箱子！） */
    private static void interactBlocksInArc(
            ServerPlayer player, ServerLevel level, Vec3 center, Vec3 forward, float radius, double minDot, boolean sun, boolean heavy
    ) {
        int rCeil = Mth.ceil(radius);
        int cx = Mth.floor(center.x);
        int cy = Mth.floor(center.y);
        int cz = Mth.floor(center.z);

        boolean playedSteam = false;
        boolean playedSpark = false;
        boolean playedIgnite = false;
        boolean playedWaterSplash = false;
        int foliageCut = 0;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -rCeil; dx <= rCeil; dx++) {
                for (int dz = -rCeil; dz <= rCeil; dz++) {
                    double distSq = dx * dx + dz * dz;
                    if (distSq > radius * radius) continue;
                    if (distSq > 0.8) {
                        double inv = 1.0 / Math.sqrt(distSq);
                        if ((dx * inv) * forward.x + (dz * inv) * forward.z < minDot) continue;
                    }

                    pos.set(cx + dx, cy + dy, cz + dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) continue;

                    // A. 刀锋所过之处，杂草 / 花卉 / 蕨类 / 蜘蛛网 / 藤蔓应声而断！
                    if (foliageCut < 28 && isCuttableFoliage(st)) {
                        level.destroyBlock(pos, true, player);
                        foliageCut++;
                        continue;
                    }

                    // B. 水之呼吸元素化学：灭火 / 熄灭营火蜡烛 / 岩浆生汽 / 激荡水面
                    if (!sun) {
                        if (st.is(BlockTags.FIRE)) {
                            level.removeBlock(pos, false);
                            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5, 4, 0.25, 0.25, 0.25, 0.04);
                            if (!playedSteam) {
                                playedSteam = true;
                                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.75F, 1.35F);
                            }
                        } else if (CampfireBlock.isLitCampfire(st)) {
                            level.setBlock(pos, st.setValue(CampfireBlock.LIT, false), 11);
                            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.2, 0.25, 0.2, 0.04);
                            if (!playedSteam) {
                                playedSteam = true;
                                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.80F, 1.15F);
                            }
                        } else if (AbstractCandleBlock.isLit(st)) {
                            AbstractCandleBlock.extinguish(player, st, level, pos);
                        } else if (st.is(Blocks.LAVA)) {
                            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 4, 0.35, 0.25, 0.35, 0.05);
                            if (!playedSteam) {
                                playedSteam = true;
                                level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.PLAYERS, 0.85F, 1.20F);
                            }
                        } else if (st.is(Blocks.WATER) && dy <= 0) {
                            if (!playedWaterSplash) {
                                playedWaterSplash = true;
                                level.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 16, 0.8, 0.35, 0.8, 0.14);
                                level.playSound(null, pos, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS, 0.70F, 1.18F);
                            }
                        }
                    } else {
                        // C. 日之呼吸热力学：点燃营火/蜡烛、引爆TNT、融化薄雪、灼烧水面/冰面生汽！
                        if (CampfireBlock.canLight(st)) {
                            level.setBlock(pos, st.setValue(CampfireBlock.LIT, true), 11);
                            level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.04);
                            if (!playedIgnite) {
                                playedIgnite = true;
                                level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 0.90F, 1.10F);
                            }
                        } else if (st.is(BlockTags.CANDLES) && st.hasProperty(AbstractCandleBlock.LIT) && !st.getValue(AbstractCandleBlock.LIT)) {
                            level.setBlock(pos, st.setValue(AbstractCandleBlock.LIT, true), 11);
                            if (!playedIgnite) {
                                playedIgnite = true;
                                level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS, 0.75F, 1.35F);
                            }
                        } else if (st.is(Blocks.TNT)) {
                            TntBlock.prime(level, pos, player, player.getMainHandItem());
                            level.removeBlock(pos, false);
                            level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.35, 0.35, 0.35, 0.08);
                        } else if (st.is(Blocks.SNOW) && st.hasProperty(SnowLayerBlock.LAYERS) && st.getValue(SnowLayerBlock.LAYERS) <= 2) {
                            level.removeBlock(pos, false);
                            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 3, 0.2, 0.15, 0.2, 0.03);
                            if (!playedSteam) {
                                playedSteam = true;
                                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.45F, 1.65F);
                            }
                        } else if ((st.is(Blocks.WATER) || st.is(BlockTags.ICE)) && !playedSteam) {
                            playedSteam = true;
                            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 12, 0.65, 0.3, 0.65, 0.06);
                            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.68F, 1.40F);
                        }
                    }

                    // D. 刀锋外缘刮到坚硬墙面/矿石：迸出真实金铁火星与清脆刮壁微鸣！
                    if (!playedSpark && dy >= 0 && distSq >= (radius * 0.55) * (radius * 0.55) && st.isSolidRender()) {
                        playedSpark = true;
                        double sx = pos.getX() + 0.5 - forward.x * 0.55;
                        double sy = pos.getY() + 0.5;
                        double sz = pos.getZ() + 0.5 - forward.z * 0.55;
                        level.sendParticles(ParticleTypes.LAVA, sx, sy, sz, heavy ? 7 : 4, 0.18, 0.18, 0.18, 0.08);
                        level.sendParticles(ParticleTypes.CRIT, sx, sy, sz, heavy ? 8 : 4, 0.22, 0.22, 0.22, 0.15);
                        level.playSound(null, sx, sy, sz, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.24F, 1.88F);
                    }
                }
            }
        }
    }

    private static boolean isCuttableFoliage(BlockState st) {
        return st.is(Blocks.COBWEB)
                || st.is(Blocks.SHORT_GRASS)
                || st.is(Blocks.TALL_GRASS)
                || st.is(Blocks.FERN)
                || st.is(Blocks.LARGE_FERN)
                || st.is(Blocks.DEAD_BUSH)
                || st.is(Blocks.VINE)
                || st.is(BlockTags.SMALL_FLOWERS);
    }

    /**
     * 水火相激化学反应（旷野之息化学引擎核心！）：
     * - 水之呼吸击中着火目标 -> 急冷熄火 + 滚滚蒸汽爆裂 + 额外急冷脆裂伤害 + 蒸汽致盲周围敌怪
     * - 日之呼吸击中潮湿/水中/雨中目标 -> 瞬间气化 + 超热蒸汽爆燃 + 额外烫伤与热浪上掀
     * 返回附加的蒸汽反应额外伤害（无反应返回 0）
     */
    public static float triggerWaterFireChemistry(
            ServerPlayer player, ServerLevel level, LivingEntity target, boolean sun
    ) {
        long now = level.getGameTime();
        double tx = target.getX(), ty = target.getY() + target.getBbHeight() * 0.55, tz = target.getZ();

        if (!sun) {
            boolean wasBurning = target.isOnFire();
            // 水之呼吸天然浸湿目标 5 秒，方便玩家按 X 切日呼顺势打出超热蒸汽爆燃！
            WET_UNTIL.put(target.getId(), now + 100L);
            if (wasBurning) {
                target.clearFire();
                emitSteamBurst(player, level, tx, ty, tz, false);
                return 6.5F;
            }
            return 0.0F;
        } else {
            boolean wasWet = target.isInWaterOrRain() || WET_UNTIL.getOrDefault(target.getId(), 0L) > now;
            if (wasWet) {
                WET_UNTIL.remove(target.getId());
                emitSteamBurst(player, level, tx, ty, tz, true);
                return 7.5F;
            }
            return 0.0F;
        }
    }

    private static void emitSteamBurst(ServerPlayer player, ServerLevel level, double x, double y, double z, boolean superheated) {
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 22, 1.05, 0.75, 1.05, 0.05);
        level.sendParticles(ParticleTypes.CLOUD, x, y, z, 26, 1.25, 0.85, 1.25, 0.09);
        if (superheated) {
            level.sendParticles(ParticleTypes.FLAME, x, y, z, 14, 0.85, 0.65, 0.85, 0.12);
        }
        level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.25F, superheated ? 0.85F : 1.05F);
        level.playSound(null, x, y, z, SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 0.95F, 1.25F);

        // 浓密高温蒸汽短暂干扰周围敌怪视野与步伐
        AABB steamBox = new AABB(x - 3.6, y - 1.5, z - 3.6, x + 3.6, y + 2.5, z + 3.6);
        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class, steamBox,
                e -> e.isAlive() && e != player)) {
            nearby.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 36, 0, false, false, false));
            nearby.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 32, 1, false, false, false));
            if (superheated) {
                nearby.push(0.0, 0.32, 0.0);
                BreathingManager.syncEntityVelocity(nearby);
            }
        }
    }

    /** 真实动能转化：奔跑 / 突进 / 俯冲中挥刀，速度越快斩击威力与冲击越猛（1.00x .. 1.38x）！ */
    public static float computeMomentumMultiplier(ServerPlayer player) {
        Vec3 v = player.getDeltaMovement();
        double speed = Math.hypot(v.x, v.z) + Math.max(0.0, -v.y) * 0.85;
        return Mth.clamp(1.0F + (float) Math.max(0.0, speed - 0.16) * 0.34F, 1.0F, 1.38F);
    }

    /** 斩中重甲 / 铁傀儡 / 举盾目标时的真实金铁交鸣与火星回馈！ */
    public static void playMaterialHitResonance(ServerLevel level, LivingEntity target, boolean sun) {
        boolean metallic = target instanceof IronGolem || target.getArmorValue() >= 8 || target.isBlocking();
        if (metallic) {
            double hx = target.getX(), hy = target.getY() + target.getBbHeight() * 0.62, hz = target.getZ();
            level.playSound(null, hx, hy, hz, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.42F, 1.68F);
            level.sendParticles(ParticleTypes.LAVA, hx, hy, hz, 8, 0.3, 0.3, 0.3, 0.12);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hx, hy, hz, 10, 0.35, 0.35, 0.35, 0.15);
        } else if (!sun && target.isInWaterOrRain()) {
            level.sendParticles(ParticleTypes.SPLASH, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    12, 0.45, 0.45, 0.45, 0.14);
        }
    }

    /** 水之呼吸高空水垫缓震直觉：下坠时挥出水呼剑型，水浪直接吃掉下落动能并免除摔伤！ */
    public static void tryWaterFallCushion(ServerPlayer player, ServerLevel level, BreathingManager.PlayerBreathState state) {
        if (player.fallDistance >= 2.8F || player.getDeltaMovement().y < -0.52) {
            player.resetFallDistance();
            state.fallImmuneTicks = Math.max(state.fallImmuneTicks, 26);
            Vec3 v = player.getDeltaMovement();
            if (v.y < -0.25) {
                player.setDeltaMovement(v.x, Math.max(v.y * 0.25, 0.18), v.z);
                BreathingManager.syncEntityVelocity(player);
            }
            level.sendParticles(ParticleTypes.SPLASH, player.getX(), player.getY() + 0.15, player.getZ(), 26, 1.1, 0.3, 1.1, 0.18);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.85F, 1.25F);
        }
    }

    /** 日之呼吸热力学：在烈焰落点/焦土坑生成真实向上的热空气上升气流（BotW Updraft！） */
    public static void spawnThermalUpdraft(
            ServerLevel level, double x, double y, double z, float radius, float height, int durationTicks
    ) {
        if (UPDRAFTS.size() >= 16) {
            UPDRAFTS.remove(0);
        }
        UPDRAFTS.add(new ThermalUpdraftZone(level.dimension(), x, y, z, radius, height, durationTicks));
    }

    /** 服务端每世界 tick 驱动热气流上升浮力与潮湿缓存清理 */
    public static void tickServerLevel(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 80 == 0 && !WET_UNTIL.isEmpty()) {
            WET_UNTIL.entrySet().removeIf(e -> e.getValue() <= now);
        }
        if (UPDRAFTS.isEmpty()) return;

        ResourceKey<Level> dim = level.dimension();
        for (Iterator<ThermalUpdraftZone> it = UPDRAFTS.iterator(); it.hasNext(); ) {
            ThermalUpdraftZone z = it.next();
            if (!z.dimension.equals(dim)) continue;
            z.ticksLeft--;
            if (z.ticksLeft <= 0) {
                it.remove();
                continue;
            }

            // 视觉：自然升腾的细小暖金余烬与轻烟，不用文字就能一眼看出热浪在往上托！
            if (z.ticksLeft % 3 == 0) {
                level.sendParticles(ParticleTypes.FLAME, z.x, z.y + 0.4, z.z, 3, z.radius * 0.55, 0.4, z.radius * 0.55, 0.08);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, z.x, z.y + 0.6, z.z, 2, z.radius * 0.45, 0.5, z.radius * 0.45, 0.04);
            }

            AABB box = new AABB(z.x - z.radius, z.y - 0.5, z.z - z.radius, z.x + z.radius, z.y + z.height, z.z + z.radius);
            for (LivingEntity ent : level.getEntitiesOfClass(LivingEntity.class, box, Entity::isAlive)) {
                double distH = Math.hypot(ent.getX() - z.x, ent.getZ() - z.z);
                if (distH > z.radius) continue;
                // 玩家/生物在火场起跳或滞空时，受到柔和真实的热气流托举（顺手就能接空中阳华突/烈日红镜！）
                if (!ent.onGround()) {
                    Vec3 vel = ent.getDeltaMovement();
                    if (vel.y < 0.52) {
                        double lift = ent instanceof ServerPlayer ? 0.145 : 0.11;
                        ent.setDeltaMovement(vel.x, Math.min(0.54, vel.y + lift), vel.z);
                        ent.resetFallDistance();
                        BreathingManager.syncEntityVelocity(ent);
                    }
                    if (ent instanceof ServerPlayer sp) {
                        BreathingManager.PlayerBreathState st = BreathingManager.getState(sp.getUUID());
                        st.fallImmuneTicks = Math.max(st.fallImmuneTicks, 18);
                    }
                }
            }
        }
    }

    /** 日之呼吸 / 赫刀斩杀动物掉落直接炙烤为熟肉（旷野之息烤肉直觉！） */
    public static void handleSunBladeCookDrops(LivingDropsEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) return;
        if (!BreathingManager.isHoldingWaterBlade(killer)) return;
        BreathingManager.PlayerBreathState state = BreathingManager.getState(killer.getUUID());
        if (state.style != BreathingStyle.SUN) return;

        for (ItemEntity drop : event.getDrops()) {
            ItemStack cooked = cookRawMeatStack(drop.getItem());
            if (cooked != null) {
                drop.setItem(cooked);
            }
        }
    }

    private static ItemStack cookRawMeatStack(ItemStack raw) {
        if (raw.isEmpty()) return null;
        Item target = null;
        if (raw.is(Items.BEEF)) target = Items.COOKED_BEEF;
        else if (raw.is(Items.PORKCHOP)) target = Items.COOKED_PORKCHOP;
        else if (raw.is(Items.CHICKEN)) target = Items.COOKED_CHICKEN;
        else if (raw.is(Items.MUTTON)) target = Items.COOKED_MUTTON;
        else if (raw.is(Items.RABBIT)) target = Items.COOKED_RABBIT;
        else if (raw.is(Items.COD)) target = Items.COOKED_COD;
        else if (raw.is(Items.SALMON)) target = Items.COOKED_SALMON;
        return target != null ? new ItemStack(target, raw.getCount()) : null;
    }

    public static void clearAll() {
        WET_UNTIL.clear();
        UPDRAFTS.clear();
    }

    private static final class ThermalUpdraftZone {
        final ResourceKey<Level> dimension;
        final double x, y, z;
        final float radius, height;
        int ticksLeft;

        ThermalUpdraftZone(ResourceKey<Level> dimension, double x, double y, double z, float radius, float height, int ticksLeft) {
            this.dimension = dimension;
            this.x = x; this.y = y; this.z = z;
            this.radius = radius; this.height = height;
            this.ticksLeft = ticksLeft;
        }
    }
}
