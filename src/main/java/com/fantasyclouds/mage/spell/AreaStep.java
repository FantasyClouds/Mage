package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 范围伤害修饰符（2026-10-22 重构·纯范围维度）：area 只决定伤害生效的范围。
 * 伤害次数不归 area——统一走弹级 {@code projectile.damageInterval}（缺省每实体一次，
 * 配 >0 才每 interval tick 重复 = 持续范围伤害场/毒雾/灼烧场）。
 *
 * <p>伤害归属施法者（经 SpellDamageHelper 统一魔法结算，吃 L2 法伤属性）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code radius} 伤害半径（格，缺省 3）。</li>
 * </ul>
 *
 * <p>实现：注册 {@code AreaBehavior} 运行时行为，onTick 按弹级伤害次数语义扫描自身半径 AABB 结算。
 */
public class AreaStep implements SpellStep {

    private final double radius;

    public AreaStep(JsonObject json) {
        this.radius = json.has("radius") ? json.get("radius").getAsDouble() : 3.0;
        if (radius <= 0) throw new IllegalArgumentException("area radius must be > 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.areaRadius = radius;
            // 注册范围伤害运行时行为（顺序 = sequence 顺序；伤害次数逻辑走弹级通用机制）
            p.behaviors.add(new com.fantasyclouds.mage.entity.AreaBehavior(radius));
        }
    }
}
