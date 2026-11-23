package com.nichirin.client.physics;

import com.google.common.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nichirin.NichirinMod;
import com.nichirin.client.vfx.WaterVfxManager;
import com.nichirin.network.HitPhysicsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鬼灭 · 日轮刀内置 144Hz 轻量物理受击与关节布娃娃引擎（零外部前置、零 Mixin、全生物通用！）：
 * <p>
 * 1. 活体受击物理受身（LiveHitReaction）：
 *    - 命中顿刀卡肉瞬间沿真实 3D 刀锋切线微颤咬合
 *    - 随后按受击方向产生阻尼弹簧歪斜/扭转/后仰/下压形变再自然回正
 * 2. 斩杀关节布娃娃（Articulated Multi-Limb Ragdoll）：
 *    - 斩杀瞬间接管原版僵硬倾倒动画，直接提取该生物真实贴图与全部解剖肢体（头/躯干/左右臂/左右腿等）
 *    - 沿精确刀锋切线矢量 + 切入高度力矩高速翻滚飞出，60Hz 3子步关节约束求解 + MC 真地形碰撞反弹，144Hz 插值渲染！
 */
public final class MobPhysicsEngine {
    private static final int MAX_RAGDOLLS = 16;

    private static final ContextKey<Integer> ENTITY_ID_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "mob_physics_entity_id"));
    private static final ContextKey<List<RagdollRenderSnap>> RAGDOLL_SNAP_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(NichirinMod.MOD_ID, "mob_ragdoll_snaps"));

    private static final Map<Integer, LiveHitReaction> LIVE_REACTIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, PendingImpulse> PENDING_IMPULSES = new ConcurrentHashMap<>();
    private static final Set<Integer> SPAWNED_RAGDOLL_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> PUSHED_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final List<MobRagdoll> ACTIVE_RAGDOLLS = new ArrayList<>();

    private static long tickCounter = 0L;

    private MobPhysicsEngine() {}

    /** 在 ModBus 注册 RenderState Modifier，为所有 LivingEntityRenderState 注入实体 ID */
    public static void registerRenderStateModifier(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(
                new TypeToken<LivingEntityRenderer<?, ?, ?>>() {},
                (entity, state) -> state.setRenderData(ENTITY_ID_KEY, entity.getId())
        );
    }

    /** 接收服务端精确 3D 刀锋切线命中物理包，同步驱动卡肉、活体受身、斩杀布娃娃与方向斩痕 */
    public static void handleHitPhysicsPayload(HitPhysicsPayload p, IPayloadContext context) {
        // 先交给特效管理器触发出刀顿刀卡肉、顺刀势斩痕火花与隙之线绷断！
        WaterVfxManager.onHitPhysics(p);

        int tid = p.targetId();
        if (tid <= 0) return;

        Vec3 dir = new Vec3(p.dirX(), p.dirY(), p.dirZ());
        if (dir.lengthSqr() > 1.0E-5) dir = dir.normalize();
        else dir = new Vec3(0.0, 0.2, 1.0);

        // 1. 记录活体受击物理受身/后仰/卡肉微震
        LIVE_REACTIONS.put(tid, new LiveHitReaction(
                (float) dir.x, (float) dir.y, (float) dir.z,
                Math.max(0.6F, p.force()),
                Math.max(1.2F, p.hitstopTicks()),
                p.isHeavy() || p.isThreadBreak()
        ));

        // 2. 记录近期刀锋冲量（若当帧斩杀或在 12 tick 内死亡，立即无缝转为物理布娃娃！）
        PENDING_IMPULSES.put(tid, new PendingImpulse(
                dir, new Vec3(p.hitX(), p.hitY(), p.hitZ()),
                Math.max(0.8F, p.force()),
                p.isSunStyle(), p.isThreadBreak(), p.isHeavy(),
                tickCounter + (p.isLethal() ? 25L : 14L)
        ));
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        tickCounter++;

        for (Iterator<Map.Entry<Integer, LiveHitReaction>> it = LIVE_REACTIONS.entrySet().iterator(); it.hasNext(); ) {
            LiveHitReaction r = it.next().getValue();
            if (!r.tick()) it.remove();
        }
        if (tickCounter % 20 == 0) {
            PENDING_IMPULSES.entrySet().removeIf(e -> e.getValue().expireTick < tickCounter);
            if (SPAWNED_RAGDOLL_ENTITIES.size() > 64) {
                SPAWNED_RAGDOLL_ENTITIES.clear();
            }
        }

        // 驱动所有活跃关节布娃娃（每 tick 3 子步 = 60Hz 约束求解 + MC 真实方块碰撞反弹）
        for (Iterator<MobRagdoll> it = ACTIVE_RAGDOLLS.iterator(); it.hasNext(); ) {
            MobRagdoll rag = it.next();
            if (!rag.tickPhysics(level)) {
                it.remove();
            }
        }
    }

    /** 实体渲染前：接管斩杀布娃娃 + 施加活体受击物理歪斜/后仰/卡肉抖动 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?, ?> event) {
        LivingEntityRenderState state = event.getRenderState();
        Integer eid = state.getRenderData(ENTITY_ID_KEY);
        if (eid == null) return;

        // A. 斩杀布娃娃接管：死亡瞬间直接用真实物理多肢体布娃娃替代原版僵硬向左倒！
        if (state.deathTime > 0.0F) {
            if (SPAWNED_RAGDOLL_ENTITIES.contains(eid)) {
                event.setCanceled(true);
                return;
            }
            PendingImpulse imp = PENDING_IMPULSES.get(eid);
            if (imp != null && imp.expireTick >= tickCounter) {
                if (spawnRagdollFromRenderEvent(event, eid, imp)) {
                    SPAWNED_RAGDOLL_ENTITIES.add(eid);
                    PENDING_IMPULSES.remove(eid);
                    LIVE_REACTIONS.remove(eid);
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // B. 活体受击物理受身：严格沿真实 3D 刀锋切线产生卡肉震颤 + 阻尼弹簧扭转歪斜！
        LiveHitReaction react = LIVE_REACTIONS.get(eid);
        if (react == null) return;

        float pt = event.getPartialTick();
        float ageT = react.age + pt;
        float pivotY = Math.max(0.42F, state.boundingBoxHeight * 0.48F);

        // 将世界空间刀锋矢量投影到该生物自身正前方/右侧坐标系
        float yawRad = state.bodyRot * Mth.DEG_TO_RAD;
        float fwdX = -Mth.sin(yawRad), fwdZ = Mth.cos(yawRad);
        float rightX = -fwdZ, rightZ = fwdX;
        float localFwd = react.dirX * fwdX + react.dirZ * fwdZ;
        float localRight = react.dirX * rightX + react.dirZ * rightZ;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        PUSHED_ENTITIES.add(eid);

        if (ageT < react.hitstopTicks) {
            // 双向顿刀卡肉咬合期：高频微震 + 瞬间吃刀倾倒蓄势！
            float bite = 0.055F * Mth.clamp(react.force, 0.7F, 1.8F);
            float vib = Mth.sin(ageT * 11.5F) * bite;
            pose.translate(react.dirX * vib, 0.0, react.dirZ * vib);
        }

        float springU = Math.max(0.0F, ageT - react.hitstopTicks * 0.65F);
        float spring = Mth.sin(springU * 0.78F + 0.65F) * (float) Math.exp(-springU * 0.28);
        float amp = Mth.clamp(react.force * (react.heavy ? 28.0F : 19.0F), 10.0F, 36.0F) * spring;

        pose.translate(0.0, pivotY, 0.0);
        // 顺着刀锋横扫方向产生躯干侧倾 + 扭腰，顺着前后/挑斩产生后仰，下劈产生压扁弹性形变！
        pose.rotateDegrees(Axis.YP, localRight * amp * 0.95F);
        pose.rotateDegrees(Axis.ZP, -localRight * amp * 0.85F);
        pose.rotateDegrees(Axis.XP, (localFwd * 0.85F - react.dirY * 0.65F) * amp);

        if (react.dirY < -0.30F && spring > 0.0F) {
            float sq = Mth.clamp(-react.dirY * spring * 0.22F, 0.0F, 0.22F);
            pose.scale(1.0F + sq * 0.75F, 1.0F - sq, 1.0F + sq * 0.75F);
        }
        pose.translate(0.0, -pivotY, 0.0);
    }

    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?, ?> event) {
        Integer eid = event.getRenderState().getRenderData(ENTITY_ID_KEY);
        if (eid != null && PUSHED_ENTITIES.remove(eid)) {
            event.getPoseStack().popPose();
        }
    }

    /** 在世界渲染 Extract 阶段采集所有布娃娃 144Hz 平滑插值快照（含实时光照） */
    public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        if (ACTIVE_RAGDOLLS.isEmpty()) return;
        ClientLevel level = event.getLevel();
        float pt = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        List<RagdollRenderSnap> snaps = new ArrayList<>(ACTIVE_RAGDOLLS.size());
        for (MobRagdoll rag : ACTIVE_RAGDOLLS) {
            float ageT = rag.age + pt;
            float fadeStart = rag.maxAge - 16.0F;
            float alpha = ageT > fadeStart ? Mth.clamp(1.0F - (ageT - fadeStart) / 16.0F, 0.0F, 1.0F) : 1.0F;
            if (alpha <= 0.02F) continue;

            List<LimbRenderSnap> limbSnaps = new ArrayList<>(rag.limbs.size());
            for (RagdollLimb limb : rag.limbs) {
                double rx = Mth.lerp(pt, limb.prevPos.x, limb.pos.x);
                double ry = Mth.lerp(pt, limb.prevPos.y, limb.pos.y);
                double rz = Mth.lerp(pt, limb.prevPos.z, limb.pos.z);
                Quaternionf q = new Quaternionf(limb.prevRot).slerp(limb.rot, pt).normalize();
                int packedLight = LightCoordsUtil.getLightCoords(level, BlockPos.containing(rx, ry, rz));
                limbSnaps.add(new LimbRenderSnap(rx, ry, rz, q, packedLight, limb.quads));
            }
            snaps.add(new RagdollRenderSnap(rag.texture, alpha, rag.sunBurnTint && ageT < 16.0F ? (1.0F - ageT / 16.0F) : 0.0F, limbSnaps));
        }
        if (!snaps.isEmpty()) {
            event.getRenderState().setRenderData(RAGDOLL_SNAP_KEY, snaps);
        }
    }

    /** 在世界渲染 Submit 阶段以真实 MC 贴图、法线与环境光照提交全部 144Hz 关节布娃娃！ */
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        LevelRenderState levelState = event.getLevelRenderState();
        List<RagdollRenderSnap> snaps = levelState.getRenderData(RAGDOLL_SNAP_KEY);
        if (snaps == null || snaps.isEmpty()) return;

        Vec3 cam = levelState.cameraRenderState.pos;
        double camX = cam.x, camY = cam.y, camZ = cam.z;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        for (RagdollRenderSnap rag : snaps) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(rag.texture), (pose, vc) -> {
                Matrix4fc baseMat = pose.pose();
                Matrix4f limbMat = new Matrix4f();
                Matrix3f rotMat = new Matrix3f();
                Vector3f tmpPos = new Vector3f();
                Vector3f tmpNorm = new Vector3f();

                // 刚被日之呼吸斩杀前瞬间带一点焦灼红金余温，随后还原真实生物贴图色
                float rCol = 1.0F;
                float gCol = Mth.lerp(rag.burnGlow, 1.0F, 0.72F);
                float bCol = Mth.lerp(rag.burnGlow, 1.0F, 0.56F);

                for (LimbRenderSnap limb : rag.limbs) {
                    limbMat.set(baseMat);
                    limbMat.translate((float) (limb.x - camX), (float) (limb.y - camY), (float) (limb.z - camZ));
                    limbMat.rotate(limb.rot);
                    limb.rot.get(rotMat);

                    for (BakedQuad q : limb.quads) {
                        rotMat.transform(q.nx, q.ny, q.nz, tmpNorm).normalize();
                        for (int k = 0; k < 4; k++) {
                            limbMat.transformPosition(q.px[k], q.py[k], q.pz[k], tmpPos);
                            vc.addVertex(tmpPos.x, tmpPos.y, tmpPos.z)
                                    .setColor(rCol, gCol, bCol, rag.alpha)
                                    .setUv(q.u[k], q.v[k])
                                    .setOverlay(OverlayTexture.NO_OVERLAY)
                                    .setLight(limb.packedLight)
                                    .setNormal(tmpNorm.x, tmpNorm.y, tmpNorm.z);
                        }
                    }
                }
            });
        }
    }

    public static void clearAll() {
        LIVE_REACTIONS.clear();
        PENDING_IMPULSES.clear();
        SPAWNED_RAGDOLL_ENTITIES.clear();
        PUSHED_ENTITIES.clear();
        ACTIVE_RAGDOLLS.clear();
        tickCounter = 0L;
    }

    // ==================== 从任意生物真实 EntityModel 提取多肢体关节布娃娃 ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean spawnRagdollFromRenderEvent(
            RenderLivingEvent.Pre<?, ?, ?> event, int entityId, PendingImpulse imp
    ) {
        try {
            LivingEntityRenderer renderer = event.getRenderer();
            LivingEntityRenderState state = event.getRenderState();
            EntityModel model = renderer.getModel();
            Identifier texture = renderer.getTextureLocation(state);
            if (model == null || texture == null) return false;

            // 先用原版当前帧姿态刷新一次骨骼（但不应用死亡 90° 侧倒），抓出最真实的临死瞬间姿势！
            float savedDeathTime = state.deathTime;
            state.deathTime = 0.0F;
            try {
                model.setupAnim(state);
            } finally {
                state.deathTime = savedDeathTime;
            }

            PoseStack capture = new PoseStack();
            float s = Math.max(0.25F, state.scale);
            capture.scale(s, s, s);
            capture.rotateDegrees(Axis.YP, 180.0F - state.bodyRot);
            capture.scale(-1.0F, -1.0F, 1.0F);
            capture.translate(0.0F, -1.501F, 0.0F);

            Map<String, List<BakedQuad>> limbQuads = new LinkedHashMap<>();
            Map<String, float[]> limbBounds = new HashMap<>(); // minX,minY,minZ,maxX,maxY,maxZ

            Vector3f vTmp = new Vector3f();
            Vector3f nTmp = new Vector3f();

            model.root().visit(capture, (partPose, path, cubeIdx, cube) -> {
                if (cube.polygons == null || cube.polygons.length == 0) return;
                String group = classifyLimbGroup(path);
                List<BakedQuad> out = limbQuads.computeIfAbsent(group, k -> new ArrayList<>());
                float[] b = limbBounds.computeIfAbsent(group, k -> new float[] {
                        Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                        -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
                });

                Matrix4f mat = partPose.pose();
                for (ModelPart.Polygon poly : cube.polygons) {
                    ModelPart.Vertex[] verts = poly.vertices();
                    if (verts.length < 4) continue;
                    float[] px = new float[4], py = new float[4], pz = new float[4];
                    float[] pu = new float[4], pv = new float[4];
                    for (int i = 0; i < 4; i++) {
                        ModelPart.Vertex mv = verts[i];
                        mat.transformPosition(mv.worldX(), mv.worldY(), mv.worldZ(), vTmp);
                        px[i] = vTmp.x; py[i] = vTmp.y; pz[i] = vTmp.z;
                        pu[i] = mv.u(); pv[i] = mv.v();
                        if (vTmp.x < b[0]) b[0] = vTmp.x;
                        if (vTmp.y < b[1]) b[1] = vTmp.y;
                        if (vTmp.z < b[2]) b[2] = vTmp.z;
                        if (vTmp.x > b[3]) b[3] = vTmp.x;
                        if (vTmp.y > b[4]) b[4] = vTmp.y;
                        if (vTmp.z > b[5]) b[5] = vTmp.z;
                    }
                    partPose.transformNormal(poly.normal(), nTmp);
                    if (nTmp.lengthSquared() > 1.0E-6F) nTmp.normalize();
                    else nTmp.set(0.0F, 1.0F, 0.0F);
                    out.add(new BakedQuad(px, py, pz, pu, pv, nTmp.x, nTmp.y, nTmp.z));
                }
            });

            if (limbQuads.isEmpty()) return false;

            // 构建各肢体刚体（顶点全部平移至各肢体质心局部坐标系）
            List<RagdollLimb> limbs = new ArrayList<>(limbQuads.size());
            Map<String, Vec3> initialCentroids = new HashMap<>();
            RagdollLimb coreLimb = null;
            float bestCoreScore = -Float.MAX_VALUE;
            float mobHalfH = Math.max(0.45F, state.boundingBoxHeight * 0.50F);

            for (Map.Entry<String, List<BakedQuad>> e : limbQuads.entrySet()) {
                String name = e.getKey();
                List<BakedQuad> raw = e.getValue();
                if (raw.isEmpty()) continue;
                float[] b = limbBounds.get(name);
                float cx = (b[0] + b[3]) * 0.5F;
                float cy = (b[1] + b[4]) * 0.5F;
                float cz = (b[2] + b[5]) * 0.5F;
                float extX = (b[3] - b[0]) * 0.5F;
                float extY = (b[4] - b[1]) * 0.5F;
                float extZ = (b[5] - b[2]) * 0.5F;
                float radius = Mth.clamp(Math.min(Math.max(extX, extZ), Math.max(extY, 0.16F)) * 0.85F, 0.14F, 0.42F);

                List<BakedQuad> centered = new ArrayList<>(raw.size());
                for (BakedQuad q : raw) {
                    float[] px = new float[4], py = new float[4], pz = new float[4];
                    for (int i = 0; i < 4; i++) {
                        px[i] = q.px[i] - cx;
                        py[i] = q.py[i] - cy;
                        pz[i] = q.pz[i] - cz;
                    }
                    centered.add(new BakedQuad(px, py, pz, q.u, q.v, q.nx, q.ny, q.nz));
                }

                Vec3 worldPos = new Vec3(state.x + cx, state.y + cy, state.z + cz);
                RagdollLimb limb = new RagdollLimb(name, worldPos, radius, centered);
                limbs.add(limb);
                initialCentroids.put(name, new Vec3(cx, cy, cz));

                float score = (extX * extY * extZ) * 40.0F - Math.abs(cy - mobHalfH) * 2.0F;
                if (name.contains("body") || name.contains("torso") || name.contains("cube")) score += 100.0F;
                if (score > bestCoreScore) {
                    bestCoreScore = score;
                    coreLimb = limb;
                }
            }

            if (limbs.isEmpty() || coreLimb == null) return false;

            // 建立关节约束：每个肢体通过解剖铰接点（肩/髋/颈）柔韧连接至核心躯干！
            List<JointConstraint> joints = new ArrayList<>();
            Vec3 coreCent = initialCentroids.get(coreLimb.name);
            for (RagdollLimb limb : limbs) {
                if (limb == coreLimb) continue;
                Vec3 limbCent = initialCentroids.get(limb.name);
                // 铰接点位于躯干与肢体连线 52% 处（正好贴合颈部/肩关节/髋关节！）
                Vec3 jointCent = coreCent.lerp(limbCent, 0.52);
                Vector3f anchorCore = new Vector3f(
                        (float) (jointCent.x - coreCent.x),
                        (float) (jointCent.y - coreCent.y),
                        (float) (jointCent.z - coreCent.z)
                );
                Vector3f anchorLimb = new Vector3f(
                        (float) (jointCent.x - limbCent.x),
                        (float) (jointCent.y - limbCent.y),
                        (float) (jointCent.z - limbCent.z)
                );
                joints.add(new JointConstraint(coreLimb, limb, anchorCore, anchorLimb));
            }

            // 沿精确 3D 刀锋切线施加物理击飞速度 + 切入高度力矩（砍头颈上部猛甩、下劈狠砸反弹！）
            Vec3 cutDir = imp.cutDir;
            double speed = Mth.clamp(0.42 + imp.force * 0.30, 0.45, 1.38);
            double liftY = cutDir.y < -0.35
                    ? 0.22 // 下劈触地瞬间强反弹
                    : Mth.clamp(cutDir.y * speed + 0.34, 0.24, 0.92);

            for (RagdollLimb limb : limbs) {
                double relY = limb.pos.y - imp.hitPos.y;
                double prox = Mth.clamp(1.15 - Math.abs(relY) * 0.55, 0.55, 1.35);
                double limbSpd = speed * prox;

                // 离切入点越近的肢体吃满刀锋切线冲量，其余肢体带自然角动量甩飞！
                limb.vel.set(
                        (float) (cutDir.x * limbSpd),
                        (float) (liftY * (0.82 + 0.28 * prox)),
                        (float) (cutDir.z * limbSpd)
                );

                // r × cutDir 计算真实三维翻转角速度，让尸体顺着砍击方向自然翻滚！
                Vec3 lever = new Vec3(limb.pos.x - state.x, limb.pos.y - (state.y + mobHalfH), limb.pos.z - state.z);
                Vec3 torque = lever.cross(cutDir);
                float spinScale = (float) (0.28 + imp.force * 0.14);
                limb.angVel.set(
                        (float) (torque.x * spinScale + cutDir.z * 0.22F),
                        (float) (torque.y * spinScale + (limb == coreLimb ? 0.16F : 0.28F)),
                        (float) (torque.z * spinScale - cutDir.x * 0.22F)
                );
            }

            while (ACTIVE_RAGDOLLS.size() >= MAX_RAGDOLLS) {
                ACTIVE_RAGDOLLS.remove(0);
            }
            ACTIVE_RAGDOLLS.add(new MobRagdoll(texture, limbs, joints, imp.sunStyle, 58));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 将 ModelPart 路径归一化为主解剖肢体名（自动把帽子/外套/袖子/裤腿合并到对应主肢体，避免散架！） */
    private static String classifyLimbGroup(String path) {
        if (path == null || path.isEmpty()) return "body";
        String clean = path.startsWith("/") ? path.substring(1) : path;
        int slash = clean.indexOf('/');
        String root = slash >= 0 ? clean.substring(0, slash) : clean;
        if (root.equals("root") && slash >= 0 && slash + 1 < clean.length()) {
            String sub = clean.substring(slash + 1);
            int s2 = sub.indexOf('/');
            root = s2 >= 0 ? sub.substring(0, s2) : sub;
        }
        return switch (root) {
            case "hat", "headwear", "ear", "ears", "nose", "jaw", "mouth" -> "head";
            case "jacket", "cloak", "chest", "torso", "tail" -> "body";
            case "right_sleeve", "right_shoulder" -> "right_arm";
            case "left_sleeve", "left_shoulder" -> "left_arm";
            case "right_pants" -> "right_leg";
            case "left_pants" -> "left_leg";
            default -> root;
        };
    }

    // ==================== 数据结构与 60Hz 关节物理求解器 ====================

    private static final class LiveHitReaction {
        final float dirX, dirY, dirZ, force, hitstopTicks;
        final boolean heavy;
        int age = 0;
        final int maxAge = 12;

        LiveHitReaction(float dirX, float dirY, float dirZ, float force, float hitstopTicks, boolean heavy) {
            this.dirX = dirX; this.dirY = dirY; this.dirZ = dirZ;
            this.force = force; this.hitstopTicks = hitstopTicks; this.heavy = heavy;
        }

        boolean tick() { return ++age < maxAge; }
    }

    private record PendingImpulse(
            Vec3 cutDir, Vec3 hitPos, float force, boolean sunStyle, boolean threadBreak, boolean heavy, long expireTick
    ) {}

    private record BakedQuad(
            float[] px, float[] py, float[] pz, float[] u, float[] v, float nx, float ny, float nz
    ) {}

    private static final class RagdollLimb {
        final String name;
        final Vector3f pos = new Vector3f();
        final Vector3f prevPos = new Vector3f();
        final Vector3f vel = new Vector3f();
        final Quaternionf rot = new Quaternionf();
        final Quaternionf prevRot = new Quaternionf();
        final Vector3f angVel = new Vector3f();
        final float radius;
        final List<BakedQuad> quads;

        RagdollLimb(String name, Vec3 worldPos, float radius, List<BakedQuad> quads) {
            this.name = name;
            this.pos.set((float) worldPos.x, (float) worldPos.y, (float) worldPos.z);
            this.prevPos.set(this.pos);
            this.radius = radius;
            this.quads = quads;
        }
    }

    private record JointConstraint(RagdollLimb core, RagdollLimb limb, Vector3f anchorCore, Vector3f anchorLimb) {}

    private static final class MobRagdoll {
        final Identifier texture;
        final List<RagdollLimb> limbs;
        final List<JointConstraint> joints;
        final boolean sunBurnTint;
        final int maxAge;
        int age = 0;

        MobRagdoll(Identifier texture, List<RagdollLimb> limbs, List<JointConstraint> joints, boolean sunBurnTint, int maxAge) {
            this.texture = texture;
            this.limbs = limbs;
            this.joints = joints;
            this.sunBurnTint = sunBurnTint;
            this.maxAge = maxAge;
        }

        boolean tickPhysics(ClientLevel level) {
            // 先保存上一帧精确状态供 144Hz lerp / slerp
            for (RagdollLimb l : limbs) {
                l.prevPos.set(l.pos);
                l.prevRot.set(l.rot);
            }

            final int subSteps = 3;
            final float dt = 1.0F / subSteps;
            final float linDrag = (float) Math.pow(0.982, dt);
            final float angDrag = (float) Math.pow(0.945, dt);

            Vector3f pA = new Vector3f(), pB = new Vector3f(), err = new Vector3f();
            Vector3f rCore = new Vector3f(), rLimb = new Vector3f(), crossTmp = new Vector3f();
            Quaternionf dq = new Quaternionf();
            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

            for (int sub = 0; sub < subSteps; sub++) {
                // 1. 重力与自由刚体积分
                for (RagdollLimb l : limbs) {
                    l.vel.y -= 0.068F * dt;
                    l.vel.mul(linDrag);
                    l.angVel.mul(angDrag);
                    l.pos.fma(dt, l.vel);

                    float angSpeed = l.angVel.length();
                    if (angSpeed > 1.0E-4F) {
                        float halfAngle = angSpeed * dt * 0.5F;
                        float s = Mth.sin(halfAngle) / angSpeed;
                        dq.set(l.angVel.x * s, l.angVel.y * s, l.angVel.z * s, Mth.cos(halfAngle));
                        dq.mul(l.rot, l.rot).normalize();
                    }
                }

                // 2. 4 次迭代关节距离与扭转约束（让手脚与头自然绕肩/髋/颈甩动而不脱节！）
                for (int iter = 0; iter < 4; iter++) {
                    for (JointConstraint j : joints) {
                        j.core.rot.transform(j.anchorCore, rCore);
                        j.limb.rot.transform(j.anchorLimb, rLimb);
                        pA.set(j.core.pos).add(rCore);
                        pB.set(j.limb.pos).add(rLimb);
                        err.set(pB).sub(pA);

                        float errLen = err.length();
                        if (errLen < 1.0E-4F) continue;

                        // 核心躯干质量更大（分摊 26% 偏移），轻肢体分摊 74% 偏移
                        j.core.pos.fma(0.26F, err);
                        j.limb.pos.fma(-0.74F, err);

                        // 将拉扯回弹转化为柔和的速度与角动量传递（防止关节像橡皮筋乱弹）
                        j.core.vel.fma(0.10F, err);
                        j.limb.vel.fma(-0.28F, err);
                        rLimb.cross(err, crossTmp);
                        j.limb.angVel.fma(-0.18F, crossTmp);
                    }
                }

                // 3. Minecraft 真实世界方块碰撞与地面滚动摩擦
                for (RagdollLimb l : limbs) {
                    resolveBlockCollisions(level, l, mpos);
                }
            }

            return ++age < maxAge;
        }

        private static void resolveBlockCollisions(ClientLevel level, RagdollLimb l, BlockPos.MutableBlockPos mpos) {
            float r = l.radius;
            int bx = Mth.floor(l.pos.x);
            int byFloor = Mth.floor(l.pos.y - r);
            int bz = Mth.floor(l.pos.z);

            // 地面碰撞
            mpos.set(bx, byFloor, bz);
            if (isSolidBlock(level, mpos)) {
                float topY = byFloor + 1.0F;
                if (l.pos.y - r < topY) {
                    l.pos.y = topY + r;
                    if (l.vel.y < -0.14F) {
                        l.vel.y = -l.vel.y * 0.24F;
                    } else if (l.vel.y < 0.0F) {
                        l.vel.y = 0.0F;
                    }
                    // 地面摩擦 + 顺滑滚动角速度
                    l.vel.x *= 0.74F;
                    l.vel.z *= 0.74F;
                    l.angVel.mul(0.76F);
                    l.angVel.x += l.vel.z * 0.14F;
                    l.angVel.z -= l.vel.x * 0.14F;
                }
            }

            // 水平四面墙壁碰撞反弹
            int byMid = Mth.floor(l.pos.y);
            if (l.vel.x > 0.0F) {
                int nx = Mth.floor(l.pos.x + r);
                mpos.set(nx, byMid, bz);
                if (isSolidBlock(level, mpos)) {
                    l.pos.x = nx - r;
                    l.vel.x = -l.vel.x * 0.36F;
                    l.angVel.z += l.vel.x * 0.25F;
                }
            } else if (l.vel.x < 0.0F) {
                int nx = Mth.floor(l.pos.x - r);
                mpos.set(nx, byMid, bz);
                if (isSolidBlock(level, mpos)) {
                    l.pos.x = nx + 1.0F + r;
                    l.vel.x = -l.vel.x * 0.36F;
                    l.angVel.z += l.vel.x * 0.25F;
                }
            }

            if (l.vel.z > 0.0F) {
                int nz = Mth.floor(l.pos.z + r);
                mpos.set(bx, byMid, nz);
                if (isSolidBlock(level, mpos)) {
                    l.pos.z = nz - r;
                    l.vel.z = -l.vel.z * 0.36F;
                    l.angVel.x -= l.vel.z * 0.25F;
                }
            } else if (l.vel.z < 0.0F) {
                int nz = Mth.floor(l.pos.z - r);
                mpos.set(bx, byMid, nz);
                if (isSolidBlock(level, mpos)) {
                    l.pos.z = nz + 1.0F + r;
                    l.vel.z = -l.vel.z * 0.36F;
                    l.angVel.x -= l.vel.z * 0.25F;
                }
            }

            // 天花板碰撞
            if (l.vel.y > 0.0F) {
                int ny = Mth.floor(l.pos.y + r);
                mpos.set(bx, ny, bz);
                if (isSolidBlock(level, mpos)) {
                    l.pos.y = ny - r;
                    l.vel.y = -l.vel.y * 0.25F;
                }
            }
        }

        private static boolean isSolidBlock(ClientLevel level, BlockPos pos) {
            BlockState st = level.getBlockState(pos);
            return !st.isAir() && !st.getCollisionShape(level, pos).isEmpty();
        }
    }

    private record LimbRenderSnap(double x, double y, double z, Quaternionf rot, int packedLight, List<BakedQuad> quads) {}

    private record RagdollRenderSnap(Identifier texture, float alpha, float burnGlow, List<LimbRenderSnap> limbs) {}
}
