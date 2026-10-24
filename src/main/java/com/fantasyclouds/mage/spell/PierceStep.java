package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 穿透修饰符（2026-10-22 重构·纯飞行逻辑）：让待发射弹「不产生碰撞（穿墙/穿过实体），
 * 命中不消失」，每穿过一个不同实体消耗一次，穿够 {@code count} 个后消失。
 *
 * <p>伤害次数与 pierce 无关（三正交维度）：pierce 只决定能不能穿；
 * 伤害由 {@code SpellBolt.onHitEntity} 通用命中结算（按弹级 {@code damageInterval} 裁决）——
 * 缺省每实体一次，配 {@code projectile.damageInterval} 则每 interval tick 重复（帧伤）。
 * area 另管伤害生效范围。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code count} 可穿过的不同实体数（穿够 count 个后消失；缺省 3）。配置相邻多次 pierce 累加。</li>
 * </ul>
 *
 * <p>穿透只对内置 SpellBolt 生效；外部弹（adventuresmod:spell 等）字段有各自碰撞逻辑，穿透会被原生 discard 吃掉。
 */
public class PierceStep implements SpellStep {

    private final int count;

    public int count() { return count; }

    public PierceStep(JsonObject json) {
        this.count = json.has("count") ? json.get("count").getAsInt() : 3;
        if (count < 0) throw new IllegalArgumentException("pierce count must be >= 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.pierceCount += count;
            // 注册穿透运行时行为（顺序 = sequence 顺序；split 时深拷贝独立剩余次数）
            p.behaviors.add(new com.fantasyclouds.mage.entity.PierceBehavior(count));
        }
    }
}
