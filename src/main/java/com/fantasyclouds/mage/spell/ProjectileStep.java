package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 唯一的 payload 发射步骤：定义一颗弹的全部属性（类型/伤害/速度/寿命），推入 CastContext
 * 待发射列表。通常出现在 sequence 开头；其后修饰符步骤（split/pierce/homing/area/bounce/
 * antigravity/visual）对它（们）叠加改造。
 *
 * <p><b>无重力不是本步骤的原语</b>：弹默认受重力（抛物线）；要直线弹用独立修饰符步骤
 * {@code {"type":"antigravity"}}（AntigravityStep）——修饰符不带 speed 等 payload 属性。
 *
 * <p>JSON 参数（均可缺省，缺省先回落 {@link StepDefaults} 再取本体默认）：
 * <ul>
 *   <li>{@code projectileType} 外部弹实体 id；空/缺 = 内置 mage:spell_bolt</li>
 *   <li>{@code damage} 基础伤害</li>
 *   <li>{@code ballType} 伤害类型 0-5（0=INDIRECT_MAGIC 魔法）</li>
 *   <li>{@code speed} 初速（本体默认 2.0）</li>
 *   <li>{@code lifetime} 存活 tick（本体默认 60）</li>
 * </ul>
 *
 * <p>视觉委托不在此步骤：外观是修饰属性，独立 {@code visual} 步骤（VisualStep）作用于已收集的弹。
 */
public class ProjectileStep implements SpellStep {

    private final String projectileType;
    private final double damage;
    private final int ballType;
    private final double speed;
    private final int lifetime;
    private final boolean antigravity;

    public double damage() { return damage; }
    public int ballType() { return ballType; }
    public double speed() { return speed; }
    public int lifetime() { return lifetime; }
    public String projectileType() { return projectileType; }
    public boolean antigravity() { return antigravity; }

    public ProjectileStep(JsonObject json) {
        this(json, StepDefaults.EMPTY, true); // 单参构造（旧折叠）默认 antigravity（保持旧行为=直线）
    }

    public ProjectileStep(JsonObject json, StepDefaults defs) {
        this(json, defs, true);
    }

    /** 显式指定无重力与否（SpellSteps 按原语 type 分发）。 */
    public ProjectileStep(JsonObject json, StepDefaults defs, boolean antigravity) {
        this.projectileType = json.has("projectileType") ? json.get("projectileType").getAsString() : defs.projectileType();
        this.damage = json.has("damage") ? json.get("damage").getAsDouble() : defs.getDamageOr(0.0);
        this.ballType = json.has("ballType") ? json.get("ballType").getAsInt() : defs.ballType();
        this.speed = json.has("speed") ? json.get("speed").getAsDouble() : defs.getSpeedOr(2.0);
        this.lifetime = json.has("lifetime") ? json.get("lifetime").getAsInt() : defs.getLifetimeOr(60);
        this.antigravity = antigravity;
    }

    @Override
    public void apply(CastContext ctx) {
        ctx.addProjectile(new SpellProjectile(projectileType, damage, ballType, speed, lifetime, antigravity, 0.0));
    }
}