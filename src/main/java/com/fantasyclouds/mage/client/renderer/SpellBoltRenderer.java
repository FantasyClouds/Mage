package com.fantasyclouds.mage.client.renderer;

import com.fantasyclouds.mage.entity.SpellBolt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 内置法弹渲染：面向摄像机的 billboard 贴图，按 ballType 着色（客户端经由 SynchedEntityData 同步）。
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

    public SpellBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SpellBolt entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffer, int light) {
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
        super.render(entity, yaw, partialTick, pose, buffer, light);
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
