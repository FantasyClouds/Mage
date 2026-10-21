package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 范围伤害修饰符（lethality AcidicSlash 式持续范围伤害场）：让待发射弹在<b>整个飞行期间</b>
 * 每 {@code interval} tick 对自身半径内所有实体（除施法者）结算范围伤害——不是只在碰撞时爆一次，
 * 而是弹掠过即持续造成伤害。碰撞瞬间仍额外结算一次范围伤害（命中即伤）。
 *
 * <p>伤害归属施法者（经 SpellDamageHelper 统一魔法结算，吃 L2 法伤属性）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code radius} 伤害半径（格，缺省 3）。</li>
 *   <li>{@code interval} 结算间隔 tick（缺省 5；每次结算都全额 spell_damage，间隔防每 tick 刷伤太猛）。</li>
 * </ul>
 *
 * <p>实现：飞行 tick 内每 interval 读 {@code spell_area} 对自身位置 AABB 内实体逐个 applySpellDamage。
 */
public class AreaStep implements SpellStep {

    private final double radius;
    private final int interval;

    public AreaStep(JsonObject json) {
        this.radius = json.has("radius") ? json.get("radius").getAsDouble() : 3.0;
        this.interval = json.has("interval") ? json.get("interval").getAsInt() : 5;
        if (radius <= 0) throw new IllegalArgumentException("area radius must be > 0");
        if (interval <= 0) throw new IllegalArgumentException("area interval must be > 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.areaRadius = radius;
            p.areaInterval = interval;
        }
    }
}