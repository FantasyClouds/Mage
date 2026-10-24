package com.fantasyclouds.mage.client.renderer;

import com.fantasyclouds.mage.entity.SpellBolt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置法弹渲染：视觉委托优先，billboard 兜底。
 *
 * <p><b>视觉委托</b>：SpellBolt 同步了 {@code spell_visual}（目标实体注册名，如
 * lethality:acidic_slash）时，用该实体类型的 Renderer 渲染——目标实体的模型/贴图/动画
 * 全由它自己的 renderer 呈现（"影法术"式实体投影，兼容任意模组实体）。视觉是纯客户端，
 * 服务端零改动；代理实体仅渲染用，不进世界、不同步。
 *
 * <p><b>billboard 兜底</b>：无视觉 / 目标实体注册名查不到 / 代理创建失败 → 用原来的
 * 面向摄像机的贴图方块，按 damageType（字符串）着色。
 */
public class SpellBoltRenderer extends EntityRenderer<SpellBolt> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("mage", "textures/entity/spell_bolt.png");
    /** 伤害类型字符串 id → RGB 颜色（2026-10-22 定案，不再是数字索引）。 */
    private static final java.util.Map<String, float[]> TYPE_COLORS = java.util.Map.of(
            "magic", new float[]{1.0f, 1.0f, 1.0f},
            "fire", new float[]{1.0f, 0.4f, 0.2f},
            "frost", new float[]{0.5f, 0.8f, 1.0f},
            "lightning", new float[]{1.0f, 0.9f, 0.3f},
            "void", new float[]{0.6f, 0.3f, 1.0f}
    );

    /** 代理实体缓存：注册名 → 客户端代理实例（纯渲染用，避免每帧 create 的开销）。 */
    private static final Map<String, Entity> PROXY_CACHE = new ConcurrentHashMap<>();

    public SpellBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SpellBolt entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light) {
        if (renderDelegated(entity, yaw, partialTick, pose, buffer, light)) {
            return; // 视觉委托成功
        }
        renderBillboard(entity, partialTick, pose, buffer, light);
    }

    /** 视觉委托渲染。返回 false = 委托不可用（回落到 billboard）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean renderDelegated(SpellBolt bolt, float yaw, float partialTick,
                                    PoseStack pose, MultiBufferSource buffer, int light) {
        String visual = bolt.getSpellVisual();
        if (visual == null || visual.isEmpty()) return false;
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(visual));
            if (type == null) return false;
            Entity proxy = getProxy(type, visual);
            if (proxy == null) return false;

            // 把施法弹的姿态与运动搬给代理：位置/朝向/速度/tick（动画推进对齐）。
            // ⚠️ 关键：很多目标 Renderer（如 lethality AcidicSlashRenderer）**不读 yRot**，
            // 而是从 getDeltaMovement() 自己算朝向。而客户端 SpellBolt 的 deltaMovement 恒为 0
            // （launch 只在服务端跑、deltaMovement 不同步）——必须用 SynchedEntityData 同步的
            // 发射方向 getSpellDirection() 重建，否则代理朝向锁死。
            // yawOffset 仅用于校准目标模型自带的静态朝向基准（有需要才配）。
            Vec3 dir = bolt.getSpellDirection();
            if (dir.lengthSqr() < 0.0001) return false; // 方向没同步上（不应发生）：回落 billboard
            float visualYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)) + bolt.getSpellYawOffset();
            float visualPitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
            proxy.setPos(bolt.getX(), bolt.getY(), bolt.getZ());
            proxy.setYRot(visualYaw);
            proxy.setXRot(visualPitch);
            proxy.yRotO = visualYaw;
            proxy.xRotO = visualPitch;
            proxy.setDeltaMovement(dir); // 单位方向即可：renderer 只要朝向，不看速度大小
            proxy.tickCount = bolt.tickCount;
            if (proxy instanceof Projectile p) {
                p.setOwner(bolt.getOwner()); // bolt 本身即 Projectile（SpellBolt 继承 AbstractHurtingProjectile）
            }
            // 关键：LivingEntity 的渲染朝向（MobRenderer）用 yHeadRot/yBodyRot 而非 yRot——
            // 不设置则猪这类 3D 实体模型恒朝默认方向，不随玩家视角转。
            if (proxy instanceof LivingEntity le) {
                le.setYHeadRot(visualYaw);
                le.setYBodyRot(visualYaw);
                le.yHeadRotO = visualYaw;
                le.yBodyRotO = visualYaw;
            }

            // 用目标实体自己的 Renderer 渲染（模型/动画/贴图全由它负责）。
            // ⚠️ 必须传 proxy 的 visualYaw/visualPitch 而非外层 bolt 的 yaw：
            // EntityRenderer 基类（MobRenderer.applyRotations）用这个 rotationYaw 对模型做基础旋转，
            // 子类（如 AcidicSlashRenderer）再从 deltaMovement 叠加朝向——两层都基于传入的 yaw。
            // 传错（bolt 的 yaw）→ 基础旋转与 proxy.yRot/deltaMovement 不一致 → 整体朝向错。
            EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(proxy);
            if (renderer instanceof SpellBoltRenderer) return false; // 目标自己也是 SpellBolt → 防递归
            renderer.render(proxy, visualYaw, partialTick, pose, buffer, light);
            return true;
        } catch (Throwable t) {
            // 代理创建/渲染异常：优雅回落 billboard，不炸渲染线程
            return false;
        }
    }

    /** 取/建代理实体（缓存按注册名）。代理不进世界，仅作为渲染的模型载体。 */
    private Entity getProxy(EntityType<?> type, String visual) {
        Entity proxy = PROXY_CACHE.get(visual);
        if (proxy == null || proxy.getType() != type) {
            if (proxy != null) PROXY_CACHE.remove(visual);
            Entity fresh = type.create(Minecraft.getInstance().level);
            if (fresh != null) {
                PROXY_CACHE.put(visual, fresh);
                return fresh;
            }
        }
        return proxy;
    }

    /** Billboard 兜底：面向摄像机贴图方块，按 damageType 字符串着色（2026-10-22）。 */
    private void renderBillboard(SpellBolt entity, float partialTick,
                                 PoseStack pose, MultiBufferSource buffer, int light) {
        pose.pushPose();
        pose.scale(0.4f, 0.4f, 0.4f);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0f));

        float[] color = TYPE_COLORS.getOrDefault(entity.getSpellType(), TYPE_COLORS.get("magic"));

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(TEXTURE));
        PoseStack.Pose p = pose.last();
        Matrix4f mat = p.pose();
        Matrix3f norm = p.normal();
        vertex(consumer, mat, norm, -0.5f, -0.5f, 0.0f, color, 0.0f, 1.0f, light);
        vertex(consumer, mat, norm,  0.5f, -0.5f, 0.0f, color, 1.0f, 1.0f, light);
        vertex(consumer, mat, norm,  0.5f,  0.5f, 0.0f, color, 1.0f, 0.0f, light);
        vertex(consumer, mat, norm, -0.5f,  0.5f, 0.0f, color, 0.0f, 0.0f, light);

        pose.popPose();
        super.render(entity, 0.0f, partialTick, pose, buffer, light);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f mat, Matrix3f norm,
                               float x, float y, float z, float[] color, float u, float v, int light) {
        consumer.vertex(mat, x, y, z).color(color[0], color[1], color[2], 1.0f)
                .uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(norm, 0.0f, 0.0f, 1.0f).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(SpellBolt entity) {
        return TEXTURE;
    }
}