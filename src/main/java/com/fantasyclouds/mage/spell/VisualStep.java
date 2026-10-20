package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 外观修饰符：把待发射弹的视觉委托给任意模组实体（用目标实体的模型/动画渲染）。
 * 纯客户端行为（渲染层），服务端零改动。
 *
 * <p>与 payload 解耦：视觉是「外观修饰」，独立成步骤，可作用于任何已收集的弹
 * （含 split 复制出的每一颗）。JSON：
 * <ul>
 *   <li>{@code entity} 目标实体注册名（如 lethality:acidic_slash）；空/查不到 → 回落默认 billboard。</li>
 * </ul>
 *
 * <p>示例：{@code antigravity → visual(lethality:acidic_slash) → split×3} = 3 颗全用酸性斩击外观。
 */
public class VisualStep implements SpellStep {

    private final String entityId;

    public String entityId() { return entityId; }

    public VisualStep(JsonObject json) {
        this.entityId = json.has("entity") ? json.get("entity").getAsString() : "";
    }

    @Override
    public void apply(CastContext ctx) {
        if (entityId == null || entityId.isEmpty()) return;
        for (SpellProjectile p : ctx.pending()) {
            p.visual = entityId;
        }
    }
}