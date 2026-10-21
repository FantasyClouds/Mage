package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 反弹修饰符：让待发射弹撞方块时<b>按碰撞面反射速度继续飞</b>，反弹 {@code count} 次后消失。
 * 与穿透互补：pierce 穿实体不消失、bounce 撞墙反弹——两者可叠加（穿完实体仍弹墙）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code count} 反弹次数（缺省 3）。用完后撞方块才消失。</li>
 * </ul>
 *
 * <p>实现：SpellBolt.onHitBlock 读 {@code spell_bounce}，按 BlockHitResult 碰撞面方向反射 deltaMovement 分量。
 */
public class BounceStep implements SpellStep {

    private final int count;

    public BounceStep(JsonObject json) {
        this.count = json.has("count") ? json.get("count").getAsInt() : 3;
        if (count < 0) throw new IllegalArgumentException("bounce count must be >= 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.bounceCount += count;
        }
    }
}