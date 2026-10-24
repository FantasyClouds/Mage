package com.fantasyclouds.mage.spell;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
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
 *   <li>{@code damage} 基础伤害</li>
 *   <li>{@code damageType} 伤害类型字符串（"magic"/"fire"/"frost"/"lightning"/"void"，2026-10-22 定案；旧 arcane 已废弃——效果用 grantEffect/applyEffect 原语）；
 *       兼容旧 {@code ballType} 数字（0-5）自动转字符串</li>
 *   <li>{@code damageInterval} 弹级全局伤害间隔 tick（2026-10-22 定案·独立维度）：>0 = 每 interval tick
 *       结算一次（持续重复场/帧伤，area/pierce/命中结算共用）；缺省 0 = 每实体只结算一次。不再是 area/pierce 的参数。</li>
 *   <li>{@code speed} 初速（本体默认 2.0）</li>
 *   <li>{@code lifetime} 存活 tick（本体默认 60）</li>
 * </ul>
 *
 * <p>弹永远是内置 {@code mage:spell_bolt}（2026-10-23 定案：不再支持外部弹射物）；视觉委托与粒子
 * 独立 {@code visual} 步骤（VisualStep）作用于已收集的弹，外观与逻辑解耦。
 */
public class ProjectileStep implements SpellStep {

    private final double damage;
    private final String damageType;
    private final int damageInterval;
    private final double speed;
    private final int lifetime;
    private final boolean antigravity;

    public double damage() { return damage; }
    public String damageType() { return damageType; }
    public int damageInterval() { return damageInterval; }
    public double speed() { return speed; }
    public int lifetime() { return lifetime; }
    public boolean antigravity() { return antigravity; }

    public ProjectileStep(JsonObject json) {
        this(json, StepDefaults.EMPTY, true); // 单参构造（旧折叠）默认 antigravity（保持旧行为=直线）
    }

    public ProjectileStep(JsonObject json, StepDefaults defs) {
        this(json, defs, true);
    }

    /** 显式指定无重力与否（SpellSteps 按原语 type 分发）。 */
    public ProjectileStep(JsonObject json, StepDefaults defs, boolean antigravity) {
        this.damage = json.has("damage") ? json.get("damage").getAsDouble() : defs.getDamageOr(0.0);
        this.damageType = readDamageType(json, defs);
        this.damageInterval = json.has("damageInterval") ? json.get("damageInterval").getAsInt() : 0;
        this.speed = json.has("speed") ? json.get("speed").getAsDouble() : defs.getSpeedOr(2.0);
        this.lifetime = json.has("lifetime") ? json.get("lifetime").getAsInt() : defs.getLifetimeOr(60);
        this.antigravity = antigravity;
    }

    /** 伤害类型：damageType 字符串优先；旧 ballType 数字向后兼容转字符串；再回落 defs。 */
    private static String readDamageType(JsonObject json, StepDefaults defs) {
        if (json.has("damageType")) return json.get("damageType").getAsString();
        if (json.has("ballType")) return SpellDamageHelper.fromLegacyInt(json.get("ballType").getAsInt());
        return defs.damageType();
    }

    @Override
    public void apply(CastContext ctx) {
        SpellProjectile p = new SpellProjectile(damage, damageType, speed, lifetime, antigravity, 0.0);
        p.damageInterval = damageInterval;
        ctx.addProjectile(p);
    }
}