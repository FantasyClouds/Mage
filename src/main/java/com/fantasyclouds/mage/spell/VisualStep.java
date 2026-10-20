package com.fantasyclouds.mage.spell;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 外观修饰符：把待发射弹的视觉委托给任意模组实体（用目标实体的模型/动画渲染），
 * 并可附加飞行拖尾粒子（**可叠加多种**，每次 visual 步骤向粒子列表追加）。
 *
 * <p>与 payload 解耦：视觉是「外观修饰」，独立成步骤，可作用于任何已收集的弹
 * （含 split 复制出的每一颗）。JSON：
 * <ul>
 *   <li>{@code entity} 目标实体注册名（如 lethality:acidic_slash）；空/查不到 → 回落默认 billboard。</li>
 *   <li>{@code yawOffset} 视觉水平偏角（度，0 = 不偏）。目标模型自带朝向基准时用来校准（如酸性斩击需 ±90°）。</li>
 *   <li>{@code particles} 拖尾粒子数组：[{particle, count}...]；空 = 无拖尾。弹每 tick 在当前位置发射。</li>
 * </ul>
 *
 * <p>多次 visual 步骤可叠加不同粒子（追加不覆盖）；entity 后写覆盖前写（外观唯一）。
 *
 * <p>示例：{@code visual(lethality:acidic_slash, +90°) → visual(minecraft:flame) → split×3}
 * = 3 颗全用酸性斩击外观 + 火焰拖尾。
 */
public class VisualStep implements SpellStep {

    private final String entityId;
    private final float yawOffset;
    private final List<ParticleSpec> particles;

    public String entityId() { return entityId; }
    public float yawOffset() { return yawOffset; }
    public List<ParticleSpec> particles() { return particles; }

    public VisualStep(JsonObject json) {
        this.entityId = json.has("entity") ? json.get("entity").getAsString() : "";
        this.yawOffset = json.has("yawOffset") ? json.get("yawOffset").getAsFloat() : 0f;
        this.particles = parseParticles(json);
    }

    /** 解析 particles 数组（[{particle,count}...]）；缺省空列表 = 不追加拖尾。 */
    private static List<ParticleSpec> parseParticles(JsonObject json) {
        List<ParticleSpec> list = new ArrayList<>();
        if (!json.has("particles") || !json.get("particles").isJsonArray()) return list;
        JsonArray arr = json.getAsJsonArray("particles");
        for (var elem : arr) {
            if (!elem.isJsonObject()) continue;
            JsonObject po = elem.getAsJsonObject();
            if (!po.has("particle")) continue;
            String id = po.get("particle").getAsString();
            int count = po.has("count") ? po.get("count").getAsInt() : 2;
            list.add(new ParticleSpec(id, count));
        }
        return list;
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            if (entityId != null && !entityId.isEmpty()) p.visual = entityId;
            if (yawOffset != 0f) p.yawOffset = yawOffset;
            p.particles.addAll(particles); // 追加：多次 visual 步骤可叠加多种粒子
        }
    }
}