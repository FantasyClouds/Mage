package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 外观修饰符：把待发射弹的视觉委托给任意模组实体（用目标实体的模型/动画渲染），
 * 并可附加飞行拖尾粒子。
 *
 * <p>与 payload 解耦：视觉是「外观修饰」，独立成步骤，可作用于任何已收集的弹
 * （含 split 复制出的每一颗）。JSON：
 * <ul>
 *   <li>{@code entity} 目标实体注册名（如 lethality:acidic_slash）；空/查不到 → 回落默认 billboard。</li>
 *   <li>{@code particle} 拖尾粒子注册名（如 minecraft:flame）；空 = 无拖尾。弹每 tick 在当前位置发射。</li>
 *   <li>{@code particleCount} 每次发射的粒子数（缺省 2）。</li>
 * </ul>
 *
 * <p>示例：{@code antigravity → visual(lethality:acidic_slash, minecraft:flame) → split×3}
 * = 3 颗全用酸性斩击外观 + 火焰拖尾。
 */
public class VisualStep implements SpellStep {

    private final String entityId;
    private final String particle;
    private final int particleCount;

    public String entityId() { return entityId; }
    public String particle() { return particle; }
    public int particleCount() { return particleCount; }

    public VisualStep(JsonObject json) {
        this.entityId = json.has("entity") ? json.get("entity").getAsString() : "";
        this.particle = json.has("particle") ? json.get("particle").getAsString() : "";
        this.particleCount = json.has("particleCount") ? json.get("particleCount").getAsInt() : 2;
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            if (entityId != null && !entityId.isEmpty()) p.visual = entityId;
            if (particle != null && !particle.isEmpty()) {
                p.particle = particle;
                p.particleCount = particleCount;
            }
        }
    }
}