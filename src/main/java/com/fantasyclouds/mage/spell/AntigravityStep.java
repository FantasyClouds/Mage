package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 无重力修饰符：把已收集的待发射弹标记为无重力（直线飞行，不受重力下拉）。
 *
 * <p>它是<b>修饰符</b>而非 payload：不定义弹的任何属性（speed/damage/ballType 一律不带），
 * 只改弹的 {@code antigravity} 标记。真正的弹由 {@code projectile} 步骤定义——sequence 里
 * 没有 projectile 步骤时本步骤静默（无弹可改，cast 判定不发射）。
 *
 * <p>JSON：{@code {"type":"antigravity"}}，无参数。
 */
public class AntigravityStep implements SpellStep {

    public AntigravityStep(JsonObject json) {
        // 修饰符无独立参数；不解析任何字段（antigravity 不该带 payload 属性）
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.antigravity = true;
        }
    }
}
