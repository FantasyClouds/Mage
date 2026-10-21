package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 范围爆裂修饰符：让待发射弹命中后对命中点半径内<b>所有实体</b>（除施法者）造成范围伤害。
 * 伤害归属施法者（经 SpellDamageHelper 统一魔法结算，吃 L2 法伤属性）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code radius} 爆炸半径（格，缺省 3）。</li>
 * </ul>
 *
 * <p>实现：命中结算处读 {@code spell_area}，对命中点附近 AABB 内实体逐个 applySpellDamage。
 * 伤害值 = 弹自身伤害（ballDamage + forceful）；不逐各自减伤。
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
        }
    }
}