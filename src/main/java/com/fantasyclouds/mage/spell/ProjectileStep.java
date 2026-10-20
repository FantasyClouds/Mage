package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 基础发射步骤（payload）：定义一颗弹的默认属性，推入 CastContext 待发射列表。
 * 通常出现在 sequence 开头；其后修饰符步骤对它（们）叠加改造。
 *
 * <p>JSON 参数（均可缺省，缺省先回落 {@link StepDefaults} 再取本体默认）：
 * <ul>
 *   <li>{@code projectileType} 外部弹实体 id；空/缺 = 内置 mage:spell_bolt</li>
 *   <li>{@code damage} 基础伤害</li>
 *   <li>{@code ballType} 伤害类型 0-5（0=INDIRECT_MAGIC 魔法）</li>
 *   <li>{@code speed} 初速（本体默认 2.0）</li>
 *   <li>{@code lifetime} 存活 tick（本体默认 60）</li>
 * </ul>
 */
public class ProjectileStep implements SpellStep {

    private final String projectileType;
    private final double damage;
    private final int ballType;
    private final double speed;
    private final int lifetime;

    public double damage() { return damage; }
    public int ballType() { return ballType; }
    public double speed() { return speed; }
    public int lifetime() { return lifetime; }
    public String projectileType() { return projectileType; }

    public ProjectileStep(JsonObject json) {
        this(json, StepDefaults.EMPTY);
    }

    public ProjectileStep(JsonObject json, StepDefaults defs) {
        this.projectileType = json.has("projectileType") ? json.get("projectileType").getAsString() : defs.projectileType();
        this.damage = json.has("damage") ? json.get("damage").getAsDouble() : defs.getDamageOr(0.0);
        this.ballType = json.has("ballType") ? json.get("ballType").getAsInt() : defs.ballType();
        this.speed = json.has("speed") ? json.get("speed").getAsDouble() : defs.getSpeedOr(2.0);
        this.lifetime = json.has("lifetime") ? json.get("lifetime").getAsInt() : defs.getLifetimeOr(60);
    }

    @Override
    public void apply(CastContext ctx) {
        ctx.addProjectile(new SpellProjectile(projectileType, damage, ballType, speed, lifetime));
    }
}