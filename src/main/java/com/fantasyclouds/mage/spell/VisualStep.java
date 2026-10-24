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
 *   <li>{@code particles} 特效粒子数组：[{particle, count, mode?, radius?}...]；空 = 无粒子。
 *       <b>mode</b>（2026-10-22 定案·两种约定）：缺省 {@code spread}=点扩散（弹位置小抖动，拖尾火花）；
 *       {@code cloud}=范围雾（散布在弹周围 {@code radius} 格扁平球体内——毒雾/灼烧场）。
 *       <b>radius</b> 仅 cloud 用（缺省 1）。弹移动则雾跟随；弹停下则雾在原地残留（残留时长由弹 lifetime 控制）。</li>
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
    private final List<ParticleSpec> deathParticles;

    public String entityId() { return entityId; }
    public float yawOffset() { return yawOffset; }
    public List<ParticleSpec> particles() { return particles; }

    public VisualStep(JsonObject json) {
        this.entityId = json.has("entity") ? json.get("entity").getAsString() : "";
        this.yawOffset = json.has("yawOffset") ? json.get("yawOffset").getAsFloat() : 0f;
        this.particles = parseParticles(json);
        this.deathParticles = parseParticles(json, "onDeath"); // 消失粒子：法术消失时一次性爆发
    }

    /** 解析 particles 数组（[{particle,count,mode?,radius?}...]）；缺省空列表 = 不追加粒子。 */
    private static List<ParticleSpec> parseParticles(JsonObject json) {
        return parseParticles(json, "particles");
    }

    private static List<ParticleSpec> parseParticles(JsonObject json, String key) {
        List<ParticleSpec> list = new ArrayList<>();
        if (!json.has(key) || !json.get(key).isJsonArray()) return list;
        JsonArray arr = json.getAsJsonArray(key);
        for (var elem : arr) {
            if (!elem.isJsonObject()) continue;
            JsonObject po = elem.getAsJsonObject();
            if (!po.has("particle")) continue;
            String id = po.get("particle").getAsString();
            int count = po.has("count") ? po.get("count").getAsInt() : 2;
            String mode = po.has("mode") ? po.get("mode").getAsString() : "spread";
            double radius = po.has("radius") ? po.get("radius").getAsDouble() : 1.0;
            list.add(new ParticleSpec(id, count, mode, radius));
        }
        return list;
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            if (entityId != null && !entityId.isEmpty()) p.visual = entityId;
            if (yawOffset != 0f) p.yawOffset = yawOffset;
            p.particles.addAll(particles); // 追加：多次 visual 步骤可叠加多种粒子
            p.deathParticles.addAll(deathParticles); // 消失粒子同样可叠加
        }
    }
}