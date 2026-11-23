package com.nichirin.client.vfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.nichirin.NichirinMod;
import com.nichirin.client.ClientBreathState;
import com.nichirin.client.anim.SwordChoreography;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 鬼灭之刃 · 浮世绘赛璐璐木版画 3D 水之呼吸特效渲染器
 * <p>
 * 核心机制：
 * - 常态实时刀光带（renderLiveBladeTrail）：直接依据日轮刀真实的 3D 刀柄/刀尖运动轨迹实时拉出
 *   不透明浮世绘条纹水浪带，且招式只驱动人/刀动作，水浪形态完全由刀锋划出！
 * - 发射型奥义（拾之型·生生流转）：26 节青海波龙鳞水龙紧缠冲锋中的玩家与日轮刀同步推进！
 */
public final class WaterVfxRenderer {
    private static final ContextKey<VfxSnapshot> VFX_STATE_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "water_vfx_snapshot"));

    private static final Identifier TEX_WAVE_RIBBON =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/ukiyo_wave_ribbon.png");
    private static final Identifier TEX_FOAM_ATLAS =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/ukiyo_foam_atlas.png");
    private static final Identifier TEX_DRAGON_SCALES =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/ukiyo_dragon_scales.png");
    private static final Identifier TEX_CALM_RIPPLE =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/ukiyo_calm_ripple.png");

    // 日之呼吸 · 火之神神乐 浮世绘烈焰贴图
    private static final Identifier TEX_SUN_RIBBON =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/sun_flame_ribbon.png");
    private static final Identifier TEX_SUN_EMBERS =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/sun_ember_atlas.png");
    private static final Identifier TEX_SUN_DRAGON_SCALES =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/sun_dragon_scales.png");
    private static final Identifier TEX_SUN_HALO =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/sun_halo_sigil.png");
    // 日之呼吸奥义终点：焦土熔岩裂地与黄金日轮神龙图腾（致敬孙策龙限大招撞击）
    private static final Identifier TEX_SUN_CRATER_TOTEM =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/sun_crater_totem.png");
    // 电影级柔和体积辉光辅助贴图（叠在浮世绘实体层外围，打造真体积光晕与热浪辉光！）
    private static final Identifier TEX_GLOW_RIBBON =
            Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "textures/vfx/breath_glow_ribbon.png");

    public static final RenderPipeline UKIYO_CUTOUT_PIPELINE =
            RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "pipeline/ukiyo_cutout"))
                    .withShaderDefine("ALPHA_CUTOUT", 0.42F)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .withCull(false)
                    .build();

    public static final RenderPipeline UKIYO_TRANSLUCENT_PIPELINE =
            RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "pipeline/ukiyo_translucent"))
                    .withShaderDefine("ALPHA_CUTOUT", 0.03F)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .withCull(false)
                    .build();

    public static final OitPipelineSet UKIYO_TRANSLUCENT_OIT =
            OitPipelineSet.builder(
                    Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "ukiyo_translucent"),
                    RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET, RenderPipelines.EYES_SNIPPET).withCull(false)
            ).build();

    public static final RenderPipeline BREATH_GLOW_PIPELINE =
            RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "pipeline/breath_glow"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .withCull(false)
                    .build();

    public static final OitPipelineSet BREATH_GLOW_OIT =
            OitPipelineSet.builder(
                    Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "breath_glow"),
                    RenderPipeline.builder(RenderPipelines.OIT_ENTITY_SNIPPET, RenderPipelines.EYES_SNIPPET)
                            .withShaderDefine("OIT_ADDITIVE")
                            .withCull(false)
            ).build();

    private static final RenderType RIBBON_CUTOUT_TYPE = RenderType.create(
            "nichirin_ukiyo_ribbon",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_WAVE_RIBBON,
                            () -> RenderSystem.getSamplerCache().getSampler(
                                    AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false))
                    .createRenderSetup()
    );

    private static final RenderType FOAM_CUTOUT_TYPE = RenderType.create(
            "nichirin_ukiyo_foam",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_FOAM_ATLAS,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .createRenderSetup()
    );

    private static final RenderType DRAGON_SCALES_TYPE = RenderType.create(
            "nichirin_ukiyo_dragon_scales",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_DRAGON_SCALES,
                            () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                    .createRenderSetup()
    );

    private static final RenderType CALM_MIRROR_TYPE = RenderType.create(
            "nichirin_ukiyo_calm_mirror",
            RenderSetup.builder(UKIYO_TRANSLUCENT_PIPELINE)
                    .setOitPipelines(UKIYO_TRANSLUCENT_OIT)
                    .withTexture("Sampler0", TEX_CALM_RIPPLE,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .sortOnUpload()
                    .createRenderSetup()
    );

    private static final RenderType SUN_RIBBON_CUTOUT_TYPE = RenderType.create(
            "nichirin_sun_flame_ribbon",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_SUN_RIBBON,
                            () -> RenderSystem.getSamplerCache().getSampler(
                                    AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false))
                    .createRenderSetup()
    );

    private static final RenderType SUN_EMBER_CUTOUT_TYPE = RenderType.create(
            "nichirin_sun_ember_atlas",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_SUN_EMBERS,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .createRenderSetup()
    );

    private static final RenderType SUN_DRAGON_SCALES_TYPE = RenderType.create(
            "nichirin_sun_dragon_scales",
            RenderSetup.builder(UKIYO_CUTOUT_PIPELINE)
                    .withTexture("Sampler0", TEX_SUN_DRAGON_SCALES,
                            () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                    .createRenderSetup()
    );

    private static final RenderType SUN_HALO_MIRROR_TYPE = RenderType.create(
            "nichirin_sun_halo_mirror",
            RenderSetup.builder(UKIYO_TRANSLUCENT_PIPELINE)
                    .setOitPipelines(UKIYO_TRANSLUCENT_OIT)
                    .withTexture("Sampler0", TEX_SUN_HALO,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .sortOnUpload()
                    .createRenderSetup()
    );

    private static final RenderType SUN_CRATER_TOTEM_TYPE = RenderType.create(
            "nichirin_sun_crater_totem",
            RenderSetup.builder(UKIYO_TRANSLUCENT_PIPELINE)
                    .setOitPipelines(UKIYO_TRANSLUCENT_OIT)
                    .withTexture("Sampler0", TEX_SUN_CRATER_TOTEM,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .sortOnUpload()
                    .createRenderSetup()
    );

    private static final RenderType GLOW_RIBBON_TYPE = RenderType.create(
            "nichirin_breath_glow_ribbon",
            RenderSetup.builder(BREATH_GLOW_PIPELINE)
                    .setOitPipelines(BREATH_GLOW_OIT)
                    .withTexture("Sampler0", TEX_GLOW_RIBBON,
                            () -> RenderSystem.getSamplerCache().getSampler(
                                    AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false))
                    .sortOnUpload()
                    .createRenderSetup()
    );

    private WaterVfxRenderer() {}

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(UKIYO_CUTOUT_PIPELINE);
        event.registerPipeline(UKIYO_TRANSLUCENT_PIPELINE);
        event.registerOitPipelineSet(UKIYO_TRANSLUCENT_OIT);
        event.registerPipeline(BREATH_GLOW_PIPELINE);
        event.registerOitPipelineSet(BREATH_GLOW_OIT);
    }

    /**
     * 阶段一（Extract）：提取实时平滑刀光带、人刀合一真龙、浮世绘精灵、高速光针与冲击波快照
     */
    public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        float pt = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // 先在真实渲染帧推进原生 60/144/240Hz 连续刀光带，与摄像机和玩家 100% 锁定同一精确时钟！
        double renderTime = WaterVfxManager.updateLiveBladeTrailsAtRenderFrame(level, pt);

        if (WaterVfxManager.BLADE_TRAILS.isEmpty()
                && WaterVfxManager.DEBRIS.isEmpty()
                && WaterVfxManager.UPHEAVALS.isEmpty()
                && WaterVfxManager.ORBITING_BLOCKS.isEmpty()
                && WaterVfxManager.WHIRLPOOLS.isEmpty()
                && WaterVfxManager.WATERFALLS.isEmpty()
                && WaterVfxManager.DRAGONS.isEmpty()
                && WaterVfxManager.SUN_FINALES.isEmpty()
                && WaterVfxManager.DEAD_CALMS.isEmpty()
                && WaterVfxManager.RIFT_SLASHES.isEmpty()
                && WaterVfxManager.CHARGE_AURAS.isEmpty()
                && WaterVfxManager.FOAM_SPRITES.isEmpty()
                && WaterVfxManager.STREAKS.isEmpty()
                && WaterVfxManager.THREAD_SNAPS.isEmpty()
                && WaterVfxManager.RIPPLE_RINGS.isEmpty()
                && WaterVfxManager.getOpeningThreadTargetId() <= 0) {
            return;
        }

        // 1. 直接提取不可变高帧率刀光节点快照（零重算、零回折、零跳帧抖动，携带奥义冲刺尾流标记！）
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer localPlayer = mc.player;
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        int localId = localPlayer != null ? localPlayer.getId() : -1;
        Vec3 camForward = localPlayer != null ? localPlayer.getViewVector(pt).normalize() : new Vec3(0.0, 0.0, 1.0);

        List<BladeTrailSnap> bladeTrails = new ArrayList<>(WaterVfxManager.BLADE_TRAILS.size());
        for (WaterVfxManager.LiveBladeTrail trail : WaterVfxManager.BLADE_TRAILS.values()) {
            int sz = trail.points.size();
            if (sz < 2) continue;
            List<Vec3> bases = new ArrayList<>(sz);
            List<Vec3> tips = new ArrayList<>(sz);
            List<Vec3> normals = new ArrayList<>(sz);
            List<Float> intensities = new ArrayList<>(sz);
            List<Float> arcDists = new ArrayList<>(sz);
            List<Double> birthTimes = new ArrayList<>(sz);
            List<Boolean> rushFlags = new ArrayList<>(sz);
            for (WaterVfxManager.TrailNode node : trail.points) {
                bases.add(node.base);
                tips.add(node.tip);
                normals.add(node.normal);
                intensities.add(node.intensity);
                arcDists.add(node.arcDist);
                birthTimes.add(node.birthTime);
                rushFlags.add(node.rushNode);
            }
            bladeTrails.add(new BladeTrailSnap(bases, tips, normals, intensities, arcDists, birthTimes, rushFlags,
                    renderTime, trail.sunStyle, trail.entityId == localId));
        }

        // 2. 提取真实方块碎块 / 掀翻 / 反重力旋转
        List<BlockInstance> blocks = new ArrayList<>(
                WaterVfxManager.DEBRIS.size() + WaterVfxManager.UPHEAVALS.size() + WaterVfxManager.ORBITING_BLOCKS.size()
        );
        for (WaterVfxManager.FlyingBlockDebris d : WaterVfxManager.DEBRIS) {
            if (d.blockState.getRenderShape() != RenderShape.MODEL) continue;
            double rx = Mth.lerp(pt, d.prevX, d.x), ry = Mth.lerp(pt, d.prevY, d.y), rz = Mth.lerp(pt, d.prevZ, d.z);
            float rem = d.maxAge - (d.age + pt);
            float scale = d.baseScale * (rem < 6.0F ? Mth.clamp(rem / 6.0F, 0.0F, 1.0F) : 1.0F);
            if (scale <= 0.02F) continue;
            MovingBlockRenderState mbs = buildMovingBlockState(level, d.blockState, d.seedPos, BlockPos.containing(rx, ry, rz));
            blocks.add(new BlockInstance(
                    mbs, rx, ry, rz,
                    Mth.lerp(pt, d.prevRotX, d.rotX), Mth.lerp(pt, d.prevRotY, d.rotY), Mth.lerp(pt, d.prevRotZ, d.rotZ),
                    scale, false, 0.0F, 0.0F
            ));
        }
        for (WaterVfxManager.GroundUpheavalBlock u : WaterVfxManager.UPHEAVALS) {
            if (u.blockState.getRenderShape() != RenderShape.MODEL) continue;
            float elev = u.getElevationFactor(pt);
            if (elev <= 0.005F) continue;
            double ry = u.baseY + 0.02 + u.liftHeight * elev;
            float yawOut = (float) (Mth.atan2(u.dirX, u.dirZ) * Mth.RAD_TO_DEG);
            MovingBlockRenderState mbs = buildMovingBlockState(level, u.blockState, u.seedPos, BlockPos.containing(u.baseX, ry + 0.5, u.baseZ));
            blocks.add(new BlockInstance(mbs, u.baseX, ry, u.baseZ, 0, 0, 0, 1.006F, true, yawOut, -u.maxTiltDeg * elev));
        }
        for (WaterVfxManager.OrbitingBlockDebris o : WaterVfxManager.ORBITING_BLOCKS) {
            if (o.blockState.getRenderShape() != RenderShape.MODEL) continue;
            double rx = Mth.lerp(pt, o.prevX, o.x), ry = Mth.lerp(pt, o.prevY, o.y), rz = Mth.lerp(pt, o.prevZ, o.z);
            MovingBlockRenderState mbs = buildMovingBlockState(level, o.blockState, o.seedPos, BlockPos.containing(rx, ry, rz));
            blocks.add(new BlockInstance(
                    mbs, rx, ry, rz,
                    Mth.lerp(pt, o.prevRotX, o.rotX), Mth.lerp(pt, o.prevRotY, o.rotY), Mth.lerp(pt, o.prevRotZ, o.rotZ),
                    o.baseScale, false, 0.0F, 0.0F
            ));
        }

        // 3. 提取伴生 3D 水龙/日晕火龙、漩涡、泷壶/阳华突、凪/幻日虹、蓄力阵（全部做 partialTick 丝滑插值，零 20Hz 差频抖动！）
        List<WhirlpoolSnap> whirlpools = new ArrayList<>(WaterVfxManager.WHIRLPOOLS.size());
        for (WaterVfxManager.WaterWhirlpoolVfx wp : WaterVfxManager.WHIRLPOOLS) {
            whirlpools.add(new WhirlpoolSnap(
                    Mth.lerp(pt, wp.prevX, wp.x), Mth.lerp(pt, wp.prevY, wp.y), Mth.lerp(pt, wp.prevZ, wp.z),
                    wp.radius, wp.height, Mth.clamp((wp.age + pt) / wp.maxAge, 0.0F, 1.0F), wp.age + pt, wp.boosted, wp.sunStyle));
        }

        List<WaterfallSnap> waterfalls = new ArrayList<>(WaterVfxManager.WATERFALLS.size());
        for (WaterVfxManager.WaterfallPillarVfx wf : WaterVfxManager.WATERFALLS) {
            float eruptAge = (wf.age + pt) - wf.delayTicks;
            if (eruptAge <= 0.0F) continue;
            float effDur = Math.max(8.0F, wf.maxAge - wf.delayTicks);
            waterfalls.add(new WaterfallSnap(wf.x, wf.y, wf.z, wf.radius, wf.height,
                    Mth.clamp(eruptAge / effDur, 0.0F, 1.0F), eruptAge, wf.totalConc, wf.sunStyle));
        }

        List<DragonSnap> dragons = new ArrayList<>(WaterVfxManager.DRAGONS.size() + WaterVfxManager.SUN_FINALES.size());
        for (WaterVfxManager.WaterDragonVfx dr : WaterVfxManager.DRAGONS) {
            Vec3[] spine = dr.buildSmoothSpine(renderTime);
            float lifeNorm = dr.getEffectiveLifeNorm(renderTime);
            if (spine.length >= 2 && lifeNorm < 0.99F) {
                dragons.add(new DragonSnap(spine, dr.tier, lifeNorm,
                        (float) (renderTime - dr.startTime), dr.totalConc, dr.sunStyle, dr.getStageScale(), dr.entityId == localId));
            }
        }

        // 提取思路 2「人刀龙一体重劈落地 · 外圈 480° 修长东方神龙大回环」快照（144Hz 零抖动！）
        List<SunFinaleSnap> sunFinales = new ArrayList<>(WaterVfxManager.SUN_FINALES.size());
        for (WaterVfxManager.SunDragonFinaleVfx sf : WaterVfxManager.SUN_FINALES) {
            float ageTicks = sf.age + pt;
            float progress = Mth.clamp(ageTicks / sf.maxAge, 0.0F, 1.0F);
            sunFinales.add(new SunFinaleSnap(
                    sf.x, sf.y, sf.z, sf.yawDeg, sf.radius, sf.tier, progress, ageTicks, sf.maxAge, sf.totalConc, sf.sunStyle
            ));
            Vec3[] ringSpine = WaterVfxManager.SunDragonFinaleVfx.buildRingDragonSpine(
                    sf.x, sf.y, sf.z, sf.yawDeg, sf.radius, ageTicks
            );
            if (ringSpine.length >= 2) {
                float dLife = Mth.clamp(
                        (ageTicks - WaterVfxManager.SunDragonFinaleVfx.DRAGON_START_TICK)
                                / (WaterVfxManager.SunDragonFinaleVfx.DRAGON_END_TICK - WaterVfxManager.SunDragonFinaleVfx.DRAGON_START_TICK),
                        0.0F, 1.0F
                );
                float popScale = Mth.clamp(ageTicks / 1.5F, 0.62F, 1.0F);
                // 随生生流转回旋圈数（1..4）实时递进龙体威势（1圈灵巧 -> 4圈极势巨龙！）
                float stageMult = 0.78F + Mth.clamp(sf.tier, 1, 4) * 0.10F;
                dragons.add(new DragonSnap(ringSpine, sf.tier, dLife, ageTicks, sf.totalConc, sf.sunStyle, stageMult * popScale, false));
            }
        }

        List<DeadCalmSnap> deadCalms = new ArrayList<>(WaterVfxManager.DEAD_CALMS.size());
        for (WaterVfxManager.DeadCalmMirrorVfx dc : WaterVfxManager.DEAD_CALMS) {
            deadCalms.add(new DeadCalmSnap(
                    Mth.lerp(pt, dc.prevX, dc.x), Mth.lerp(pt, dc.prevY, dc.y), Mth.lerp(pt, dc.prevZ, dc.z),
                    dc.radius, Mth.clamp((dc.age + pt) / dc.maxAge, 0.0F, 1.0F), dc.age + pt, dc.totalConc, dc.sunStyle));
        }

        List<RiftSlashSnap> rifts = new ArrayList<>(WaterVfxManager.RIFT_SLASHES.size());
        for (WaterVfxManager.WaterRiftSlashVfx r : WaterVfxManager.RIFT_SLASHES) {
            rifts.add(new RiftSlashSnap(r.x, r.y, r.z, r.yawDeg, r.pitchDeg, r.length,
                    Mth.clamp((r.age + pt) / r.maxAge, 0.0F, 1.0F), r.heavy, r.sunStyle));
        }

        List<ChargeAuraSnap> auras = new ArrayList<>(WaterVfxManager.CHARGE_AURAS.size());
        for (WaterVfxManager.ChargeAuraVfx c : WaterVfxManager.CHARGE_AURAS) {
            auras.add(new ChargeAuraSnap(
                    Mth.lerp(pt, c.prevX, c.x), Mth.lerp(pt, c.prevY, c.y), Mth.lerp(pt, c.prevZ, c.z),
                    c.radius, c.mode, Mth.clamp((c.age + pt) / c.maxAge, 0.0F, 1.0F), c.age + pt, c.totalConc, c.sunStyle));
        }

        List<FoamSpriteSnap> foams = new ArrayList<>(WaterVfxManager.FOAM_SPRITES.size());
        for (WaterVfxManager.UkiyoFoamSprite s : WaterVfxManager.FOAM_SPRITES) {
            foams.add(new FoamSpriteSnap(
                    Mth.lerp(pt, s.prevX, s.x), Mth.lerp(pt, s.prevY, s.y), Mth.lerp(pt, s.prevZ, s.z),
                    s.tile, s.baseScale, Mth.lerp(pt, s.prevRollDeg, s.rollDeg),
                    Mth.clamp((s.age + pt) / s.maxAge, 0.0F, 1.0F), s.sunStyle
            ));
        }

        List<StreakSnap> streaks = new ArrayList<>(WaterVfxManager.STREAKS.size());
        for (WaterVfxManager.VelocityStreakVfx st : WaterVfxManager.STREAKS) {
            streaks.add(new StreakSnap(
                    Mth.lerp(pt, st.prevX, st.x), Mth.lerp(pt, st.prevY, st.y), Mth.lerp(pt, st.prevZ, st.z),
                    st.vx, st.vy, st.vz, st.lengthScale, st.widthScale,
                    Mth.clamp((st.age + pt) / st.maxAge, 0.0F, 1.0F), st.sunStyle, st.hotWhite
            ));
        }

        // 4. 提取鬼灭核心战斗直觉：活体 3D「隙之线」与绷断丝线残芒
        OpeningThreadSnap openingThread = null;
        int threadTargetId = WaterVfxManager.getOpeningThreadTargetId();
        float threadStrength = WaterVfxManager.getOpeningThreadStrength(pt);
        if (threadTargetId > 0 && threadStrength > 0.02F && localPlayer != null && localPlayer.isAlive()) {
            Entity target = level.getEntity(threadTargetId);
            if (target != null && target.isAlive()) {
                Vec3 neckPos = new Vec3(
                        Mth.lerp(pt, target.xo, target.getX()),
                        Mth.lerp(pt, target.yo, target.getY()) + target.getBbHeight() * 0.78,
                        Mth.lerp(pt, target.zo, target.getZ())
                );
                Vec3 pPos = new Vec3(
                        Mth.lerp(pt, localPlayer.xo, localPlayer.getX()),
                        Mth.lerp(pt, localPlayer.yo, localPlayer.getY()) + 1.08,
                        Mth.lerp(pt, localPlayer.zo, localPlayer.getZ())
                );
                Vec3 pFwdHoriz = Vec3.directionFromRotation(0.0F, Mth.lerp(pt, localPlayer.yRotO, localPlayer.getYRot())).normalize();
                Vec3 pRight = new Vec3(-pFwdHoriz.z, 0.0, pFwdHoriz.x).normalize();
                Vec3 tpAnchor = pPos.add(pRight.scale(0.42)).add(pFwdHoriz.scale(0.68));
                double dist = pPos.distanceTo(neckPos);
                float tautness = Mth.clamp(1.0F - (float) ((dist - 4.2) / 7.8), 0.0F, 1.0F);
                openingThread = new OpeningThreadSnap(
                        tpAnchor, neckPos, camForward, threadStrength, tautness, (float) renderTime,
                        firstPerson,
                        WaterVfxManager.isOpeningThreadForcedHighlight(),
                        ClientBreathState.isSunStyle()
                );
            }
        }

        List<ThreadSnapData> threadSnaps = new ArrayList<>(WaterVfxManager.THREAD_SNAPS.size());
        for (WaterVfxManager.ThreadSnapVfx ts : WaterVfxManager.THREAD_SNAPS) {
            threadSnaps.add(new ThreadSnapData(
                    ts.start, ts.end, Mth.clamp((ts.age + pt) / ts.maxAge, 0.0F, 1.0F), ts.sunStyle
            ));
        }

        List<RippleRingSnap> ripples = new ArrayList<>(WaterVfxManager.RIPPLE_RINGS.size());
        for (WaterVfxManager.RippleRingVfx rr : WaterVfxManager.RIPPLE_RINGS) {
            ripples.add(new RippleRingSnap(
                    rr.x, rr.y, rr.z, rr.yawDeg, rr.pitchDeg, rr.maxRadius, rr.mode,
                    Mth.clamp((rr.age + pt) / rr.maxAge, 0.0F, 1.0F), rr.age + pt, rr.sunStyle
            ));
        }

        event.getRenderState().setRenderData(VFX_STATE_KEY, new VfxSnapshot(
                bladeTrails, blocks, whirlpools, waterfalls, dragons, sunFinales, deadCalms, rifts, auras, foams, streaks,
                openingThread, threadSnaps, ripples, firstPerson, camForward
        ));
    }

    /**
     * 阶段二（Submit）：分批提交实体碎块、实时刀光浮世绘水带、青海波水龙、黑边白沫图集与静止镜面
     */
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        LevelRenderState levelState = event.getLevelRenderState();
        VfxSnapshot snap = levelState.getRenderData(VFX_STATE_KEY);
        if (snap == null) return;

        Vec3 cam = levelState.cameraRenderState.pos;
        double camX = cam.x, camY = cam.y, camZ = cam.z;
        Quaternionf camOrient = levelState.cameraRenderState.orientation;
        Vector3f camRight = new Vector3f(1.0F, 0.0F, 0.0F).rotate(camOrient);
        Vector3f camUp = new Vector3f(0.0F, 1.0F, 0.0F).rotate(camOrient);

        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        // ====== Pass 1: 真实地形 3D 方块碎块 / 掀翻 / 反重力旋转 ======
        for (BlockInstance b : snap.blocks) {
            poseStack.pushPose();
            poseStack.translate(b.x - camX, b.y - camY, b.z - camZ);
            if (b.isUpheaval) {
                poseStack.rotateDegrees(Axis.YP, b.upheavalYaw);
                poseStack.rotateDegrees(Axis.XP, b.upheavalTilt);
                poseStack.scale(b.scale, b.scale, b.scale);
                poseStack.translate(-0.5, 0.0, -0.5);
            } else {
                poseStack.rotateDegrees(Axis.YP, b.rotY);
                poseStack.rotateDegrees(Axis.XP, b.rotX);
                poseStack.rotateDegrees(Axis.ZP, b.rotZ);
                poseStack.scale(b.scale, b.scale, b.scale);
                poseStack.translate(-0.5, -0.5, -0.5);
            }
            collector.submitMovingBlock(poseStack, b.state, 0);
            poseStack.popPose();
        }

        // ====== Pass 2A: 水之呼吸 浮世绘水浪带（刀先破空 -> 尾流澎湃展开 / 漩涡 / 泷壶 / 水龙双翼 / 终结贴地浪环与外圈龙息带 / 蓄力） ======
        boolean fp = snap.firstPerson;
        Vec3 camFwd = snap.camForward;
        boolean hasWaterRibbons = snap.bladeTrails.stream().anyMatch(t -> !t.sunStyle)
                || snap.whirlpools.stream().anyMatch(w -> !w.sunStyle)
                || snap.waterfalls.stream().anyMatch(w -> !w.sunStyle)
                || snap.dragons.stream().anyMatch(d -> !d.sunStyle)
                || snap.sunFinales.stream().anyMatch(s -> !s.sunStyle)
                || snap.auras.stream().anyMatch(a -> !a.sunStyle);
        if (hasWaterRibbons) {
            collector.submitCustomGeometry(poseStack, RIBBON_CUTOUT_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (BladeTrailSnap tr : snap.bladeTrails) if (!tr.sunStyle) renderLiveBladeTrail(mat, vc, tr, camX, camY, camZ, fp, camFwd);
                for (WhirlpoolSnap wp : snap.whirlpools) if (!wp.sunStyle) renderUkiyoWhirlpool(mat, vc, wp, camX, camY, camZ);
                for (WaterfallSnap wf : snap.waterfalls) if (!wf.sunStyle) renderUkiyoWaterfall(mat, vc, wf, camX, camY, camZ);
                for (DragonSnap dr : snap.dragons) if (!dr.sunStyle) renderDragonFlankStreams(mat, vc, dr, camX, camY, camZ, fp, camFwd);
                for (SunFinaleSnap sf : snap.sunFinales) if (!sf.sunStyle) renderSunFinaleEruption(mat, vc, sf, camX, camY, camZ);
                for (ChargeAuraSnap c : snap.auras) if (!c.sunStyle) renderChargeRibbons(mat, vc, c, camX, camY, camZ);
            });
        }

        // ====== Pass 2B: 日之呼吸·火之神神乐 黑墨红金烈焰带（刀光 / 灼骨炎阳 / 阳华突 / 日晕龙翼 / 终结贴地火浪环与外圈龙息带 / 幻日虹残影炎柱） ======
        boolean hasSunRibbons = snap.bladeTrails.stream().anyMatch(t -> t.sunStyle)
                || snap.whirlpools.stream().anyMatch(w -> w.sunStyle)
                || snap.waterfalls.stream().anyMatch(w -> w.sunStyle)
                || snap.dragons.stream().anyMatch(d -> d.sunStyle)
                || snap.sunFinales.stream().anyMatch(s -> s.sunStyle)
                || snap.auras.stream().anyMatch(a -> a.sunStyle)
                || snap.deadCalms.stream().anyMatch(d -> d.sunStyle);
        if (hasSunRibbons) {
            collector.submitCustomGeometry(poseStack, SUN_RIBBON_CUTOUT_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (BladeTrailSnap tr : snap.bladeTrails) if (tr.sunStyle) renderLiveBladeTrail(mat, vc, tr, camX, camY, camZ, fp, camFwd);
                for (WhirlpoolSnap wp : snap.whirlpools) if (wp.sunStyle) renderUkiyoWhirlpool(mat, vc, wp, camX, camY, camZ);
                for (WaterfallSnap wf : snap.waterfalls) if (wf.sunStyle) renderUkiyoWaterfall(mat, vc, wf, camX, camY, camZ);
                for (DragonSnap dr : snap.dragons) if (dr.sunStyle) renderDragonFlankStreams(mat, vc, dr, camX, camY, camZ, fp, camFwd);
                for (SunFinaleSnap sf : snap.sunFinales) if (sf.sunStyle) renderSunFinaleEruption(mat, vc, sf, camX, camY, camZ);
                for (ChargeAuraSnap c : snap.auras) if (c.sunStyle) renderChargeRibbons(mat, vc, c, camX, camY, camZ);
                for (DeadCalmSnap dc : snap.deadCalms) if (dc.sunStyle) renderSunMiragePillar(mat, vc, dc, camX, camY, camZ);
            });
        }

        // ====== Pass 3A/3B: 3D 浮世绘龙躯（青海波水龙 / 赤金日晕火龙） ======
        if (snap.dragons.stream().anyMatch(d -> !d.sunStyle)) {
            collector.submitCustomGeometry(poseStack, DRAGON_SCALES_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (DragonSnap dr : snap.dragons) if (!dr.sunStyle) renderDragonScaledBody(mat, vc, dr, camX, camY, camZ, fp, camFwd);
            });
        }
        if (snap.dragons.stream().anyMatch(d -> d.sunStyle)) {
            collector.submitCustomGeometry(poseStack, SUN_DRAGON_SCALES_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (DragonSnap dr : snap.dragons) if (dr.sunStyle) renderDragonScaledBody(mat, vc, dr, camX, camY, camZ, fp, camFwd);
            });
        }

        // ====== Pass 4A/4B: 黑边公告板精灵图集（水·浪爪白沫 / 日·火舌炽焰火星，第一人称自动保护中心准星！） ======
        boolean hasWaterFoams = snap.foams.stream().anyMatch(f -> !f.sunStyle)
                || snap.dragons.stream().anyMatch(d -> !d.sunStyle);
        if (hasWaterFoams) {
            collector.submitCustomGeometry(poseStack, FOAM_CUTOUT_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (FoamSpriteSnap s : snap.foams) if (!s.sunStyle) {
                    renderFoamBillboard(mat, vc, s, camX, camY, camZ, camRight, camUp, fp, camFwd);
                }
                for (DragonSnap dr : snap.dragons) if (!dr.sunStyle) {
                    renderDragonClawMane(mat, vc, dr, camX, camY, camZ, camRight, camUp, fp, camFwd);
                }
            });
        }
        boolean hasSunEmbers = snap.foams.stream().anyMatch(f -> f.sunStyle)
                || snap.dragons.stream().anyMatch(d -> d.sunStyle);
        if (hasSunEmbers) {
            collector.submitCustomGeometry(poseStack, SUN_EMBER_CUTOUT_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (FoamSpriteSnap s : snap.foams) if (s.sunStyle) {
                    renderFoamBillboard(mat, vc, s, camX, camY, camZ, camRight, camUp, fp, camFwd);
                }
                for (DragonSnap dr : snap.dragons) if (dr.sunStyle) {
                    renderDragonClawMane(mat, vc, dr, camX, camY, camZ, camRight, camUp, fp, camFwd);
                }
            });
        }

        // ====== Pass 5A/5B: 地面水镜、同心水滴波纹冲击环与领域阵 ======
        boolean hasWaterDiscs = snap.deadCalms.stream().anyMatch(d -> !d.sunStyle)
                || snap.auras.stream().anyMatch(a -> !a.sunStyle)
                || snap.sunFinales.stream().anyMatch(s -> !s.sunStyle)
                || snap.ripples.stream().anyMatch(r -> !r.sunStyle);
        if (hasWaterDiscs) {
            collector.submitCustomGeometry(poseStack, CALM_MIRROR_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (DeadCalmSnap dc : snap.deadCalms) if (!dc.sunStyle) renderDeadCalmMirror(mat, vc, dc, camX, camY, camZ);
                for (ChargeAuraSnap c : snap.auras) if (!c.sunStyle) renderChargeGroundDisc(mat, vc, c, camX, camY, camZ);
                for (SunFinaleSnap sf : snap.sunFinales) if (!sf.sunStyle) renderWaterFinaleGroundRing(mat, vc, sf, camX, camY, camZ);
                for (RippleRingSnap rr : snap.ripples) if (!rr.sunStyle) renderRippleRing(mat, vc, rr, camX, camY, camZ);
            });
        }
        // ====== Pass 5C: 日之呼吸奥义终点 焦土熔岩裂地与神龙图腾（底层紧贴地表，持续烙印余烬！） ======
        if (snap.sunFinales.stream().anyMatch(s -> s.sunStyle)) {
            collector.submitCustomGeometry(poseStack, SUN_CRATER_TOTEM_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (SunFinaleSnap sf : snap.sunFinales) if (sf.sunStyle) renderSunFinaleCraterTotem(mat, vc, sf, camX, camY, camZ);
            });
        }
        boolean hasSunDiscs = snap.deadCalms.stream().anyMatch(d -> d.sunStyle)
                || snap.auras.stream().anyMatch(a -> a.sunStyle)
                || snap.sunFinales.stream().anyMatch(s -> s.sunStyle);
        if (hasSunDiscs) {
            collector.submitCustomGeometry(poseStack, SUN_HALO_MIRROR_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (DeadCalmSnap dc : snap.deadCalms) if (dc.sunStyle) renderDeadCalmMirror(mat, vc, dc, camX, camY, camZ);
                for (ChargeAuraSnap c : snap.auras) if (c.sunStyle) renderChargeGroundDisc(mat, vc, c, camX, camY, camZ);
                for (SunFinaleSnap sf : snap.sunFinales) if (sf.sunStyle) renderSunFinaleInnerHalo(mat, vc, sf, camX, camY, camZ);
            });
        }

        // ====== Pass 6: 克制高级的体积光晕层（仅日呼灼热内焰 + 龙气 + 冲天柱/终结炽焰柱/漩涡，绝不发灰糊掉水墨！） ======
        boolean hasGlow = snap.bladeTrails.stream().anyMatch(t -> t.sunStyle)
                || !snap.dragons.isEmpty() || !snap.waterfalls.isEmpty() || !snap.whirlpools.isEmpty()
                || !snap.sunFinales.isEmpty();
        if (hasGlow) {
            collector.submitCustomGeometry(poseStack, GLOW_RIBBON_TYPE, (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (BladeTrailSnap tr : snap.bladeTrails) if (tr.sunStyle) renderBladeTrailGlow(mat, vc, tr, camX, camY, camZ, fp, camFwd);
                for (DragonSnap dr : snap.dragons) renderDragonGlowAura(mat, vc, dr, camX, camY, camZ, fp, camFwd);
                for (WaterfallSnap wf : snap.waterfalls) renderWaterfallGlow(mat, vc, wf, camX, camY, camZ);
                for (SunFinaleSnap sf : snap.sunFinales) renderSunFinaleGlow(mat, vc, sf, camX, camY, camZ);
                for (WhirlpoolSnap wp : snap.whirlpools) renderWhirlpoolGlow(mat, vc, wp, camX, camY, camZ);
            });
        }

        // ====== Pass 7: 极锐利刀锋核心斩光刃 + 方向拉伸高速细水针 + 雫波纹清冽水光环 + 鬼灭「隙之线」 ======
        if (!snap.bladeTrails.isEmpty() || !snap.streaks.isEmpty()
                || snap.openingThread != null || !snap.threadSnaps.isEmpty() || !snap.ripples.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (BladeTrailSnap tr : snap.bladeTrails) renderBladeCoreEdge(mat, vc, tr, camX, camY, camZ);
                for (StreakSnap st : snap.streaks) renderVelocityStreak(mat, vc, st, camX, camY, camZ);
                for (RippleRingSnap rr : snap.ripples) renderRippleRingHighlight(mat, vc, rr, camX, camY, camZ);
                if (snap.openingThread != null) {
                    renderOpeningThread(mat, vc, snap.openingThread, camX, camY, camZ, camRight, camUp);
                }
                for (ThreadSnapData ts : snap.threadSnaps) {
                    renderThreadSnap(mat, vc, ts, camX, camY, camZ);
                }
            });
        }

        // ====== Pass 8: 赛璐璐实色细节（龙角/长须/龙瞳 + 凪/幻日虹裂空斩） ======
        if (!snap.dragons.isEmpty() || !snap.rifts.isEmpty()) {
            collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, vc) -> {
                Matrix4fc mat = pose.pose();
                for (DragonSnap dr : snap.dragons) renderDragonHeadAccents(mat, vc, dr, camX, camY, camZ, fp, camFwd);
                for (RiftSlashSnap r : snap.rifts) renderCelRiftSlash(mat, vc, r, camX, camY, camZ);
            });
        }
    }

    // ==================== 1. 严丝合缝共享顶点 + 永久锁死法线/弧长/寿命 零抖动刀光带 ====================

    /**
     * 真·零抖动、零空隙、且自带「刀先破空 -> 浮世绘巨浪延迟澎湃展开」的浮世绘刀光带渲染器：
     * - 刚挥过的前 0.65 tick（奥义冲刺为 1.45 tick）紧收在刀尖外缘，视野 100% 澄澈看清敌群与刀锋切点；
     * - 慢半拍后顺着刚才刀划过的空间轨迹轰然澎湃炸开成巨幅浮世绘海浪/烈焰带，冲击力爆棚！
     * - 第一人称本地玩家增加柔和近眼中心避让，第三人称保留 100% 原生史诗全宽！
     */
    private static void renderLiveBladeTrail(
            Matrix4fc mat, VertexConsumer vc, BladeTrailSnap tr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        int n = tr.bases.size();
        if (n < 2) return;

        final int crossSegs = 4;
        final double maxLife = WaterVfxManager.LiveBladeTrail.MAX_LIFE_TICKS;
        float uvScale = tr.sunStyle ? 0.28F : 0.30F;
        float uvScroll = -(float) ((tr.renderTime * (tr.sunStyle ? 0.16 : 0.13)) % 1000.0);

        Vec3[][] grid = new Vec3[n][crossSegs + 1];
        float[] uCoords = new float[n];

        for (int i = 0; i < n; i++) {
            Vec3 b = tr.bases.get(i);
            Vec3 t = tr.tips.get(i);
            Vec3 nDir = tr.normals.get(i);
            float ageTicks = (float) Math.max(0.0, tr.renderTime - tr.birthTimes.get(i));
            boolean rush = tr.rushFlags.get(i);
            float lifeFrac = Mth.clamp(ageTicks / (float) maxLife, 0.0F, 1.0F);

            // 刀先破空 -> 慢半拍轰然展开成滔天浪带 -> 丝滑自然收锋！
            float wakeExpand = computeWakeExpand(ageTicks, rush);
            float tailDecay = (float) Math.pow(1.0F - lifeFrac, 0.62);
            float taper = tailDecay * wakeExpand;
            if (firstPerson && tr.isLocalPlayer) {
                Vec3 mid = b.lerp(t, 0.55);
                taper *= computeFirstPersonClearFactor(mid.x - camX, mid.y - camY, mid.z - camZ, camForward, 2.25, 0.64, 0.16F);
            }
            float inten = tr.intensities.get(i);

            Vec3 effB = t.lerp(b, Mth.clamp(inten * taper, 0.03F, 1.0F));
            Vec3 span = t.subtract(effB);
            double arch = Math.min(0.22, span.length() * 0.085) * taper;

            for (int k = 0; k <= crossSegs; k++) {
                float f = k / (float) crossSegs;
                float lift = Mth.sin(f * Mth.PI);
                grid[i][k] = effB.lerp(t, f).add(nDir.scale(arch * lift));
            }
            uCoords[i] = -tr.arcDists.get(i) * uvScale + uvScroll;
        }

        for (int i = 0; i < n - 1; i++) {
            double stepDist = tr.tips.get(i).distanceTo(tr.tips.get(i + 1));
            if (stepDist > 3.6) continue;

            float u0 = uCoords[i];
            float u1 = uCoords[i + 1];
            for (int k = 0; k < crossSegs; k++) {
                float vA = Mth.lerp(k / (float) crossSegs, 0.02F, 0.98F);
                float vB = Mth.lerp((k + 1) / (float) crossSegs, 0.02F, 0.98F);
                Vec3 p0A = grid[i][k], p0B = grid[i][k + 1];
                Vec3 p1A = grid[i + 1][k], p1B = grid[i + 1][k + 1];

                emitTexQuad(mat, vc,
                        (float) (p0A.x - camX), (float) (p0A.y - camY), (float) (p0A.z - camZ), u0, vA,
                        (float) (p0B.x - camX), (float) (p0B.y - camY), (float) (p0B.z - camZ), u0, vB,
                        (float) (p1B.x - camX), (float) (p1B.y - camY), (float) (p1B.z - camZ), u1, vB,
                        (float) (p1A.x - camX), (float) (p1A.y - camY), (float) (p1A.z - camZ), u1, vA,
                        1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    /** 「刀先出、浪后卷」连续延迟澎湃展开函数：前段紧贴刀刃不挡眼，慢半拍后在刀痕后方轰然炸开巨幅浪带！ */
    private static float computeWakeExpand(float ageTicks, boolean rush) {
        if (rush) {
            if (ageTicks < 1.45F) {
                return 0.05F + (ageTicks / 1.45F) * 0.05F;
            }
            float u = Mth.clamp((ageTicks - 1.45F) / 1.75F, 0.0F, 1.0F);
            float inv = 1.0F - u;
            return 0.10F + 0.96F * (1.0F - inv * inv * inv);
        }
        if (ageTicks < 0.65F) {
            return 0.06F + (ageTicks / 0.65F) * 0.08F;
        }
        float u = Mth.clamp((ageTicks - 0.65F) / 1.40F, 0.0F, 1.0F);
        float inv = 1.0F - u;
        return 0.14F + 0.90F * (1.0F - inv * inv * inv);
    }

    /** 第一人称近眼中心柔和避让系数：距离摄像机极近且位于准星中心锥区时平滑收窄，彻底告别特效糊脸！ */
    private static float computeFirstPersonClearFactor(
            double dx, double dy, double dz, Vec3 camForward, double clearRadius, double minDot, float minFactor
    ) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 >= clearRadius * clearRadius || d2 < 1.0E-5) return 1.0F;
        double dist = Math.sqrt(d2);
        double dot = (dx * camForward.x + dy * camForward.y + dz * camForward.z) / dist;
        if (dot <= minDot) return 1.0F;
        float distF = smoothStep01((float) ((clearRadius - dist) / Math.max(0.4, clearRadius - 0.8)));
        float angF = smoothStep01((float) ((dot - minDot) / Math.max(0.1, 0.96 - minDot)));
        return Mth.lerp(distF * angF, 1.0F, minFactor);
    }

    private static float smoothStep01(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    // ==================== 2. 陆之型·扭转漩涡 螺旋伴生水带 ====================

    private static void renderUkiyoWhirlpool(
            Matrix4fc mat, VertexConsumer vc, WhirlpoolSnap wp, double camX, double camY, double camZ
    ) {
        float scale = (wp.progress < 0.14F ? wp.progress / 0.14F : 1.0F)
                * (1.0F - Mth.clamp((wp.progress - 0.68F) / 0.32F, 0.0F, 1.0F));
        if (scale <= 0.04F) return;

        float cx = (float) (wp.x - camX), cy = (float) (wp.y - camY) + 0.08F, cz = (float) (wp.z - camZ);
        // 内径扩大至 0.66*radius，在风暴中央留出清澈通透的「台风眼视野区」！
        drawTexturedHelixRibbon(mat, vc, cx, cy, cz, wp.radius * 0.66F, wp.radius * 1.18F, wp.height,
                3, 2.2F, wp.ageTicks * 0.44F, 1.08F * scale, -wp.ageTicks * 0.15F);
    }

    private static void drawTexturedHelixRibbon(
            Matrix4fc mat, VertexConsumer vc, float cx, float cy, float cz,
            float rBot, float rTop, float height, int strands, float turns,
            float rotPhase, float bandH, float uvScroll
    ) {
        int segs = 28;
        for (int s = 0; s < strands; s++) {
            float baseAng = (Mth.TWO_PI * s) / strands + rotPhase;
            for (int i = 0; i < segs; i++) {
                float t0 = i / (float) segs, t1 = (i + 1) / (float) segs;
                float ang0 = baseAng + t0 * Mth.TWO_PI * turns;
                float ang1 = baseAng + t1 * Mth.TWO_PI * turns;
                float rad0 = Mth.lerp(t0, rBot, rTop), rad1 = Mth.lerp(t1, rBot, rTop);
                float y0 = cy + t0 * height, y1 = cy + t1 * height;
                float bh0 = bandH * (0.28F + 0.85F * Mth.sin(t0 * Mth.PI));
                float bh1 = bandH * (0.28F + 0.85F * Mth.sin(t1 * Mth.PI));

                float x0 = cx + Mth.cos(ang0) * rad0, z0 = cz + Mth.sin(ang0) * rad0;
                float x1 = cx + Mth.cos(ang1) * rad1, z1 = cz + Mth.sin(ang1) * rad1;
                float u0 = t0 * 3.2F + uvScroll, u1 = t1 * 3.2F + uvScroll;

                emitTexQuad(mat, vc,
                        x0, y0, z0, u0, 0.02F,
                        x0, y0 + bh0, z0, u0, 0.98F,
                        x1, y1 + bh1, z1, u1, 0.98F,
                        x1, y1, z1, u1, 0.02F,
                        1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    // ==================== 3. 捌之型·泷壶 原版「自上而下倾泻瀑布水帘 + 砸潭环形激浪」 ====================

    private static void renderUkiyoWaterfall(
            Matrix4fc mat, VertexConsumer vc, WaterfallSnap wf, double camX, double camY, double camZ
    ) {
        float shrink = 1.0F - Mth.clamp((wf.progress - 0.50F) / 0.50F, 0.0F, 1.0F);
        if (shrink <= 0.04F) return;

        float cx = (float) (wf.x - camX), cy = (float) (wf.y - camY), cz = (float) (wf.z - camZ);
        // 自上而下倾泻的瀑布水帘（UV 严格向下高速奔流，绝不是反直觉往上喷的柱子！）
        float fallFrac = Mth.clamp(wf.progress * 3.8F, 0.25F, 1.0F);
        float topY = cy + wf.height * Mth.lerp(Mth.clamp((wf.progress - 0.22F) / 0.78F, 0.0F, 1.0F), 1.0F, 0.18F);
        float botY = cy + 0.08F + (1.0F - fallFrac) * wf.height * 0.55F;
        if (topY > botY + 0.25F) {
            // 4 道半弧瀑幕自高空向落点倾泻，落地底端自然向外飞散展宽！
            float uvDown = wf.ageTicks * 0.34F; // 正向递增让水纹严格自上而下倾泻！
            int sheets = 4;
            for (int s = 0; s < sheets; s++) {
                float ang = (Mth.TWO_PI * s) / sheets + wf.ageTicks * 0.03F;
                float c = Mth.cos(ang), sn = Mth.sin(ang);
                float tx = -sn, tz = c;
                float rTop = wf.radius * 0.14F;
                float rBot = wf.radius * (0.28F + wf.progress * 0.28F);
                float wTop = wf.radius * 0.24F * shrink;
                float wBot = wf.radius * 0.42F * shrink;

                float topCx = cx + c * rTop, topCz = cz + sn * rTop;
                float botCx = cx + c * rBot, botCz = cz + sn * rBot;
                emitTexQuad(mat, vc,
                        topCx - tx * wTop, topY, topCz - tz * wTop, uvDown, 0.08F,
                        topCx + tx * wTop, topY, topCz + tz * wTop, uvDown, 0.92F,
                        botCx + tx * wBot, botY, botCz + tz * wBot, uvDown + 2.6F, 0.92F,
                        botCx - tx * wBot, botY, botCz - tz * wBot, uvDown + 2.6F, 0.08F,
                        1.0F, 1.0F, 1.0F, 1.0F);
            }
        }

        // 瀑布砸入地面后瞬间向四周贴地横扫爆开的 2 道浮世绘激浪环（符合直觉的「砸潭水爆」！）
        for (int rIdx = 0; rIdx < 2; rIdx++) {
            float rp = Mth.clamp(wf.progress * 1.45F - rIdx * 0.18F, 0.0F, 1.0F);
            if (rp <= 0.02F || rp >= 0.96F) continue;
            float rOut = wf.radius * (0.32F + rp * 1.05F);
            float rIn = Math.max(0.22F, rOut - (1.42F - rp * 0.95F) * shrink);
            drawTexturedGroundRing(mat, vc, cx, cy + 0.14F + rIdx * 0.16F, cz, rIn, rOut, 0.54F * (1.0F - rp),
                    wf.ageTicks * 0.22F, -wf.ageTicks * 0.16F);
        }
    }

    private static void drawTexturedGroundRing(
            Matrix4fc mat, VertexConsumer vc, float cx, float cy, float cz,
            float rIn, float rOut, float crestLift, float rotPhase, float uvScroll
    ) {
        int segs = 32;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs, t1 = (i + 1) / (float) segs;
            float a0 = t0 * Mth.TWO_PI + rotPhase, a1 = t1 * Mth.TWO_PI + rotPhase;
            float u0 = t0 * 3.5F + uvScroll, u1 = t1 * 3.5F + uvScroll;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            emitTexQuad(mat, vc,
                    cx + c0 * rIn, cy, cz + s0 * rIn, u0, 0.02F,
                    cx + c0 * rOut, cy + crestLift, cz + s0 * rOut, u0, 0.98F,
                    cx + c1 * rOut, cy + crestLift, cz + s1 * rOut, u1, 0.98F,
                    cx + c1 * rIn, cy, cz + s1 * rIn, u1, 0.02F,
                    1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    // ==================== 4. 真·鬼灭浮世绘 3D 东方神龙（修长蓝背白腹/赤背金腹 + 立体龙首 + 分叉鹿角 + 龙牙 + 飘逸龙须 + 四爪！） ====================

    /** 东方神龙修长解剖径向轮廓：颈部微收 -> 胸腰流畅饱满 -> 尾端如长鞭极细飘逸！ */
    private static float dragonRadiusProfile(float u) {
        if (u < 0.08F) {
            return Mth.lerp(u / 0.08F, 0.74F, 0.88F);
        }
        if (u < 0.34F) {
            float f = (u - 0.08F) / 0.26F;
            return 0.88F + 0.14F * Mth.sin(f * Mth.PI);
        }
        float f = (u - 0.34F) / 0.66F;
        return Mth.clamp(0.88F * (float) Math.pow(1.0F - f, 0.88) + 0.05F, 0.05F, 1.0F);
    }

    private static void renderDragonScaledBody(
            Matrix4fc mat, VertexConsumer vc, DragonSnap dr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        float shrink = 1.0F - Mth.clamp((dr.lifeNorm - 0.72F) / 0.28F, 0.0F, 1.0F);
        if (shrink <= 0.05F) return;

        // 东方神龙黄金修长比例（基准半径 ~0.46 格，直径 ~0.92 格，彻底告别大香肠肥虫！）
        float bodyScale = (0.86F + dr.tier * 0.10F) * shrink * dr.scaleMult;
        Vec3[] spine = dr.spine;
        int n = spine.length;
        Vec3[][] frames = computeSpineFrames(spine);
        Vec3[] rights = frames[0], ups = frames[1];
        int sides = 10;
        float[][] rx = new float[n][sides], ry = new float[n][sides], rz = new float[n][sides];

        for (int i = 0; i < n; i++) {
            float u = i / (float) Math.max(1, n - 1);
            float taper = dragonRadiusProfile(u);
            float px = (float) (spine[i].x - camX), py = (float) (spine[i].y - camY), pz = (float) (spine[i].z - camZ);
            float clear = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(px, py, pz, camForward, 2.35, 0.52, 0.22F)
                    : 1.0F;
            float rad = 0.42F * bodyScale * taper * clear * (1.0F + 0.04F * Mth.sin(u * 20.0F - dr.ageTicks * 0.55F));
            Vec3 rVec = rights[i], uVec = ups[i];
            for (int s = 0; s < sides; s++) {
                // s=0 (V=0.0) 严格位于龙腹正底端 -up，s=5 (V=0.5) 严格位于龙脊正顶端 +up，0 麻花扭曲！
                float ang = (Mth.TWO_PI * s) / sides;
                float rOff = Mth.sin(ang) * rad * 0.94F;
                float uOff = -Mth.cos(ang) * rad * 1.06F;
                rx[i][s] = px + (float) (rVec.x * rOff + uVec.x * uOff);
                ry[i][s] = py + (float) (rVec.y * rOff + uVec.y * uOff);
                rz[i][s] = pz + (float) (rVec.z * rOff + uVec.z * uOff);
            }
        }

        // 高密流畅细龙鳞沿龙身向尾叠覆（u 从 0 -> 4.6，与 1024px 细鳞贴图精准匹配！）
        float uvScroll = -dr.ageTicks * 0.16F;
        for (int i = 0; i < n - 1; i++) {
            float u0 = (i / (float) Math.max(1, n - 1)) * 4.6F + uvScroll;
            float u1 = ((i + 1) / (float) Math.max(1, n - 1)) * 4.6F + uvScroll;
            for (int s = 0; s < sides; s++) {
                int sn = (s + 1) % sides;
                float v0 = s / (float) sides, v1 = (s + 1) / (float) sides;
                emitTexQuad(mat, vc,
                        rx[i][s], ry[i][s], rz[i][s], u0, v0,
                        rx[i][sn], ry[i][sn], rz[i][sn], u0, v1,
                        rx[i + 1][sn], ry[i + 1][sn], rz[i + 1][sn], u1, v1,
                        rx[i + 1][s], ry[i + 1][s], rz[i + 1][s], u1, v0,
                        1.0F, 1.0F, 1.0F, 1.0F);
            }
        }

        // ====== 3D 立体雕塑级东方神龙首（饱满颅骨 + 隆起双眉脊 + 修长微翘龙吻 + 怒张立体下颌！） ======
        Vec3 head = spine[0];
        Vec3 fwd = segmentTangent(spine, 0);
        Vec3 right = rights[0], up = ups[0];
        float headClear = (firstPerson && dr.isLocalPlayer)
                ? computeFirstPersonClearFactor(head.x - camX, head.y - camY, head.z - camZ, camForward, 2.35, 0.52, 0.25F)
                : 1.0F;
        float hs = bodyScale * headClear;
        float headL = 1.55F * hs;

        // 上颌/颅骨 4 级 3D 棱柱截面（从后颅 -> 饱满眉骨 -> 修长鼻梁 -> 微翘如意吻端）
        float[] zSec = { -0.10F * headL, 0.30F * headL, 0.78F * headL, 1.08F * headL };
        float[] wSec = { 0.40F * hs,     0.48F * hs,    0.30F * hs,    0.18F * hs };
        float[] tSec = { 0.42F * hs,     0.52F * hs,    0.32F * hs,    0.26F * hs }; // 顶脊高度（眉骨高隆、吻端微翘）
        float[] bSec = { -0.05F * hs,    0.04F * hs,    0.02F * hs,    0.06F * hs }; // 上腭底缘
        for (int sec = 0; sec < 3; sec++) {
            Vec3 c0 = head.add(fwd.scale(zSec[sec]));
            Vec3 c1 = head.add(fwd.scale(zSec[sec + 1]));
            Vec3 tl0 = c0.add(right.scale(-wSec[sec] * 0.72)).add(up.scale(tSec[sec]));
            Vec3 tr0 = c0.add(right.scale(wSec[sec] * 0.72)).add(up.scale(tSec[sec]));
            Vec3 bl0 = c0.add(right.scale(-wSec[sec])).add(up.scale(bSec[sec]));
            Vec3 br0 = c0.add(right.scale(wSec[sec])).add(up.scale(bSec[sec]));

            Vec3 tl1 = c1.add(right.scale(-wSec[sec + 1] * 0.72)).add(up.scale(tSec[sec + 1]));
            Vec3 tr1 = c1.add(right.scale(wSec[sec + 1] * 0.72)).add(up.scale(tSec[sec + 1]));
            Vec3 bl1 = c1.add(right.scale(-wSec[sec + 1])).add(up.scale(bSec[sec + 1]));
            Vec3 br1 = c1.add(right.scale(wSec[sec + 1])).add(up.scale(bSec[sec + 1]));

            float uA = sec * 0.30F, uB = (sec + 1) * 0.30F;
            // 龙额/鼻梁顶面（贴深色脊鳞 V=0.38..0.62）
            emitWorldTexQuad(mat, vc, tl0, tr0, tr1, tl1, camX, camY, camZ, uA, 0.38F, uB, 0.62F);
            // 左颊面 + 右颊面（贴侧鳞 V=0.22..0.48）
            emitWorldTexQuad(mat, vc, bl0, tl0, tl1, bl1, camX, camY, camZ, uA, 0.22F, uB, 0.48F);
            emitWorldTexQuad(mat, vc, tr0, br0, br1, tr1, camX, camY, camZ, uA, 0.52F, uB, 0.78F);
            // 上腭底面（贴浅色腹甲 V=0.04..0.16）
            emitWorldTexQuad(mat, vc, br0, bl0, bl1, br1, camX, camY, camZ, uA, 0.04F, uB, 0.16F);
        }
        // 封住立体龙吻前端鼻面
        Vec3 noseC = head.add(fwd.scale(zSec[3]));
        emitWorldTexQuad(mat, vc,
                noseC.add(right.scale(-wSec[3] * 0.72)).add(up.scale(tSec[3])),
                noseC.add(right.scale(wSec[3] * 0.72)).add(up.scale(tSec[3])),
                noseC.add(right.scale(wSec[3])).add(up.scale(bSec[3])),
                noseC.add(right.scale(-wSec[3])).add(up.scale(bSec[3])),
                camX, camY, camZ, 0.85F, 0.35F, 1.0F, 0.65F);

        // 怒张咆哮的 3D 立体下颌（微微向下张开 24°，下表面贴浅色龙腹甲，威风凛凛！）
        Vec3 jawRoot = head.add(fwd.scale(0.06F * headL)).add(up.scale(-0.14F * hs));
        Vec3 jawDir = fwd.scale(0.91).add(up.scale(-0.41)).normalize();
        Vec3 jawUp = right.cross(jawDir).normalize();
        float[] jz = { 0.0F, 0.45F * headL, 0.86F * headL };
        float[] jw = { 0.36F * hs, 0.28F * hs, 0.15F * hs };
        float[] jh = { 0.16F * hs, 0.14F * hs, 0.10F * hs };
        for (int sec = 0; sec < 2; sec++) {
            Vec3 c0 = jawRoot.add(jawDir.scale(jz[sec]));
            Vec3 c1 = jawRoot.add(jawDir.scale(jz[sec + 1]));
            Vec3 tl0 = c0.add(right.scale(-jw[sec])).add(jawUp.scale(jh[sec] * 0.5));
            Vec3 tr0 = c0.add(right.scale(jw[sec])).add(jawUp.scale(jh[sec] * 0.5));
            Vec3 bl0 = c0.add(right.scale(-jw[sec] * 0.75)).add(jawUp.scale(-jh[sec]));
            Vec3 br0 = c0.add(right.scale(jw[sec] * 0.75)).add(jawUp.scale(-jh[sec]));

            Vec3 tl1 = c1.add(right.scale(-jw[sec + 1])).add(jawUp.scale(jh[sec + 1] * 0.5));
            Vec3 tr1 = c1.add(right.scale(jw[sec + 1])).add(jawUp.scale(jh[sec + 1] * 0.5));
            Vec3 bl1 = c1.add(right.scale(-jw[sec + 1] * 0.75)).add(jawUp.scale(-jh[sec + 1]));
            Vec3 br1 = c1.add(right.scale(jw[sec + 1] * 0.75)).add(jawUp.scale(-jh[sec + 1]));

            float uA = sec * 0.35F, uB = (sec + 1) * 0.35F;
            emitWorldTexQuad(mat, vc, bl0, br0, br1, bl1, camX, camY, camZ, uA, 0.02F, uB, 0.16F);
            emitWorldTexQuad(mat, vc, bl0, tl0, tl1, bl1, camX, camY, camZ, uA, 0.25F, uB, 0.45F);
            emitWorldTexQuad(mat, vc, tr0, br0, br1, tr1, camX, camY, camZ, uA, 0.55F, uB, 0.75F);
            emitWorldTexQuad(mat, vc, tl0, tr0, tr1, tl1, camX, camY, camZ, uA, 0.38F, uB, 0.62F);
        }
    }

    private static void emitWorldTexQuad(
            Matrix4fc mat, VertexConsumer vc, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
            double camX, double camY, double camZ, float u0, float v0, float u1, float v1
    ) {
        emitTexQuad(mat, vc,
                (float) (a.x - camX), (float) (a.y - camY), (float) (a.z - camZ), u0, v0,
                (float) (b.x - camX), (float) (b.y - camY), (float) (b.z - camZ), u0, v1,
                (float) (c.x - camX), (float) (c.y - camY), (float) (c.z - camZ), u1, v1,
                (float) (d.x - camX), (float) (d.y - camY), (float) (d.z - camZ), u1, v0,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Pass 2A/2B：东方神龙波浪锯齿背棘 + 2 股轻盈缠身流动浮世绘水浪/烈焰丝带 + 龙尾飘逸浪花流苏！
     * （彻底告别塑料平板大背帆与僵硬大飞机翅膀！）
     */
    private static void renderDragonFlankStreams(
            Matrix4fc mat, VertexConsumer vc, DragonSnap dr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        float shrink = 1.0F - Mth.clamp((dr.lifeNorm - 0.72F) / 0.28F, 0.0F, 1.0F);
        if (shrink <= 0.05F) return;

        float bodyScale = (0.86F + dr.tier * 0.10F) * shrink * dr.scaleMult;
        Vec3[] spine = dr.spine;
        int n = spine.length;
        Vec3[][] frames = computeSpineFrames(spine);
        Vec3[] rights = frames[0], ups = frames[1];
        float uvScroll = -dr.ageTicks * 0.22F;

        // 1. 两条轻盈螺旋缠绕龙躯的浮世绘激浪 / 烈焰流带（像原版动画里包裹龙身流淌的奔涌水浪/烈焰！）
        for (int strand = 0; strand < 2; strand++) {
            float phaseOffset = strand * Mth.PI;
            for (int i = 1; i < n - 1; i++) {
                float u0 = i / (float) Math.max(1, n - 1);
                float u1 = (i + 1) / (float) Math.max(1, n - 1);
                float c0 = (firstPerson && dr.isLocalPlayer)
                        ? computeFirstPersonClearFactor(spine[i].x - camX, spine[i].y - camY, spine[i].z - camZ, camForward, 2.4, 0.52, 0.15F) : 1.0F;
                float c1 = (firstPerson && dr.isLocalPlayer)
                        ? computeFirstPersonClearFactor(spine[i + 1].x - camX, spine[i + 1].y - camY, spine[i + 1].z - camZ, camForward, 2.4, 0.52, 0.15F) : 1.0F;

                float env0 = Mth.sin(u0 * Mth.PI);
                float env1 = Mth.sin(u1 * Mth.PI);
                float rad0 = (0.46F * dragonRadiusProfile(u0) + 0.14F * env0) * bodyScale * c0;
                float rad1 = (0.46F * dragonRadiusProfile(u1) + 0.14F * env1) * bodyScale * c1;
                float bandW0 = (0.34F + 0.22F * env0) * bodyScale * c0;
                float bandW1 = (0.34F + 0.22F * env1) * bodyScale * c1;

                float ph0 = u0 * 11.0F - dr.ageTicks * 0.34F + phaseOffset;
                float ph1 = u1 * 11.0F - dr.ageTicks * 0.34F + phaseOffset;
                Vec3 dir0 = rights[i].scale(Mth.cos(ph0)).add(ups[i].scale(Mth.sin(ph0)));
                Vec3 dir1 = rights[i + 1].scale(Mth.cos(ph1)).add(ups[i + 1].scale(Mth.sin(ph1)));

                Vec3 in0 = spine[i].add(dir0.scale(rad0));
                Vec3 out0 = spine[i].add(dir0.scale(rad0 + bandW0));
                Vec3 in1 = spine[i + 1].add(dir1.scale(rad1));
                Vec3 out1 = spine[i + 1].add(dir1.scale(rad1 + bandW1));
                float tu0 = u0 * 4.5F + uvScroll, tu1 = u1 * 4.5F + uvScroll;

                emitWorldTexQuad(mat, vc, in0, out0, out1, in1, camX, camY, camZ, tu0, 0.04F, tu1, 0.96F);
            }
        }

        // 2. 波浪锯齿掠动龙背棘（纤细锐利、随波浪起伏的东方龙脊浪峰，绝不是僵硬塑料大平板！）
        for (int i = 0; i < n - 1; i++) {
            float u0 = i / (float) Math.max(1, n - 1);
            float u1 = (i + 1) / (float) Math.max(1, n - 1);
            float c0 = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(spine[i].x - camX, spine[i].y - camY, spine[i].z - camZ, camForward, 2.4, 0.52, 0.15F) : 1.0F;
            float c1 = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(spine[i + 1].x - camX, spine[i + 1].y - camY, spine[i + 1].z - camZ, camForward, 2.4, 0.52, 0.15F) : 1.0F;

            // 尾端 (u > 0.80) 舒展成一簇潇洒飘逸的龙尾浪花/火焰流苏！
            float tailPlume0 = u0 > 0.80F ? Mth.sin(((u0 - 0.80F) / 0.20F) * Mth.PI) * 0.52F : 0.0F;
            float tailPlume1 = u1 > 0.80F ? Mth.sin(((u1 - 0.80F) / 0.20F) * Mth.PI) * 0.52F : 0.0F;
            float saw0 = 0.42F + 0.58F * Math.abs(Mth.sin(u0 * 24.0F - dr.ageTicks * 0.32F));
            float saw1 = 0.42F + 0.58F * Math.abs(Mth.sin(u1 * 24.0F - dr.ageTicks * 0.32F));
            float h0 = ((0.46F * dragonRadiusProfile(u0) + 0.08F) * saw0 + tailPlume0) * bodyScale * c0;
            float h1 = ((0.46F * dragonRadiusProfile(u1) + 0.08F) * saw1 + tailPlume1) * bodyScale * c1;

            Vec3 fwd0 = segmentTangent(spine, i), fwd1 = segmentTangent(spine, i + 1);
            Vec3 base0 = spine[i].add(ups[i].scale(0.38 * dragonRadiusProfile(u0) * bodyScale * c0));
            Vec3 top0 = base0.add(ups[i].scale(h0)).add(fwd0.scale(-h0 * 0.45));
            Vec3 base1 = spine[i + 1].add(ups[i + 1].scale(0.38 * dragonRadiusProfile(u1) * bodyScale * c1));
            Vec3 top1 = base1.add(ups[i + 1].scale(h1)).add(fwd1.scale(-h1 * 0.45));
            float tu0 = u0 * 5.0F + uvScroll, tu1 = u1 * 5.0F + uvScroll;

            emitWorldTexQuad(mat, vc, base0, top0, top1, base1, camX, camY, camZ, tu0, 0.10F, tu1, 0.94F);
        }
    }

    private static void renderDragonClawMane(
            Matrix4fc mat, VertexConsumer vc, DragonSnap dr, double camX, double camY, double camZ,
            Vector3f camRight, Vector3f camUp, boolean firstPerson, Vec3 camForward
    ) {
        float shrink = 1.0F - Mth.clamp((dr.lifeNorm - 0.72F) / 0.28F, 0.0F, 1.0F);
        if (shrink <= 0.06F) return;

        float bodyScale = (0.76F + dr.tier * 0.10F) * shrink * dr.scaleMult;
        Vec3[] spine = dr.spine;
        Vec3[][] frames = computeSpineFrames(spine);
        Vec3[] ups = frames[1];
        // 克制精致的颈鬃与龙尾白沫/火舌点缀，绝不糊满龙身！
        int[] picks = { 1, Math.max(2, spine.length / 5), Math.max(3, (spine.length * 3) / 5), spine.length - 2 };
        for (int idx : picks) {
            if (idx < 0 || idx >= spine.length) continue;
            float u = idx / (float) Math.max(1, spine.length - 1);
            Vec3 p = spine[idx].add(ups[idx].scale(0.36 * bodyScale * (1.0 - u * 0.5)));
            double dx = p.x - camX, dy = p.y - camY, dz = p.z - camZ;
            float clear = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(dx, dy, dz, camForward, 2.5, 0.55, 0.0F) : 1.0F;
            float sz = bodyScale * (idx == 1 ? 0.95F : 0.72F) * clear;
            if (sz <= 0.08F) continue;
            drawBillboardAtlasSprite(mat, vc,
                    (float) dx, (float) dy, (float) dz,
                    sz, (idx == 1) ? 0 : 1, idx * 31.0F + dr.ageTicks * 3.0F, camRight, camUp);
        }
    }

    /**
     * Pass 8：精雕东方神龙五官与四爪 —— 分叉鹿角龙角 + 6 段飘逸波浪龙须 + 4 对锋利雪白獠牙 + 狭长烈瞳 + 颈后怒张鬃棘 + 东方神龙四爪！
     */
    private static void renderDragonHeadAccents(
            Matrix4fc mat, VertexConsumer vc, DragonSnap dr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        float shrink = 1.0F - Mth.clamp((dr.lifeNorm - 0.72F) / 0.28F, 0.0F, 1.0F);
        if (shrink <= 0.05F) return;
        Vec3[] spine = dr.spine;
        int n = spine.length;
        Vec3[][] frames = computeSpineFrames(spine);
        Vec3[] rights = frames[0], ups = frames[1];

        Vec3 head = spine[0];
        float headClear = (firstPerson && dr.isLocalPlayer)
                ? computeFirstPersonClearFactor(head.x - camX, head.y - camY, head.z - camZ, camForward, 2.35, 0.52, 0.25F)
                : 1.0F;
        float bodyScale = (0.86F + dr.tier * 0.10F) * shrink * dr.scaleMult;
        float hs = bodyScale * headClear;
        Vec3 fwd = segmentTangent(spine, 0);
        Vec3 right = rights[0], up = ups[0];
        float headL = 1.55F * hs;

        // 配色：水龙冰白青芒、火龙鎏金炽白
        float hornR = dr.sunStyle ? 1.00F : 0.90F;
        float hornG = dr.sunStyle ? 0.86F : 0.98F;
        float hornB = dr.sunStyle ? 0.42F : 1.00F;
        float eyeR = dr.sunStyle ? 1.00F : 0.92F;
        float eyeG = dr.sunStyle ? 0.92F : 0.99F;
        float eyeB = dr.sunStyle ? 0.28F : 1.00F;
        float limbR = dr.sunStyle ? 0.88F : 0.16F;
        float limbG = dr.sunStyle ? 0.22F : 0.54F;
        float limbB = dr.sunStyle ? 0.08F : 0.92F;

        for (int side = -1; side <= 1; side += 2) {
            // 1. 优雅弯曲分叉鹿角状龙角（彻底告别黑粗木棍！）
            Vec3 h0 = head.add(fwd.scale(0.16F * headL)).add(right.scale(0.28F * hs * side)).add(up.scale(0.46F * hs));
            Vec3 h1 = h0.add(fwd.scale(-0.62F * hs)).add(right.scale(0.22F * hs * side)).add(up.scale(0.46F * hs));
            Vec3 h2 = h1.add(fwd.scale(-0.78F * hs)).add(right.scale(0.14F * hs * side)).add(up.scale(0.32F * hs));
            Vec3 h3 = h2.add(fwd.scale(-0.52F * hs)).add(right.scale(0.05F * hs * side)).add(up.scale(0.34F * hs));
            Vec3 tine = h1.add(fwd.scale(-0.22F * hs)).add(right.scale(0.26F * hs * side)).add(up.scale(0.46F * hs));
            drawCelOutlinedBeam(mat, vc, h0, h1, camX, camY, camZ, 0.082F * hs, 0.058F * hs, hornR, hornG, hornB);
            drawCelOutlinedBeam(mat, vc, h1, h2, camX, camY, camZ, 0.058F * hs, 0.036F * hs, hornR, hornG, hornB);
            drawCelOutlinedBeam(mat, vc, h2, h3, camX, camY, camZ, 0.036F * hs, 0.012F * hs, hornR, hornG, hornB);
            drawCelOutlinedBeam(mat, vc, h1, tine, camX, camY, camZ, 0.044F * hs, 0.012F * hs, hornR, hornG, hornB);

            // 2. 6 段实时波浪飘舞的 S 形修长龙须（从吻部鼻翼优雅向后流淌！）
            Vec3 prevW = head.add(fwd.scale(0.84F * headL)).add(right.scale(0.24F * hs * side)).add(up.scale(0.14F * hs));
            for (int seg = 1; seg <= 6; seg++) {
                float f = seg / 6.0F;
                float wave1 = Mth.sin(f * 5.2F - dr.ageTicks * 0.42F + side * 0.8F) * 0.34F * hs * f;
                float wave2 = Mth.cos(f * 4.4F - dr.ageTicks * 0.36F) * 0.24F * hs * f;
                Vec3 nextW = head
                        .add(fwd.scale((0.84F - f * 2.25F) * headL))
                        .add(right.scale((0.24F + f * 1.15F) * hs * side + wave1))
                        .add(up.scale((0.14F - f * 0.12F) * hs + wave2));
                float wA = Mth.lerp((seg - 1) / 6.0F, 0.052F, 0.012F) * hs;
                float wB = Mth.lerp(f, 0.052F, 0.012F) * hs;
                drawCelOutlinedBeam(mat, vc, prevW, nextW, camX, camY, camZ, wA, wB, hornR, hornG, hornB);
                prevW = nextW;
            }

            // 3. 狭长上挑的威严东方龙烈瞳（干净贴合于隆起眉脊下方颊侧，绝不穿插进颅骨、绝不是傻大黄方块！）
            Vec3 eyeC = head.add(fwd.scale(0.38F * headL)).add(right.scale(0.45F * hs * side)).add(up.scale(0.30F * hs));
            Vec3 eyeLong = fwd.scale(-0.19F * hs).add(up.scale(0.08F * hs));
            Vec3 eyeTall = up.scale(0.075F * hs).add(fwd.scale(0.03F * hs));
            Vec3 eyeOut = right.scale(0.026F * hs * side);
            // 黑墨眼眶 + 狭长炽亮龙瞳（严格外浮一层杜绝 Z-Fighting！）
            emitEyeDiamond(mat, vc, eyeC, eyeLong.scale(1.36), eyeTall.scale(1.44), camX, camY, camZ, 0.04F, 0.05F, 0.08F, 1.0F);
            emitEyeDiamond(mat, vc, eyeC.add(eyeOut), eyeLong, eyeTall, camX, camY, camZ, eyeR, eyeG, eyeB, 1.0F);

            // 4. 4 对尖锐雪白龙獠牙（上下颌缘怒张撕咬！）
            for (int tIdx = 0; tIdx < 3; tIdx++) {
                float tf = 0.42F + tIdx * 0.22F;
                float toothH = (tIdx == 2 ? 0.20F : 0.14F) * hs;
                Vec3 topToothBase = head.add(fwd.scale(tf * headL)).add(right.scale(Mth.lerp(tf, 0.36F, 0.18F) * hs * side)).add(up.scale(0.02F * hs));
                Vec3 topToothTip = topToothBase.add(up.scale(-toothH)).add(fwd.scale(-0.04F * hs));
                drawCelOutlinedBeam(mat, vc, topToothBase, topToothTip, camX, camY, camZ, 0.038F * hs, 0.008F * hs, 0.98F, 0.99F, 1.00F);
            }
            // 下颌前锋尖獠牙
            Vec3 botToothBase = head.add(fwd.scale(0.66F * headL)).add(right.scale(0.18F * hs * side)).add(up.scale(-0.34F * hs));
            Vec3 botToothTip = botToothBase.add(up.scale(0.18F * hs)).add(fwd.scale(0.05F * hs));
            drawCelOutlinedBeam(mat, vc, botToothBase, botToothTip, camX, camY, camZ, 0.036F * hs, 0.008F * hs, 0.98F, 0.99F, 1.00F);
        }

        // 5. 颈后放射状 7 瓣怒张浮世绘龙鬃鬣毛（取代糊脸贴纸！）
        for (int m = -3; m <= 3; m++) {
            float mAng = m * 0.38F;
            Vec3 radial = up.scale(Mth.cos(mAng)).add(right.scale(Mth.sin(mAng)));
            Vec3 mBase = head.add(fwd.scale(-0.08F * headL)).add(radial.scale(0.36F * hs));
            Vec3 mTip = mBase.add(fwd.scale(-0.92F * hs)).add(radial.scale(0.55F * hs));
            drawCelOutlinedBeam(mat, vc, mBase, mTip, camX, camY, camZ, 0.085F * hs, 0.015F * hs, hornR, hornG, hornB);
        }

        // 6. 东方神龙四爪（前双爪 u=0.20 霸气前探撕抓 + 后双爪 u=0.58 蹬踏气浪！每爪三根弯曲尖锐利爪！）
        int[] legIndices = { Math.max(2, (int) (n * 0.20F)), Math.max(4, (int) (n * 0.58F)) };
        for (int legPair = 0; legPair < 2; legPair++) {
            int idx = legIndices[legPair];
            if (idx >= n - 2) continue;
            float u = idx / (float) Math.max(1, n - 1);
            Vec3 anchor = spine[idx];
            float legClear = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(anchor.x - camX, anchor.y - camY, anchor.z - camZ, camForward, 2.35, 0.52, 0.15F)
                    : 1.0F;
            float ls = bodyScale * legClear * (legPair == 0 ? 1.0F : 0.82F);
            Vec3 lFwd = segmentTangent(spine, idx);
            Vec3 lRight = rights[idx], lUp = ups[idx];
            float reachSign = (legPair == 0) ? 1.0F : -0.65F;

            for (int side = -1; side <= 1; side += 2) {
                Vec3 shoulder = anchor.add(lRight.scale(0.32F * dragonRadiusProfile(u) * ls * side)).add(lUp.scale(-0.14F * ls));
                Vec3 elbow = shoulder.add(lRight.scale(0.46F * ls * side)).add(lUp.scale(-0.32F * ls)).add(lFwd.scale(-0.18F * ls));
                Vec3 wrist = elbow.add(lFwd.scale(0.56F * ls * reachSign)).add(lRight.scale(0.18F * ls * side)).add(lUp.scale(-0.22F * ls));
                drawCelOutlinedBeam(mat, vc, shoulder, elbow, camX, camY, camZ, 0.115F * ls, 0.085F * ls, limbR, limbG, limbB);
                drawCelOutlinedBeam(mat, vc, elbow, wrist, camX, camY, camZ, 0.085F * ls, 0.065F * ls, limbR, limbG, limbB);
                // 肘部向后飘拂的一缕标志性龙肘毛！
                Vec3 tuftTip = elbow.add(lFwd.scale(-0.58F * ls)).add(lRight.scale(0.24F * ls * side));
                drawCelOutlinedBeam(mat, vc, elbow, tuftTip, camX, camY, camZ, 0.055F * ls, 0.010F * ls, hornR, hornG, hornB);

                // 三趾尖锐弯曲利爪（向正前/斜外张开抓握！）
                for (int claw = -1; claw <= 1; claw++) {
                    Vec3 clawDir = lFwd.scale(0.75 * reachSign).add(lRight.scale((0.35 * side + claw * 0.42))).add(lUp.scale(-0.45)).normalize();
                    Vec3 clawTip = wrist.add(clawDir.scale(0.38F * ls));
                    drawCelOutlinedBeam(mat, vc, wrist, clawTip, camX, camY, camZ, 0.036F * ls, 0.008F * ls, hornR, hornG, hornB);
                }
            }
        }
    }

    private static void emitEyeDiamond(
            Matrix4fc mat, VertexConsumer vc, Vec3 center, Vec3 longAxis, Vec3 tallAxis,
            double camX, double camY, double camZ, float r, float g, float b, float a
    ) {
        Vec3 p0 = center.subtract(longAxis);
        Vec3 p1 = center.add(tallAxis);
        Vec3 p2 = center.add(longAxis);
        Vec3 p3 = center.subtract(tallAxis);
        emitColorQuad(mat, vc,
                (float) (p0.x - camX), (float) (p0.y - camY), (float) (p0.z - camZ), r, g, b, a,
                (float) (p1.x - camX), (float) (p1.y - camY), (float) (p1.z - camZ), r, g, b, a,
                (float) (p2.x - camX), (float) (p2.y - camY), (float) (p2.z - camZ), r, g, b, a,
                (float) (p3.x - camX), (float) (p3.y - camY), (float) (p3.z - camZ), r, g, b, a);
    }

    // ==================== 5. 黑边白沫精灵 / 凪镜面 / 蓄力阵 ====================

    private static void renderFoamBillboard(
            Matrix4fc mat, VertexConsumer vc, FoamSpriteSnap s,
            double camX, double camY, double camZ, Vector3f camRight, Vector3f camUp,
            boolean firstPerson, Vec3 camForward
    ) {
        double dx = s.x - camX, dy = s.y - camY, dz = s.z - camZ;
        double d2 = dx * dx + dy * dy + dz * dz;
        // 紧贴眼球防穿近裁面
        if (d2 < 0.72) return;

        float popIn = Mth.clamp(s.progress * 5.5F, 0.35F, 1.05F);
        float shrinkOut = 1.0F - Mth.clamp((s.progress - 0.56F) / 0.44F, 0.0F, 1.0F);
        float scale = s.baseScale * popIn * shrinkOut;

        // 第一人称准星视野保护：中心近距贴片平滑外推到画面四周并缩薄（边缘依然炸裂飞溅，中心准星 100% 清澈！）
        if (firstPerson && d2 < 10.24) {
            double dist = Math.sqrt(d2);
            double dot = (dx * camForward.x + dy * camForward.y + dz * camForward.z) / dist;
            if (dist < 1.35 && dot > 0.30) return;
            if (dot > 0.56) {
                float distF = smoothStep01((float) ((3.2 - dist) / 1.85));
                float angF = smoothStep01((float) ((dot - 0.56) / 0.38));
                float block = distF * angF;
                scale *= Mth.lerp(block, 1.0F, 0.24F);
                // 沿垂直于视线的径向轻轻往画面边缘推开，增加四周高速飞掠感且清空正中央！
                double proj = dx * camForward.x + dy * camForward.y + dz * camForward.z;
                double rx = dx - camForward.x * proj;
                double ry = dy - camForward.y * proj;
                double rz = dz - camForward.z * proj;
                double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
                if (rLen > 0.04) {
                    double push = 0.85 * block;
                    dx += (rx / rLen) * push;
                    dy += (ry / rLen) * push;
                    dz += (rz / rLen) * push;
                }
            }
        }
        if (scale <= 0.08F) return;

        drawBillboardAtlasSprite(mat, vc,
                (float) dx, (float) dy, (float) dz,
                scale, s.tile, s.rollDeg, camRight, camUp);
    }

    private static void drawBillboardAtlasSprite(
            Matrix4fc mat, VertexConsumer vc, float cx, float cy, float cz,
            float size, int tile, float rollDeg, Vector3f camRight, Vector3f camUp
    ) {
        float half = size * 0.5F;
        float rad = rollDeg * Mth.DEG_TO_RAD;
        float c = Mth.cos(rad), s = Mth.sin(rad);

        float rx = (camRight.x * c + camUp.x * s) * half;
        float ry = (camRight.y * c + camUp.y * s) * half;
        float rz = (camRight.z * c + camUp.z * s) * half;

        float ux = (camUp.x * c - camRight.x * s) * half;
        float uy = (camUp.y * c - camRight.y * s) * half;
        float uz = (camUp.z * c - camRight.z * s) * half;

        float u0 = (tile % 2 == 0) ? 0.008F : 0.508F;
        float u1 = u0 + 0.484F;
        float v0 = (tile < 2) ? 0.008F : 0.508F;
        float v1 = v0 + 0.484F;

        emitTexQuad(mat, vc,
                cx - rx - ux, cy - ry - uy, cz - rz - uz, u0, v1,
                cx + rx - ux, cy + ry - uy, cz + rz - uz, u1, v1,
                cx + rx + ux, cy + ry + uy, cz + rz + uz, u1, v0,
                cx - rx + ux, cy - ry + uy, cz - rz + uz, u0, v0,
                1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void renderDeadCalmMirror(
            Matrix4fc mat, VertexConsumer vc, DeadCalmSnap dc, double camX, double camY, double camZ
    ) {
        float enter = Mth.clamp(dc.progress * 6.0F, 0.0F, 1.0F);
        float exit = 1.0F - Mth.clamp((dc.progress - 0.80F) / 0.20F, 0.0F, 1.0F);
        float alpha = enter * exit;
        if (alpha <= 0.02F) return;

        float cx = (float) (dc.x - camX), cy = (float) (dc.y - camY) + 0.04F, cz = (float) (dc.z - camZ);
        float maxR = dc.radius * Mth.clamp(enter * 1.15F, 0.25F, 1.0F);

        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, maxR, dc.ageTicks * 0.006F, 1.0F, 1.0F, 1.0F, 0.92F * alpha);
        for (int i = 0; i < 2; i++) {
            float rp = ((dc.ageTicks * 0.024F) + i * 0.5F) % 1.0F;
            float r = maxR * (0.28F + rp * 0.78F);
            float ra = Mth.sin(rp * Mth.PI) * 0.72F * alpha;
            drawHorizontalTexturedQuad(mat, vc, cx, cy + 0.03F + i * 0.015F, cz, r, -dc.ageTicks * 0.012F, 1.0F, 1.0F, 1.0F, ra);
        }
    }

    private static void renderChargeGroundDisc(
            Matrix4fc mat, VertexConsumer vc, ChargeAuraSnap c, double camX, double camY, double camZ
    ) {
        float fade = (1.0F - c.progress) * Mth.clamp(c.progress * 5.0F, 0.0F, 1.0F);
        if (fade <= 0.02F) return;
        float cx = (float) (c.x - camX), cy = (float) (c.y - camY) + 0.06F, cz = (float) (c.z - camZ);
        float r = (c.mode == 3)
                ? c.radius * (0.35F + c.progress * 0.90F)
                : (c.mode == 4 ? c.radius * (1.10F - c.progress * 0.75F) : c.radius);
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, r, c.ageTicks * 0.06F, 1.0F, 1.0F, 1.0F, 0.85F * fade);
    }

    /** 日·幻日虹：在玩家闪身原地耸立起燃烧扭曲的炽炎残影火柱，如动漫中的阳炎幻身！ */
    private static void renderSunMiragePillar(
            Matrix4fc mat, VertexConsumer vc, DeadCalmSnap dc, double camX, double camY, double camZ
    ) {
        float enter = Mth.clamp(dc.progress * 6.0F, 0.0F, 1.0F);
        float exit = 1.0F - Mth.clamp((dc.progress - 0.76F) / 0.24F, 0.0F, 1.0F);
        float scale = enter * exit;
        if (scale <= 0.05F) return;

        float cx = (float) (dc.x - camX), cy = (float) (dc.y - camY) + 0.08F, cz = (float) (dc.z - camZ);
        drawTexturedHelixRibbon(mat, vc, cx, cy, cz,
                0.82F * scale, 0.34F * scale, 2.85F * enter,
                2, 1.45F, dc.ageTicks * 0.32F, 0.62F * scale, -dc.ageTicks * 0.18F);
    }

    private static void renderChargeRibbons(
            Matrix4fc mat, VertexConsumer vc, ChargeAuraSnap c, double camX, double camY, double camZ
    ) {
        if (c.mode != 1 && c.mode != 3) return;
        float shrink = (1.0F - c.progress) * Mth.clamp(c.progress * 5.0F, 0.0F, 1.0F);
        if (shrink <= 0.05F) return;
        float cx = (float) (c.x - camX), cy = (float) (c.y - camY) + 0.1F, cz = (float) (c.z - camZ);
        drawTexturedHelixRibbon(mat, vc, cx, cy, cz,
                c.mode == 3 ? c.radius * 0.45F : c.radius * 0.90F,
                c.mode == 3 ? c.radius * 0.98F : 0.48F,
                3.4F, 2, 1.4F, c.ageTicks * 0.48F, 0.65F * shrink, -c.ageTicks * 0.16F);
    }

    private static void renderCelRiftSlash(
            Matrix4fc mat, VertexConsumer vc, RiftSlashSnap r, double camX, double camY, double camZ
    ) {
        float shrink = 1.0F - r.progress * r.progress;
        if (shrink <= 0.04F) return;
        float grow = Mth.clamp(r.progress * 5.2F, 0.30F, 1.12F);
        Vec3 dir = Vec3.directionFromRotation(r.pitchDeg, r.yawDeg).normalize();
        Vec3[] basis = orthoBasis(dir);
        float cx = (float) (r.x - camX), cy = (float) (r.y - camY), cz = (float) (r.z - camZ);
        float halfLen = r.length * grow;
        float wInk = (r.heavy ? 0.18F : 0.11F) * shrink;
        float wCore = wInk * 0.56F;

        float cr = r.sunStyle ? 1.00F : 0.96F;
        float cg = r.sunStyle ? 0.84F : 0.99F;
        float cb = r.sunStyle ? 0.34F : 1.00F;

        for (int p = 0; p < 2; p++) {
            Vec3 n = basis[p];
            float dx = (float) dir.x * halfLen, dy = (float) dir.y * halfLen, dz = (float) dir.z * halfLen;
            float ix = (float) n.x * wInk, iy = (float) n.y * wInk, iz = (float) n.z * wInk;
            emitColorQuad(mat, vc,
                    cx - dx, cy - dy, cz - dz, 0.05F, 0.03F, 0.04F, 0.96F * shrink,
                    cx + ix, cy + iy, cz + iz, 0.05F, 0.03F, 0.04F, 0.96F * shrink,
                    cx + dx, cy + dy, cz + dz, 0.05F, 0.03F, 0.04F, 0.96F * shrink,
                    cx - ix, cy - iy, cz - iz, 0.05F, 0.03F, 0.04F, 0.96F * shrink);

            float wx = (float) n.x * wCore, wy = (float) n.y * wCore, wz = (float) n.z * wCore;
            for (int sideSign = -1; sideSign <= 1; sideSign += 2) {
                float ox = (float) basis[1 - p].x * 0.014F * sideSign;
                float oy = (float) basis[1 - p].y * 0.014F * sideSign;
                float oz = (float) basis[1 - p].z * 0.014F * sideSign;
                emitColorQuad(mat, vc,
                        cx - dx * 0.92F + ox, cy - dy * 0.92F + oy, cz - dz * 0.92F + oz, cr, cg, cb, 1.0F * shrink,
                        cx + wx + ox, cy + wy + oy, cz + wz + oz, cr, cg, cb, 1.0F * shrink,
                        cx + dx * 0.92F + ox, cy + dy * 0.92F + oy, cz + dz * 0.92F + oz, cr, cg, cb, 1.0F * shrink,
                        cx - wx + ox, cy - wy + oy, cz - wz + oz, cr, cg, cb, 1.0F * shrink);
            }
        }
    }

    // ==================== 6. 电影级柔和 Additive 体积辉光与锐利斩锋核心 ====================

    private static void renderBladeTrailGlow(
            Matrix4fc mat, VertexConsumer vc, BladeTrailSnap tr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        if (!tr.sunStyle) return;
        int n = tr.bases.size();
        if (n < 2) return;
        final double maxLife = WaterVfxManager.LiveBladeTrail.MAX_LIFE_TICKS;
        float uvScroll = -(float) ((tr.renderTime * 0.18) % 1000.0);
        float gr = 1.00F, gg = 0.36F, gb = 0.05F, baseAlpha = 0.28F;

        for (int i = 0; i < n - 1; i++) {
            Vec3 b0 = tr.bases.get(i), t0 = tr.tips.get(i);
            Vec3 b1 = tr.bases.get(i + 1), t1 = tr.tips.get(i + 1);
            if (t0.distanceTo(t1) > 3.6) continue;

            float age0 = (float) Math.max(0.0, tr.renderTime - tr.birthTimes.get(i));
            float age1 = (float) Math.max(0.0, tr.renderTime - tr.birthTimes.get(i + 1));
            float life0 = Mth.clamp(age0 / (float) maxLife, 0.0F, 1.0F);
            float life1 = Mth.clamp(age1 / (float) maxLife, 0.0F, 1.0F);
            float wake0 = computeWakeExpand(age0, tr.rushFlags.get(i));
            float wake1 = computeWakeExpand(age1, tr.rushFlags.get(i + 1));
            float fade0 = (float) Math.pow(1.0F - life0, 0.85) * wake0;
            float fade1 = (float) Math.pow(1.0F - life1, 0.85) * wake1;
            float a = baseAlpha * (fade0 + fade1) * 0.5F;
            if (firstPerson && tr.isLocalPlayer) {
                Vec3 mid = b0.lerp(t0, 0.55);
                a *= computeFirstPersonClearFactor(mid.x - camX, mid.y - camY, mid.z - camZ, camForward, 2.3, 0.62, 0.12F);
            }
            if (a <= 0.015F) continue;

            Vec3 gb0 = b0.lerp(t0, 0.16);
            Vec3 gt0 = b0.lerp(t0, 0.88);
            Vec3 gb1 = b1.lerp(t1, 0.16);
            Vec3 gt1 = b1.lerp(t1, 0.88);
            float u0 = -tr.arcDists.get(i) * 0.24F + uvScroll;
            float u1 = -tr.arcDists.get(i + 1) * 0.24F + uvScroll;

            emitTexQuad(mat, vc,
                    (float) (gb0.x - camX), (float) (gb0.y - camY), (float) (gb0.z - camZ), u0, 0.0F,
                    (float) (gt0.x - camX), (float) (gt0.y - camY), (float) (gt0.z - camZ), u0, 1.0F,
                    (float) (gt1.x - camX), (float) (gt1.y - camY), (float) (gt1.z - camZ), u1, 1.0F,
                    (float) (gb1.x - camX), (float) (gb1.y - camY), (float) (gb1.z - camZ), u1, 0.0F,
                    gr, gg, gb, a);
        }
    }

    private static void renderDragonGlowAura(
            Matrix4fc mat, VertexConsumer vc, DragonSnap dr, double camX, double camY, double camZ,
            boolean firstPerson, Vec3 camForward
    ) {
        float shrink = 1.0F - Mth.clamp((dr.lifeNorm - 0.70F) / 0.30F, 0.0F, 1.0F);
        if (shrink <= 0.05F) return;
        float bodyScale = (0.86F + dr.tier * 0.10F) * shrink * dr.scaleMult;
        Vec3[] spine = dr.spine;
        int n = spine.length;
        Vec3[][] frames = computeSpineFrames(spine);
        float uvScroll = -dr.ageTicks * 0.24F;
        float gr = dr.sunStyle ? 1.00F : 0.24F;
        float gg = dr.sunStyle ? 0.42F : 0.80F;
        float gb = dr.sunStyle ? 0.06F : 1.00F;
        float alpha = (dr.sunStyle ? 0.28F : 0.13F) * shrink;

        // 贴合修长龙体的轻盈流光外晕（大幅收窄，绝不糊掉精致的 1024px 细龙鳞！）
        for (int plane = 0; plane < 2; plane++) {
            for (int i = 0; i < n - 1; i++) {
                float u0 = i / (float) Math.max(1, n - 1);
                float u1 = (i + 1) / (float) Math.max(1, n - 1);
                float c0 = (firstPerson && dr.isLocalPlayer)
                        ? computeFirstPersonClearFactor(spine[i].x - camX, spine[i].y - camY, spine[i].z - camZ, camForward, 2.5, 0.55, 0.10F) : 1.0F;
                float c1 = (firstPerson && dr.isLocalPlayer)
                        ? computeFirstPersonClearFactor(spine[i + 1].x - camX, spine[i + 1].y - camY, spine[i + 1].z - camZ, camForward, 2.5, 0.55, 0.10F) : 1.0F;
                float w0 = 0.72F * bodyScale * dragonRadiusProfile(u0) * c0;
                float w1 = 0.72F * bodyScale * dragonRadiusProfile(u1) * c1;
                Vec3 axis0 = frames[plane][i], axis1 = frames[plane][i + 1];

                Vec3 p0L = spine[i].subtract(axis0.scale(w0)), p0R = spine[i].add(axis0.scale(w0));
                Vec3 p1L = spine[i + 1].subtract(axis1.scale(w1)), p1R = spine[i + 1].add(axis1.scale(w1));
                float tu0 = u0 * 3.5F + uvScroll, tu1 = u1 * 3.5F + uvScroll;
                emitTexQuad(mat, vc,
                        (float) (p0L.x - camX), (float) (p0L.y - camY), (float) (p0L.z - camZ), tu0, 0.0F,
                        (float) (p0R.x - camX), (float) (p0R.y - camY), (float) (p0R.z - camZ), tu0, 1.0F,
                        (float) (p1R.x - camX), (float) (p1R.y - camY), (float) (p1R.z - camZ), tu1, 1.0F,
                        (float) (p1L.x - camX), (float) (p1L.y - camY), (float) (p1L.z - camZ), tu1, 0.0F,
                        gr, gg, gb, alpha * Math.min(c0, c1));
            }
        }

        // 日呼专属彩蛋：「日晕之龙」颈后旋转炽金日轮光晕（点题原版太阳光环意象！）
        if (dr.sunStyle && n >= 3) {
            Vec3 haloCenter = spine[1];
            float hClear = (firstPerson && dr.isLocalPlayer)
                    ? computeFirstPersonClearFactor(haloCenter.x - camX, haloCenter.y - camY, haloCenter.z - camZ, camForward, 2.4, 0.55, 0.0F) : 1.0F;
            if (hClear > 0.1F) {
                Vec3 rAxis = frames[0][1], uAxis = frames[1][1];
                float rIn = 0.62F * bodyScale * hClear;
                float rOut = 1.18F * bodyScale * hClear;
                int segs = 16;
                float rot = dr.ageTicks * 0.12F;
                float ha = 0.42F * shrink * hClear;
                for (int i = 0; i < segs; i++) {
                    float a0 = (Mth.TWO_PI * i) / segs + rot;
                    float a1 = (Mth.TWO_PI * (i + 1)) / segs + rot;
                    Vec3 d0 = rAxis.scale(Mth.cos(a0)).add(uAxis.scale(Mth.sin(a0)));
                    Vec3 d1 = rAxis.scale(Mth.cos(a1)).add(uAxis.scale(Mth.sin(a1)));
                    Vec3 p0I = haloCenter.add(d0.scale(rIn)), p0O = haloCenter.add(d0.scale(rOut));
                    Vec3 p1I = haloCenter.add(d1.scale(rIn)), p1O = haloCenter.add(d1.scale(rOut));
                    emitTexQuad(mat, vc,
                            (float) (p0I.x - camX), (float) (p0I.y - camY), (float) (p0I.z - camZ), i * 0.25F, 0.0F,
                            (float) (p0O.x - camX), (float) (p0O.y - camY), (float) (p0O.z - camZ), i * 0.25F, 1.0F,
                            (float) (p1O.x - camX), (float) (p1O.y - camY), (float) (p1O.z - camZ), (i + 1) * 0.25F, 1.0F,
                            (float) (p1I.x - camX), (float) (p1I.y - camY), (float) (p1I.z - camZ), (i + 1) * 0.25F, 0.0F,
                            1.00F, 0.72F, 0.16F, ha);
                }
            }
        }
    }

    // ==================== 4B. 思路 2 人刀龙一体落点终结 · 干净贴地冲击浪环与神龙图腾 ====================

    /**
     * Pass 2A/2B：彻底零柱子、零脏泥沟！仅保留从剑士刀锋落点贴地疾扫而出的 2 道精致浮世绘海浪/烈焰冲击环！
     */
    private static void renderSunFinaleEruption(
            Matrix4fc mat, VertexConsumer vc, SunFinaleSnap sf, double camX, double camY, double camZ
    ) {
        float ageT = sf.ageTicks;
        if (ageT >= 22.0F) return;
        float shrink = 1.0F - Mth.clamp((ageT - 10.0F) / 12.0F, 0.0F, 1.0F);
        if (shrink <= 0.04F) return;

        float cx = (float) (sf.x - camX), cy = (float) (sf.y - camY), cz = (float) (sf.z - camZ);
        for (int rIdx = 0; rIdx < 2; rIdx++) {
            float rp = Mth.clamp((ageT - rIdx * 2.2F) / 14.0F, 0.0F, 1.0F);
            if (rp <= 0.02F || rp >= 0.96F) continue;
            float rOut = sf.radius * (0.28F + rp * (1.05F + rIdx * 0.18F));
            float bandW = (1.45F - rp * 0.95F) * shrink;
            float rIn = Math.max(0.35F, rOut - bandW);
            drawTexturedGroundRing(mat, vc, cx, cy + 0.14F + rIdx * 0.14F, cz,
                    rIn, rOut, (0.56F + rIdx * 0.16F) * (1.0F - rp),
                    ageT * 0.22F * (rIdx == 0 ? 1.0F : -1.0F), -ageT * 0.16F);
        }
    }

    /**
     * Pass 5A：生生流转收尾高压水爆扩散水纹（短促清澈、绝不残留奇怪发光地面魔法阵！）
     */
    private static void renderWaterFinaleGroundRing(
            Matrix4fc mat, VertexConsumer vc, SunFinaleSnap sf, double camX, double camY, double camZ
    ) {
        float ageT = sf.ageTicks;
        if (ageT >= 16.0F) return;
        float rp = ageT / 16.0F;
        float ease = 1.0F - (1.0F - rp) * (1.0F - rp);
        float alpha = (1.0F - rp) * (1.0F - rp) * 0.78F;
        if (alpha <= 0.02F) return;

        float cx = (float) (sf.x - camX), cy = (float) (sf.y - camY) + 0.06F, cz = (float) (sf.z - camZ);
        float mainR = sf.radius * (0.32F + ease * 0.95F);
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, mainR, ageT * 0.04F, 0.90F, 0.98F, 1.0F, alpha);
    }

    /**
     * Pass 5A：3D 定向同心水波纹冲击环（柒之型·雫波纹突刺垂直水纹 / 贴地水压爆裂环 / 伍之型·慈雨细涟漪！）
     */
    private static void renderRippleRing(
            Matrix4fc mat, VertexConsumer vc, RippleRingSnap rr, double camX, double camY, double camZ
    ) {
        float cx = (float) (rr.x - camX), cy = (float) (rr.y - camY), cz = (float) (rr.z - camZ);
        if (rr.mode == 0) {
            // 柒之型·雫波纹突刺：垂直于刺击矢量展开的 3 层立体同心水滴波纹！
            Vec3 normal = Vec3.directionFromRotation(rr.pitchDeg, rr.yawDeg).normalize();
            Vec3[] basis = orthoBasis(normal);
            Vec3 uAxis = basis[0], vAxis = basis[1];
            for (int ring = 0; ring < 3; ring++) {
                float localP = Mth.clamp((rr.progress - ring * 0.12F) / (1.0F - ring * 0.12F), 0.0F, 1.0F);
                if (localP <= 0.01F || localP >= 0.99F) continue;
                float ease = 1.0F - (1.0F - localP) * (1.0F - localP);
                float rad = rr.maxRadius * (0.14F + ease * (0.86F - ring * 0.14F));
                float alpha = (1.0F - localP) * (ring == 0 ? 0.92F : 0.72F);
                float offAlong = ring * 0.42F * (0.4F + ease * 0.6F);
                float ox = cx - (float) normal.x * offAlong;
                float oy = cy - (float) normal.y * offAlong;
                float oz = cz - (float) normal.z * offAlong;
                drawOrientedTexturedQuad(mat, vc, ox, oy, oz, uAxis, vAxis, rad,
                        rr.ageTicks * (0.06F + ring * 0.03F), 0.88F, 0.98F, 1.0F, alpha);
            }
        } else if (rr.mode == 1) {
            // 贴地水压激浪冲击波纹（泷壶 / 生生流转水爆 / 漩涡收尾 / 打潮拍岸 / 水流飞沫踏水）
            for (int ring = 0; ring < 2; ring++) {
                float localP = Mth.clamp((rr.progress - ring * 0.14F) / (1.0F - ring * 0.14F), 0.0F, 1.0F);
                if (localP <= 0.01F || localP >= 0.99F) continue;
                float ease = 1.0F - (1.0F - localP) * (1.0F - localP);
                float rad = rr.maxRadius * (0.22F + ease * (0.86F - ring * 0.16F));
                float alpha = (1.0F - localP) * (1.0F - localP) * (ring == 0 ? 0.88F : 0.65F);
                drawHorizontalTexturedQuad(mat, vc, cx, cy + 0.06F + ring * 0.04F, cz, rad,
                        rr.ageTicks * (ring == 0 ? 0.05F : -0.04F), 0.88F, 0.98F, 1.0F, alpha);
            }
        } else {
            // 伍之型·乾天的慈雨：柔和银青浮空同心细雨涟漪
            float ease = 1.0F - (1.0F - rr.progress) * (1.0F - rr.progress);
            float alpha = Mth.sin(rr.progress * Mth.PI) * 0.82F;
            float rad = rr.maxRadius * (0.28F + ease * 0.72F);
            drawHorizontalTexturedQuad(mat, vc, cx, cy + 0.08F, cz, rad, rr.ageTicks * 0.025F, 0.94F, 0.99F, 1.0F, alpha);
        }
    }

    /** Pass 7：为柒之型·雫波纹突刺与水压冲击环叠加极锐利的一闪清亮水光环缘！ */
    private static void renderRippleRingHighlight(
            Matrix4fc mat, VertexConsumer vc, RippleRingSnap rr, double camX, double camY, double camZ
    ) {
        if (rr.progress >= 0.88F) return;
        float ease = 1.0F - (1.0F - rr.progress) * (1.0F - rr.progress);
        float alpha = (1.0F - rr.progress / 0.88F) * (rr.mode == 0 ? 0.88F : 0.56F);
        if (alpha <= 0.03F) return;

        float cx = (float) (rr.x - camX), cy = (float) (rr.y - camY), cz = (float) (rr.z - camZ);
        Vec3 uAxis, vAxis;
        if (rr.mode == 0) {
            Vec3 normal = Vec3.directionFromRotation(rr.pitchDeg, rr.yawDeg).normalize();
            Vec3[] basis = orthoBasis(normal);
            uAxis = basis[0];
            vAxis = basis[1];
        } else {
            uAxis = new Vec3(1.0, 0.0, 0.0);
            vAxis = new Vec3(0.0, 0.0, 1.0);
            cy += 0.10F;
        }
        float rMid = rr.maxRadius * (0.18F + ease * 0.82F);
        float halfW = (rr.mode == 0 ? 0.11F : 0.15F) * (1.0F - rr.progress * 0.65F);
        float rIn = Math.max(0.06F, rMid - halfW);
        float rOut = rMid + halfW;
        int segs = 24;
        for (int i = 0; i < segs; i++) {
            float a0 = (Mth.TWO_PI * i) / segs;
            float a1 = (Mth.TWO_PI * (i + 1)) / segs;
            Vec3 d0 = uAxis.scale(Mth.cos(a0)).add(vAxis.scale(Mth.sin(a0)));
            Vec3 d1 = uAxis.scale(Mth.cos(a1)).add(vAxis.scale(Mth.sin(a1)));
            emitColorQuad(mat, vc,
                    cx + (float) d0.x * rIn,  cy + (float) d0.y * rIn,  cz + (float) d0.z * rIn,  0.45F, 0.90F, 1.0F, alpha * 0.25F,
                    cx + (float) d0.x * rOut, cy + (float) d0.y * rOut, cz + (float) d0.z * rOut, 0.94F, 0.99F, 1.0F, alpha,
                    cx + (float) d1.x * rOut, cy + (float) d1.y * rOut, cz + (float) d1.z * rOut, 0.94F, 0.99F, 1.0F, alpha,
                    cx + (float) d1.x * rIn,  cy + (float) d1.y * rIn,  cz + (float) d1.z * rIn,  0.45F, 0.90F, 1.0F, alpha * 0.25F);
        }
    }

    private static void drawOrientedTexturedQuad(
            Matrix4fc mat, VertexConsumer vc, float cx, float cy, float cz,
            Vec3 uAxis, Vec3 vAxis, float radius, float rotRad,
            float r, float g, float b, float a
    ) {
        float c = Mth.cos(rotRad) * radius, s = Mth.sin(rotRad) * radius;
        float ux = (float) (uAxis.x * c + vAxis.x * s), uy = (float) (uAxis.y * c + vAxis.y * s), uz = (float) (uAxis.z * c + vAxis.z * s);
        float vx = (float) (vAxis.x * c - uAxis.x * s), vy = (float) (vAxis.y * c - uAxis.y * s), vz = (float) (vAxis.z * c - uAxis.z * s);
        emitTexQuad(mat, vc,
                cx - ux - vx, cy - uy - vy, cz - uz - vz, 0.0F, 1.0F,
                cx + ux - vx, cy + uy - vy, cz + uz - vz, 1.0F, 1.0F,
                cx + ux + vx, cy + uy + vy, cz + uz + vz, 1.0F, 0.0F,
                cx - ux + vx, cy - uy + vy, cz - uz + vz, 0.0F, 0.0F,
                r, g, b, a);
    }

    /**
     * Pass 5C：日之呼吸落点 焦土熔岩神龙图腾（刀劈瞬间铺满落点，脉动余烬！）
     */
    private static void renderSunFinaleCraterTotem(
            Matrix4fc mat, VertexConsumer vc, SunFinaleSnap sf, double camX, double camY, double camZ
    ) {
        float ageT = sf.ageTicks;
        float openP = Mth.clamp(ageT / 4.2F, 0.0F, 1.0F);
        float easeOut = 1.0F - (1.0F - openP) * (1.0F - openP);
        float radius = sf.radius * Mth.lerp(easeOut, 0.42F, 1.32F);
        float fadeStart = Math.max(34.0F, sf.maxAge - 18.0F);
        float fadeOut = 1.0F - Mth.clamp((ageT - fadeStart) / Math.max(1.0F, sf.maxAge - fadeStart), 0.0F, 1.0F);
        float alpha = fadeOut * fadeOut;
        if (alpha <= 0.02F) return;

        float pulse = ageT >= 4.2F ? (0.91F + 0.09F * Mth.sin(ageT * 0.28F)) : 1.0F;
        float cx = (float) (sf.x - camX), cy = (float) (sf.y - camY) + 0.055F, cz = (float) (sf.z - camZ);
        float baseRot = sf.yawDeg * Mth.DEG_TO_RAD;
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, radius, baseRot,
                pulse, pulse * 0.96F, pulse * 0.90F, 0.96F * alpha);
    }

    /**
     * Pass 5B：落点 12 芒炽金旋转日轮圣纹
     */
    private static void renderSunFinaleInnerHalo(
            Matrix4fc mat, VertexConsumer vc, SunFinaleSnap sf, double camX, double camY, double camZ
    ) {
        float ageT = sf.ageTicks;
        float fadeStart = Math.max(28.0F, sf.maxAge - 20.0F);
        float fadeOut = 1.0F - Mth.clamp((ageT - fadeStart) / Math.max(1.0F, sf.maxAge - fadeStart - 2.0F), 0.0F, 1.0F);
        if (fadeOut <= 0.02F) return;

        float cx = (float) (sf.x - camX), cy = (float) (sf.y - camY) + 0.095F, cz = (float) (sf.z - camZ);
        float p = Mth.clamp(ageT / 4.5F, 0.0F, 1.0F);
        float ease = 1.0F - (1.0F - p) * (1.0F - p);
        float mainR = sf.radius * Mth.lerp(ease, 0.36F, 0.86F);
        float rot = ageT * 0.032F;
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, mainR, rot, 1.0F, 0.95F, 0.84F, 0.86F * fadeOut);

        if (ageT < 18.0F) {
            float rp = ageT / 18.0F;
            float echoR = sf.radius * (0.45F + rp * 0.92F);
            float echoA = Mth.sin(rp * Mth.PI) * 0.58F;
            drawHorizontalTexturedQuad(mat, vc, cx, cy + 0.025F, cz, echoR, -rot * 1.4F, 1.0F, 0.88F, 0.65F, echoA);
        }
    }

    /**
     * Pass 6：落点极克制柔和地光（彻底 0 柱子、0 遮挡！）
     */
    private static void renderSunFinaleGlow(
            Matrix4fc mat, VertexConsumer vc, SunFinaleSnap sf, double camX, double camY, double camZ
    ) {
        float ageT = sf.ageTicks;
        if (ageT >= 16.0F) return;
        float p = ageT / 16.0F;
        float fade = (1.0F - p) * (1.0F - p);
        if (fade <= 0.03F) return;
        float cx = (float) (sf.x - camX), cy = (float) (sf.y - camY) + 0.14F, cz = (float) (sf.z - camZ);
        float r = sf.radius * (0.35F + p * 0.72F);
        float gr = sf.sunStyle ? 1.00F : 0.24F;
        float gg = sf.sunStyle ? 0.48F : 0.82F;
        float gb = sf.sunStyle ? 0.10F : 1.00F;
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, r, ageT * 0.08F, gr, gg, gb, (sf.sunStyle ? 0.34F : 0.22F) * fade);
    }

    private static void renderWaterfallGlow(
            Matrix4fc mat, VertexConsumer vc, WaterfallSnap wf, double camX, double camY, double camZ
    ) {
        // 捌之型·泷壶：仅保留落点贴地柔和清冽水光，彻底取消向上喷射的垂直光柱！
        float fade = 1.0F - Mth.clamp(wf.progress / 0.72F, 0.0F, 1.0F);
        if (fade <= 0.03F) return;
        float cx = (float) (wf.x - camX), cy = (float) (wf.y - camY) + 0.14F, cz = (float) (wf.z - camZ);
        float r = wf.radius * (0.35F + wf.progress * 0.78F);
        drawHorizontalTexturedQuad(mat, vc, cx, cy, cz, r, wf.ageTicks * 0.06F, 0.28F, 0.82F, 1.00F, 0.22F * fade);
    }

    private static void renderWhirlpoolGlow(
            Matrix4fc mat, VertexConsumer vc, WhirlpoolSnap wp, double camX, double camY, double camZ
    ) {
        float fade = (wp.progress < 0.16F ? wp.progress / 0.16F : 1.0F)
                * (1.0F - Mth.clamp((wp.progress - 0.62F) / 0.38F, 0.0F, 1.0F));
        if (fade <= 0.03F) return;
        float cx = (float) (wp.x - camX), cy = (float) (wp.y - camY) + 0.15F, cz = (float) (wp.z - camZ);
        float gr = wp.sunStyle ? 1.00F : 0.24F;
        float gg = wp.sunStyle ? 0.40F : 0.76F;
        float gb = wp.sunStyle ? 0.07F : 1.00F;
        float a = (wp.sunStyle ? 0.42F : 0.26F) * fade;
        int segs = 24;
        float rBot = wp.radius * 0.68F, rTop = wp.radius * 1.24F;
        float uvScroll = -wp.ageTicks * 0.24F;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs, t1 = (i + 1) / (float) segs;
            float a0 = t0 * Mth.TWO_PI * 1.8F + wp.ageTicks * 0.40F;
            float a1 = t1 * Mth.TWO_PI * 1.8F + wp.ageTicks * 0.40F;
            float rad0 = Mth.lerp(t0, rBot, rTop), rad1 = Mth.lerp(t1, rBot, rTop);
            float y0 = cy + t0 * wp.height, y1 = cy + t1 * wp.height;
            float bh = 1.45F * fade;
            emitTexQuad(mat, vc,
                    cx + Mth.cos(a0) * rad0, y0, cz + Mth.sin(a0) * rad0, t0 * 2.5F + uvScroll, 0.0F,
                    cx + Mth.cos(a0) * rad0, y0 + bh, cz + Mth.sin(a0) * rad0, t0 * 2.5F + uvScroll, 1.0F,
                    cx + Mth.cos(a1) * rad1, y1 + bh, cz + Mth.sin(a1) * rad1, t1 * 2.5F + uvScroll, 1.0F,
                    cx + Mth.cos(a1) * rad1, y1, cz + Mth.sin(a1) * rad1, t1 * 2.5F + uvScroll, 0.0F,
                    gr, gg, gb, a);
        }
    }

    /** Pass 7A: 「刀先破空」极细锐利寒光/赫金一闪刃（0 tick 瞬间切开空间，不遮视野且极具速度锐度！） */
    private static void renderBladeCoreEdge(
            Matrix4fc mat, VertexConsumer vc, BladeTrailSnap tr, double camX, double camY, double camZ
    ) {
        int n = tr.bases.size();
        if (n < 2) return;
        final double maxLife = WaterVfxManager.LiveBladeTrail.MAX_LIFE_TICKS;
        float r = tr.sunStyle ? 1.00F : 0.92F;
        float g = tr.sunStyle ? 0.95F : 0.99F;
        float b = tr.sunStyle ? 0.68F : 1.00F;

        for (int i = 0; i < n - 1; i++) {
            Vec3 b0 = tr.bases.get(i), t0 = tr.tips.get(i);
            Vec3 b1 = tr.bases.get(i + 1), t1 = tr.tips.get(i + 1);
            if (t0.distanceTo(t1) > 3.6) continue;

            float age0 = (float) Math.max(0.0, tr.renderTime - tr.birthTimes.get(i));
            float age1 = (float) Math.max(0.0, tr.renderTime - tr.birthTimes.get(i + 1));
            float life0 = Mth.clamp(age0 / (float) maxLife, 0.0F, 1.0F);
            float life1 = Mth.clamp(age1 / (float) maxLife, 0.0F, 1.0F);
            // 前 2.2 tick 极度清脆高亮，随后顺滑融入后方澎湃展开的浮世绘海浪/烈焰！
            float fresh0 = age0 < 2.2F ? (0.90F - age0 * 0.08F) : Mth.clamp(1.0F - life0 * 1.75F, 0.0F, 1.0F) * 0.70F;
            float fresh1 = age1 < 2.2F ? (0.90F - age1 * 0.08F) : Mth.clamp(1.0F - life1 * 1.75F, 0.0F, 1.0F) * 0.70F;
            if (fresh0 <= 0.02F && fresh1 <= 0.02F) continue;

            Vec3 in0 = t0.lerp(b0, 0.075);
            Vec3 out0 = t0.lerp(b0, -0.022);
            Vec3 in1 = t1.lerp(b1, 0.075);
            Vec3 out1 = t1.lerp(b1, -0.022);

            emitColorQuad(mat, vc,
                    (float) (in0.x - camX), (float) (in0.y - camY), (float) (in0.z - camZ), r, g, b, fresh0 * 0.14F,
                    (float) (out0.x - camX), (float) (out0.y - camY), (float) (out0.z - camZ), r, g, b, fresh0,
                    (float) (out1.x - camX), (float) (out1.y - camY), (float) (out1.z - camZ), r, g, b, fresh1,
                    (float) (in1.x - camX), (float) (in1.y - camY), (float) (in1.z - camZ), r, g, b, fresh1 * 0.14F);
        }
    }

    /** Pass 7B: 方向拉伸发光细水针 / 炽热上升火星雨 */
    private static void renderVelocityStreak(
            Matrix4fc mat, VertexConsumer vc, StreakSnap st, double camX, double camY, double camZ
    ) {
        float fade = 1.0F - st.progress * st.progress;
        if (fade <= 0.04F) return;

        Vec3 vel = new Vec3(st.vx, st.vy, st.vz);
        double spd = vel.length();
        Vec3 dir = spd > 1.0E-4 ? vel.scale(1.0 / spd) : new Vec3(0, 1, 0);
        Vec3[] basis = orthoBasis(dir);

        float len = (float) Mth.clamp(spd * 2.15 + 0.14, 0.16, 1.65) * st.lengthScale * (0.45F + 0.55F * fade);
        float w = st.widthScale * fade;

        float hx = (float) (st.x - camX), hy = (float) (st.y - camY), hz = (float) (st.z - camZ);
        float tx = hx - (float) dir.x * len, ty = hy - (float) dir.y * len, tz = hz - (float) dir.z * len;
        float mx = (hx + tx) * 0.5F, my = (hy + ty) * 0.5F, mz = (hz + tz) * 0.5F;

        float r, g, b;
        if (st.sunStyle) {
            if (st.hotWhite) { r = 1.00F; g = 0.96F; b = 0.72F; }
            else { r = 1.00F; g = Mth.lerp(st.progress, 0.68F, 0.24F); b = 0.08F; }
        } else {
            if (st.hotWhite) { r = 0.94F; g = 0.99F; b = 1.00F; }
            else { r = 0.36F; g = 0.84F; b = 1.00F; }
        }
        float aHead = 0.92F * fade;

        // 双交叉尖菱形针，任意视角都极度锐利精致！
        for (int p = 0; p < 2; p++) {
            Vec3 n = basis[p];
            float wx = (float) n.x * w, wy = (float) n.y * w, wz = (float) n.z * w;
            emitColorQuad(mat, vc,
                    hx, hy, hz, r, g, b, aHead,
                    mx + wx, my + wy, mz + wz, r, g, b, aHead * 0.65F,
                    tx, ty, tz, r, g, b, 0.0F,
                    mx - wx, my - wy, mz - wz, r, g, b, aHead * 0.65F);
        }
    }

    /**
     * Pass 7C：鬼灭核心战斗直觉 —— 活体 3D「隙之线 (Opening Thread)」
     * 从日轮刀刀锋优雅延展至敌怪颈部要害；拉近斩击距离时丝线逐渐绷直如弦，颈部光环收缩锁定！
     */
    private static void renderOpeningThread(
            Matrix4fc mat, VertexConsumer vc, OpeningThreadSnap th,
            double camX, double camY, double camZ, Vector3f camRight, Vector3f camUp
    ) {
        Vec3 start = th.firstPerson
                ? new Vec3(
                        camX + th.viewDir.x * 0.88 + camRight.x * 0.28 - camUp.x * 0.22,
                        camY + th.viewDir.y * 0.88 + camRight.y * 0.28 - camUp.y * 0.22,
                        camZ + th.viewDir.z * 0.88 + camRight.z * 0.28 - camUp.z * 0.22
                )
                : th.thirdPersonStart;
        Vec3 end = th.targetNeck;
        Vec3 span = end.subtract(start);
        double dist = span.length();
        if (dist < 0.35) return;

        Vec3 dir = span.scale(1.0 / dist);
        Vec3[] basis = orthoBasis(dir);
        Vec3 sideVec = basis[0];
        Vec3 upVec = new Vec3(0.0, 1.0, 0.0);

        float alpha = th.strength * (th.forcedHighlight ? 1.0F : 0.86F);
        float taut = th.tautness;
        float boost = th.forcedHighlight ? 1.28F : 1.0F;

        // 配色：水之呼吸呈冷冽银白带淡青光丝，日之呼吸呈炽白核心带琉璃金丝！
        float coreR = th.sunStyle ? 1.00F : 0.96F;
        float coreG = th.sunStyle ? 0.98F : 0.99F;
        float coreB = th.sunStyle ? 0.86F : 1.00F;
        float glowR = th.sunStyle ? 1.00F : 0.48F;
        float glowG = th.sunStyle ? 0.72F : 0.90F;
        float glowB = th.sunStyle ? 0.22F : 1.00F;

        float droop = (1.0F - taut * 0.86F) * (float) Math.min(0.62, dist * 0.055);
        float waveAmp = (1.0F - taut * 0.78F) * 0.055F;

        final int segs = 24;
        Vec3[] pts = new Vec3[segs + 1];
        for (int i = 0; i <= segs; i++) {
            float u = i / (float) segs;
            float env = Mth.sin(u * Mth.PI);
            float wave = Mth.sin(u * 13.0F - th.renderTime * 0.44F) * waveAmp * env;
            float lift = Mth.cos(u * 9.0F - th.renderTime * 0.32F) * waveAmp * 0.6F * env - droop * env;
            pts[i] = start.lerp(end, u).add(sideVec.scale(wave)).add(upVec.scale(lift));
        }

        // 绘制细丝内芯 + 温润外光丝（近处柔和隐入刀锋，接近颈部要害渐强闪耀！）
        for (int i = 0; i < segs; i++) {
            float u0 = i / (float) segs;
            float u1 = (i + 1) / (float) segs;
            float a0 = alpha * Mth.clamp(u0 * 4.5F, 0.12F, 1.0F) * (0.72F + 0.28F * taut);
            float a1 = alpha * Mth.clamp(u1 * 4.5F, 0.12F, 1.0F) * (0.72F + 0.28F * taut);
            float wCore = (0.013F + 0.005F * taut) * boost;
            float wGlow = (0.042F + 0.014F * taut) * boost;

            emitSilkSegment(mat, vc, pts[i], pts[i + 1], camX, camY, camZ,
                    wGlow, glowR, glowG, glowB, a0 * 0.38F, a1 * 0.38F);
            emitSilkSegment(mat, vc, pts[i], pts[i + 1], camX, camY, camZ,
                    wCore, coreR, coreG, coreB, a0 * 0.92F, a1 * 0.92F);
        }

        // 颈部要害锁点：旋转收缩细光环 + 精致四芒一闪星芒！
        float ex = (float) (end.x - camX), ey = (float) (end.y - camY), ez = (float) (end.z - camZ);
        float ringR = Mth.lerp(taut, 0.34F, 0.19F) * boost;
        float ringW = 0.022F * boost;
        int ringSegs = 12;
        float rot = th.renderTime * (th.forcedHighlight ? 0.14F : 0.08F);
        for (int i = 0; i < ringSegs; i++) {
            float a0 = (Mth.TWO_PI * i) / ringSegs + rot;
            float a1 = (Mth.TWO_PI * (i + 1)) / ringSegs + rot;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            float rIn = ringR - ringW, rOut = ringR + ringW;
            emitColorQuad(mat, vc,
                    ex + (camRight.x * c0 + camUp.x * s0) * rIn, ey + (camRight.y * c0 + camUp.y * s0) * rIn, ez + (camRight.z * c0 + camUp.z * s0) * rIn,
                    glowR, glowG, glowB, alpha * 0.72F,
                    ex + (camRight.x * c0 + camUp.x * s0) * rOut, ey + (camRight.y * c0 + camUp.y * s0) * rOut, ez + (camRight.z * c0 + camUp.z * s0) * rOut,
                    coreR, coreG, coreB, alpha * 0.92F,
                    ex + (camRight.x * c1 + camUp.x * s1) * rOut, ey + (camRight.y * c1 + camUp.y * s1) * rOut, ez + (camRight.z * c1 + camUp.z * s1) * rOut,
                    coreR, coreG, coreB, alpha * 0.92F,
                    ex + (camRight.x * c1 + camUp.x * s1) * rIn, ey + (camRight.y * c1 + camUp.y * s1) * rIn, ez + (camRight.z * c1 + camUp.z * s1) * rIn,
                    glowR, glowG, glowB, alpha * 0.72F);
        }

        // 中心锐利十字星芒（拉近进入斩杀距离时闪耀微脉！）
        float starLen = (0.36F + 0.16F * taut) * boost * (0.88F + 0.12F * Mth.sin(th.renderTime * 0.65F));
        float starW = 0.028F * boost;
        for (int axis = 0; axis < 2; axis++) {
            Vector3f mainA = (axis == 0) ? camRight : camUp;
            Vector3f perpA = (axis == 0) ? camUp : camRight;
            emitColorQuad(mat, vc,
                    ex - mainA.x * starLen, ey - mainA.y * starLen, ez - mainA.z * starLen, coreR, coreG, coreB, 0.0F,
                    ex + perpA.x * starW, ey + perpA.y * starW, ez + perpA.z * starW, coreR, coreG, coreB, alpha * 0.95F,
                    ex + mainA.x * starLen, ey + mainA.y * starLen, ez + mainA.z * starLen, coreR, coreG, coreB, 0.0F,
                    ex - perpA.x * starW, ey - perpA.y * starW, ez - perpA.z * starW, coreR, coreG, coreB, alpha * 0.95F);
        }
    }

    /** Pass 7D：「斩断隙之线」丝线瞬间从中点轰然绷断、向两侧弹卷消散的 3D 残芒！ */
    private static void renderThreadSnap(
            Matrix4fc mat, VertexConsumer vc, ThreadSnapData ts, double camX, double camY, double camZ
    ) {
        float fade = 1.0F - ts.progress * ts.progress;
        if (fade <= 0.03F) return;
        Vec3 span = ts.end.subtract(ts.start);
        double dist = span.length();
        if (dist < 0.25) return;
        Vec3 dir = span.scale(1.0 / dist);
        Vec3[] basis = orthoBasis(dir);
        Vec3 side = basis[0], up = basis[1];
        Vec3 mid = ts.start.lerp(ts.end, 0.5);

        float cr = ts.sunStyle ? 1.00F : 0.95F;
        float cg = ts.sunStyle ? 0.92F : 0.99F;
        float cb = ts.sunStyle ? 0.62F : 1.00F;

        float recoil = ts.progress * ts.progress * 0.42F;
        for (int half = 0; half < 2; half++) {
            Vec3 anchor = (half == 0) ? ts.start : ts.end;
            Vec3 snappedTip = mid.lerp(anchor, recoil)
                    .add(side.scale((half == 0 ? -1.0 : 1.0) * ts.progress * 0.55))
                    .add(up.scale(ts.progress * 0.40));
            int segs = 10;
            Vec3 prev = anchor;
            for (int i = 1; i <= segs; i++) {
                float u = i / (float) segs;
                float whip = Mth.sin(u * Mth.PI * 1.5F) * ts.progress * 0.24F * (half == 0 ? 1.0F : -1.0F);
                Vec3 cur = anchor.lerp(snappedTip, u).add(side.scale(whip)).add(up.scale(Mth.sin(u * Mth.PI) * ts.progress * 0.20));
                emitSilkSegment(mat, vc, prev, cur, camX, camY, camZ,
                        0.026F * fade, cr, cg, cb, fade * 0.95F, fade * (1.0F - u * 0.45F));
                prev = cur;
            }
        }
    }

    private static void emitSilkSegment(
            Matrix4fc mat, VertexConsumer vc, Vec3 a, Vec3 b, double camX, double camY, double camZ,
            float halfW, float r, float g, float bl, float a0, float a1
    ) {
        Vec3 d = b.subtract(a);
        if (d.lengthSqr() < 1.0E-6) return;
        Vec3[] basis = orthoBasis(d.normalize());
        float ax = (float) (a.x - camX), ay = (float) (a.y - camY), az = (float) (a.z - camZ);
        float bx = (float) (b.x - camX), by = (float) (b.y - camY), bz = (float) (b.z - camZ);
        for (int p = 0; p < 2; p++) {
            Vec3 n = basis[p];
            float wx = (float) n.x * halfW, wy = (float) n.y * halfW, wz = (float) n.z * halfW;
            emitColorQuad(mat, vc,
                    ax - wx, ay - wy, az - wz, r, g, bl, a0,
                    ax + wx, ay + wy, az + wz, r, g, bl, a0,
                    bx + wx, by + wy, bz + wz, r, g, bl, a1,
                    bx - wx, by - wy, bz - wz, r, g, bl, a1);
        }
    }

    // ==================== 通用几何与方块状态工具 ====================

    private static void drawHorizontalTexturedQuad(
            Matrix4fc mat, VertexConsumer vc, float cx, float cy, float cz, float radius, float rotRad,
            float r, float g, float b, float a
    ) {
        float c = Mth.cos(rotRad) * radius, s = Mth.sin(rotRad) * radius;
        emitTexQuad(mat, vc,
                cx - c - s, cy, cz - s + c, 0.0F, 1.0F,
                cx + c - s, cy, cz + s + c, 1.0F, 1.0F,
                cx + c + s, cy, cz + s - c, 1.0F, 0.0F,
                cx - c + s, cy, cz - s - c, 0.0F, 0.0F,
                r, g, b, a);
    }

    /**
     * 真·3D 零 Z-Fighting 赛璐璐墨线描边棱柱（用于鹿角分叉龙角 / 6段飘舞龙须 / 獠牙 / 颈鬃 / 东方龙四爪）：
     * - 4 棱锥柱完全封闭 3D 体积，绝无交叉平面接缝！
     * - 每个面按 0..0.17（墨黑描边）| 0.17..0.83（二分赛璐璐明暗色）| 0.83..1.0（墨黑描边）非重叠拼合，
     *   数学上 100% 杜绝深度缓冲 Z-Fighting，任意角度都是通透干净的日漫赛璐璐手绘立体造型！
     */
    private static void drawCelOutlinedBeam(
            Matrix4fc mat, VertexConsumer vc, Vec3 a, Vec3 b, double camX, double camY, double camZ,
            float wA, float wB, float cr, float cg, float cb
    ) {
        Vec3 diff = b.subtract(a);
        if (diff.lengthSqr() < 1.0E-5) return;
        Vec3[] basis = orthoBasis(diff.normalize());
        Vec3 u = basis[0], v = basis[1];
        float ax = (float) (a.x - camX), ay = (float) (a.y - camY), az = (float) (a.z - camZ);
        float bx = (float) (b.x - camX), by = (float) (b.y - camY), bz = (float) (b.z - camZ);

        float[] cx = { (float) u.x, (float) v.x, -(float) u.x, -(float) v.x };
        float[] cy = { (float) u.y, (float) v.y, -(float) u.y, -(float) v.y };
        float[] cz = { (float) u.z, (float) v.z, -(float) u.z, -(float) v.z };
        float[] faceShade = { 1.00F, 0.96F, 0.80F, 0.86F };
        final float rim = 0.17F;
        final float ir = 0.04F, ig = 0.05F, ib = 0.09F;

        for (int f = 0; f < 4; f++) {
            int fn = (f + 1) & 3;
            float a0x = ax + cx[f] * wA,  a0y = ay + cy[f] * wA,  a0z = az + cz[f] * wA;
            float a1x = ax + cx[fn] * wA, a1y = ay + cy[fn] * wA, a1z = az + cz[fn] * wA;
            float b0x = bx + cx[f] * wB,  b0y = by + cy[f] * wB,  b0z = bz + cz[f] * wB;
            float b1x = bx + cx[fn] * wB, b1y = by + cy[fn] * wB, b1z = bz + cz[fn] * wB;

            float aLx = Mth.lerp(rim, a0x, a1x), aLy = Mth.lerp(rim, a0y, a1y), aLz = Mth.lerp(rim, a0z, a1z);
            float aRx = Mth.lerp(1.0F - rim, a0x, a1x), aRy = Mth.lerp(1.0F - rim, a0y, a1y), aRz = Mth.lerp(1.0F - rim, a0z, a1z);
            float bLx = Mth.lerp(rim, b0x, b1x), bLy = Mth.lerp(rim, b0y, b1y), bLz = Mth.lerp(rim, b0z, b1z);
            float bRx = Mth.lerp(1.0F - rim, b0x, b1x), bRy = Mth.lerp(1.0F - rim, b0y, b1y), bRz = Mth.lerp(1.0F - rim, b0z, b1z);

            float sh = faceShade[f];
            float fr = Math.min(1.0F, cr * sh), fg = Math.min(1.0F, cg * sh), fb = Math.min(1.0F, cb * sh);

            emitColorQuad(mat, vc,
                    a0x, a0y, a0z, ir, ig, ib, 1.0F,
                    aLx, aLy, aLz, ir, ig, ib, 1.0F,
                    bLx, bLy, bLz, ir, ig, ib, 1.0F,
                    b0x, b0y, b0z, ir, ig, ib, 1.0F);
            emitColorQuad(mat, vc,
                    aLx, aLy, aLz, fr, fg, fb, 1.0F,
                    aRx, aRy, aRz, fr, fg, fb, 1.0F,
                    bRx, bRy, bRz, fr, fg, fb, 1.0F,
                    bLx, bLy, bLz, fr, fg, fb, 1.0F);
            emitColorQuad(mat, vc,
                    aRx, aRy, aRz, ir, ig, ib, 1.0F,
                    a1x, a1y, a1z, ir, ig, ib, 1.0F,
                    b1x, b1y, b1z, ir, ig, ib, 1.0F,
                    bRx, bRy, bRz, ir, ig, ib, 1.0F);
        }
    }

    private static void drawSolidDiamond(
            Matrix4fc mat, VertexConsumer vc, float x, float y, float z, float s,
            float r, float g, float b, float a
    ) {
        emitColorQuad(mat, vc,
                x - s, y, z, r, g, b, a, x, y + s, z, r, g, b, a,
                x + s, y, z, r, g, b, a, x, y - s, z, r, g, b, a);
        emitColorQuad(mat, vc,
                x, y, z - s, r, g, b, a, x, y + s, z, r, g, b, a,
                x, y, z + s, r, g, b, a, x, y - s, z, r, g, b, a);
    }

    private static void emitTexQuad(
            Matrix4fc mat, VertexConsumer vc,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            float x3, float y3, float z3, float u3, float v3,
            float r, float g, float b, float a
    ) {
        vc.addVertex(mat, x0, y0, z0).setColor(r, g, b, a).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
        vc.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
        vc.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setUv(u2, v2)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
        vc.addVertex(mat, x3, y3, z3).setColor(r, g, b, a).setUv(u3, v3)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
    }

    private static void emitColorQuad(
            Matrix4fc mat, VertexConsumer vc,
            float x0, float y0, float z0, float r0, float g0, float b0, float a0,
            float x1, float y1, float z1, float r1, float g1, float b1, float a1,
            float x2, float y2, float z2, float r2, float g2, float b2, float a2,
            float x3, float y3, float z3, float r3, float g3, float b3, float a3
    ) {
        vc.addVertex(mat, x0, y0, z0).setColor(r0, g0, b0, a0);
        vc.addVertex(mat, x1, y1, z1).setColor(r1, g1, b1, a1);
        vc.addVertex(mat, x2, y2, z2).setColor(r2, g2, b2, a2);
        vc.addVertex(mat, x3, y3, z3).setColor(r3, g3, b3, a3);
    }

    private static Vec3 segmentTangent(Vec3[] spine, int i) {
        Vec3 diff;
        if (i == 0) diff = spine[0].subtract(spine[1]);
        else if (i == spine.length - 1) diff = spine[i - 1].subtract(spine[i]);
        else diff = spine[i - 1].subtract(spine[i + 1]);
        return diff.lengthSqr() > 1.0E-6 ? diff.normalize() : new Vec3(0, 0, 1);
    }

    private static Vec3[] orthoBasis(Vec3 forward) {
        Vec3 ref = Math.abs(forward.y) > 0.92 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = forward.cross(ref).normalize();
        Vec3 up = right.cross(forward).normalize();
        return new Vec3[] {right, up};
    }

    /** 沿龙脊做连续平行输运标架，保证神龙垂直盘旋冲霄时龙躯/龙翼/背鬃 100% 流畅无任何 90° 万向锁翻折！ */
    private static Vec3[][] computeSpineFrames(Vec3[] spine) {
        int n = spine.length;
        Vec3[] rights = new Vec3[n];
        Vec3[] ups = new Vec3[n];
        Vec3 fwd0 = segmentTangent(spine, 0);
        Vec3[] b0 = orthoBasis(fwd0);
        rights[0] = b0[0];
        ups[0] = b0[1];
        for (int i = 1; i < n; i++) {
            Vec3 fwd = segmentTangent(spine, i);
            Vec3 prevR = rights[i - 1];
            Vec3 projR = prevR.subtract(fwd.scale(prevR.dot(fwd)));
            Vec3 r = projR.lengthSqr() > 1.0E-5 ? projR.normalize() : orthoBasis(fwd)[0];
            Vec3 u = r.cross(fwd);
            u = u.lengthSqr() > 1.0E-5 ? u.normalize() : orthoBasis(fwd)[1];
            rights[i] = r;
            ups[i] = u;
        }
        return new Vec3[][] {rights, ups};
    }

    private static MovingBlockRenderState buildMovingBlockState(
            ClientLevel level, net.minecraft.world.level.block.state.BlockState blockState, BlockPos seedPos, BlockPos lightPos
    ) {
        MovingBlockRenderState state = new MovingBlockRenderState();
        state.randomSeedPos = seedPos;
        state.blockPos = lightPos;
        state.blockState = blockState;
        state.biome = level.getBiome(lightPos);
        state.cardinalLighting = level.cardinalLighting();
        state.lightEngine = level.getLightEngine();
        return state;
    }

    // ==================== 不可变渲染快照 ====================

    private record VfxSnapshot(
            List<BladeTrailSnap> bladeTrails,
            List<BlockInstance> blocks,
            List<WhirlpoolSnap> whirlpools,
            List<WaterfallSnap> waterfalls,
            List<DragonSnap> dragons,
            List<SunFinaleSnap> sunFinales,
            List<DeadCalmSnap> deadCalms,
            List<RiftSlashSnap> rifts,
            List<ChargeAuraSnap> auras,
            List<FoamSpriteSnap> foams,
            List<StreakSnap> streaks,
            OpeningThreadSnap openingThread,
            List<ThreadSnapData> threadSnaps,
            List<RippleRingSnap> ripples,
            boolean firstPerson,
            Vec3 camForward
    ) {}

    private record BladeTrailSnap(
            List<Vec3> bases, List<Vec3> tips, List<Vec3> normals,
            List<Float> intensities, List<Float> arcDists, List<Double> birthTimes,
            List<Boolean> rushFlags, double renderTime, boolean sunStyle, boolean isLocalPlayer
    ) {}

    private record BlockInstance(
            MovingBlockRenderState state, double x, double y, double z,
            float rotX, float rotY, float rotZ, float scale,
            boolean isUpheaval, float upheavalYaw, float upheavalTilt
    ) {}

    private record WhirlpoolSnap(
            double x, double y, double z, float radius, float height, float progress, float ageTicks, boolean boosted, boolean sunStyle
    ) {}

    private record WaterfallSnap(
            double x, double y, double z, float radius, float height, float progress, float ageTicks, boolean totalConc, boolean sunStyle
    ) {}

    private record DragonSnap(
            Vec3[] spine, int tier, float lifeNorm, float ageTicks, boolean totalConc, boolean sunStyle, float scaleMult, boolean isLocalPlayer
    ) {}

    private record SunFinaleSnap(
            double x, double y, double z, float yawDeg, float radius,
            int tier, float progress, float ageTicks, int maxAge, boolean totalConc, boolean sunStyle
    ) {}

    private record DeadCalmSnap(
            double x, double y, double z, float radius, float progress, float ageTicks, boolean totalConc, boolean sunStyle
    ) {}

    private record RiftSlashSnap(
            double x, double y, double z, float yawDeg, float pitchDeg, float length, float progress, boolean heavy, boolean sunStyle
    ) {}

    private record ChargeAuraSnap(
            double x, double y, double z, float radius, int mode, float progress, float ageTicks, boolean totalConc, boolean sunStyle
    ) {}

    private record FoamSpriteSnap(
            double x, double y, double z, int tile, float baseScale, float rollDeg, float progress, boolean sunStyle
    ) {}

    private record StreakSnap(
            double x, double y, double z, double vx, double vy, double vz,
            float lengthScale, float widthScale, float progress, boolean sunStyle, boolean hotWhite
    ) {}

    private record OpeningThreadSnap(
            Vec3 thirdPersonStart, Vec3 targetNeck, Vec3 viewDir, float strength, float tautness,
            float renderTime, boolean firstPerson, boolean forcedHighlight, boolean sunStyle
    ) {}

    private record ThreadSnapData(
            Vec3 start, Vec3 end, float progress, boolean sunStyle
    ) {}

    private record RippleRingSnap(
            double x, double y, double z, float yawDeg, float pitchDeg, float maxRadius,
            int mode, float progress, float ageTicks, boolean sunStyle
    ) {}
}
