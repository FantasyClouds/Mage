package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 穿透修饰符：让待发射弹「不产生碰撞（穿墙），但仍伤害实体」，每个实体只结算一次。
 *
 * <p>实现参考 lethality 模组 {@code AcidicSlashEntity}：抑制原生命中/方块碰撞，
 * 自身 tick 扫 AABB 找人 + UUID 去重。Pierce 步骤把 {@code pierceCount} 写进弹配置，
 * 发射时落成 NBT {@code spell_pierce}，SpellBolt 据此进入穿透模式；
 * {@code ProjectileSpellDamageMixin} 识别该 NBT 放行（穿透由弹自身结算，不走通用接管）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code count} 可穿过的实体数（命中 count 个后消失；缺省 3）。配置相邻多次 pierce 累加。</li>
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
        }
    }
}