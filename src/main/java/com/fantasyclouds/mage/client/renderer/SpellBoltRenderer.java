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
import net.minecraft.world.entity.projectile.Projectile;
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
 * 面向摄像机的贴图方块，按 ballType 着色。
 */
public class SpellBoltRenderer extends EntityRenderer<SpellBolt> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("mage", "textures/entity/spell_bolt.png");
    private static final float[][] TYPE_COLORS = {
            {1.0f, 1.0f, 1.0f},   // 0 Magic
            {1.0f, 0.4f, 0.2f},   // 1 Fire
            {0.5f, 0.8f, 1.0f},   // 2 Frost
            {1.0f, 0.9f, 0.3f},   // 3 Lightning
            {0.6f, 0.3f, 1.0f},   // 4 Void
            {0.3f, 1.0f, 0.8f}    // 5 Arcane
    };

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

            // 把施法弹的姿态搬给代理：位置/朝向/tick（动画推进对齐）
            proxy.setPos(bolt.getX(), bolt.getY(), bolt.getZ());
            proxy.setYRot(bolt.getYRot());
            proxy.setXRot(bolt.getXRot());
            proxy.yRotO = bolt.yRotO;
            proxy.xRotO = bolt.xRotO;
            proxy.tickCount = bolt.tickCount;
            if (proxy instanceof Projectile p && bolt instanceof Projectile bp) {
                p.setOwner(bp.getOwner());
            }

            // 用目标实体自己的 Renderer 渲染（模型/动画/贴图全由它负责）
            EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(proxy);
            if (renderer instanceof SpellBoltRenderer) return false; // 目标自己也是 SpellBolt → 防递归
            renderer.render(proxy, yaw, partialTick, pose, buffer, light);
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

    /** Billboard 兜底：面向摄像机贴图方块，按 ballType 着色（原实现）。 */
    private void renderBillboard(SpellBolt entity, float partialTick,
                                 PoseStack pose, MultiBufferSource buffer, int light) {
        pose.pushPose();
        pose.scale(0.4f, 0.4f, 0.4f);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0f));

        int type = Math.max(0, Math.min(entity.getSpellType(), TYPE_COLORS.length - 1));
        float[] color = TYPE_COLORS[type];

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