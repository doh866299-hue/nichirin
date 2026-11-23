package com.nichirin.breathing;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 服务端双流派呼吸法事件桥接：
 * 处理玩家呼吸循环、凪格挡弹反、幻日虹残影闪避反杀、龙冲锋霸体、赫刀灼烧增伤与击杀回气
 */
public final class BreathingServerEvents {

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BreathingManager.tickPlayer(player);
        }
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            BladeWorldPhysics.tickServerLevel(serverLevel);
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        BladeWorldPhysics.handleSunBladeCookDrops(event);
    }

    @SubscribeEvent
    public void onLivingHeal(LivingHealEvent event) {
        SunBreathingForms.handleScorchedHeal(event);
    }

    @SubscribeEvent
    public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();

        // 1. 玩家受击侧：空中免摔伤 + 水·凪绝对格挡 + 日·幻日虹残影闪避 + 龙冲锋 75% 霸体
        if (victim instanceof ServerPlayer player) {
            BreathingManager.PlayerBreathState state = BreathingManager.getState(player.getUUID());
            if (event.getSource().is(DamageTypeTags.IS_FALL) && state.fallImmuneTicks > 0) {
                event.setCanceled(true);
                return;
            }
            if (!event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                if (state.instinctCounterWindowTicks > 0) {
                    event.setCanceled(true);
                    return;
                }
                if (state.deadCalmTicks > 0 && BreathingManager.tryDeadCalmParry(player, event.getSource().getEntity())) {
                    event.setCanceled(true);
                    return;
                }
                if (state.fakeRainbowTicks > 0 && BreathingManager.tryFakeRainbowDodge(player, event.getSource().getEntity())) {
                    event.setCanceled(true);
                    return;
                }
                if (state.dragonRushTicks > 0) {
                    event.setAmount(event.getAmount() * 0.25F);
                }
            }
        }

        // 2. 玩家攻击侧：平A接入「隙之线斩断 + 赫刀全集中 + 命中顿刀反馈」（呼吸法剑型已由 applyNichirinStrike 统一结算）
        if (event.getSource().getDirectEntity() instanceof ServerPlayer attacker
                && BreathingManager.isHoldingWaterBlade(attacker)
                && victim != attacker) {
            BreathingManager.handleVanillaBladeHit(attacker, victim, event);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer killer
                && BreathingManager.isHoldingWaterBlade(killer)) {
            BreathingManager.PlayerBreathState state = BreathingManager.getState(killer.getUUID());
            state.breath = Math.min(BreathingManager.MAX_BREATH, state.breath + 18.0F);
            state.dirty = true;
            BreathingManager.syncPlayer(killer, state);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BreathingManager.PlayerBreathState state = BreathingManager.getState(player.getUUID());
            state.breath = BreathingManager.MAX_BREATH;
            state.dirty = true;
            BreathingManager.updateHeldBladeVisual(player, state);
            BreathingManager.syncPlayer(player, state);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BreathingManager.PlayerBreathState state = BreathingManager.getState(player.getUUID());
            state.breath = BreathingManager.MAX_BREATH;
            state.deadCalmTicks = 0;
            state.fakeRainbowTicks = 0;
            state.dragonRushTicks = 0;
            state.instinctCounterWindowTicks = 0;
            state.serverHitstopTicks = 0.0F;
            state.sunAfterimagePos = null;
            state.sunAfterimageTicks = 0;
            state.sunBurstBusyTicks = 0;
            SunBreathingForms.resetSunCycle(state);
            state.exposedTargets.clear();
            state.threadCooldowns.clear();
            state.frozenTargets.clear();
            state.activeTasks.clear();
            BreathingManager.updateHazeSpeed(player, false);
            BreathingManager.setTotalConcentration(player, state, false);
            BreathingManager.syncPlayer(player, state);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BreathingManager.removePlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BreathingManager.clearAll();
        SunBreathingForms.clearAll();
        BladeWorldPhysics.clearAll();
    }
}
