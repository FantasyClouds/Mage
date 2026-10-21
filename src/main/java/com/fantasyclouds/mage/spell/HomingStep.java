package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 追踪修饰符：让待发射弹每 tick 向范围内最近的敌对目标转向（HomingStep）。
 * 施法者本体被排除在锁定之外。零依赖（服务端 SpellBolt tick 内直接转向 deltaMovement）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code range} 锁定距离（格）；范围内最近目标才锁定，无则直线飞。</li>
 *   <li>{@code turn} 每 tick 转向角（度，缺省 15）；值越大越急，越小越平滑。</li>
 * </ul>
 *
 * <p>示例：{@code homing(range:16, turn:12)} = 向 16 格内最近目标每 tick 转 12°。
 */
public class HomingStep implements SpellStep {

    private final double range;
    private final double turn;

    public HomingStep(JsonObject json) {
        this.range = json.has("range") ? json.get("range").getAsDouble() : 16.0;
        this.turn = json.has("turn") ? json.get("turn").getAsDouble() : 15.0;
        if (range <= 0) throw new IllegalArgumentException("homing range must be > 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.homingRange = range;
            p.homingTurn = turn;
        }
    }
}