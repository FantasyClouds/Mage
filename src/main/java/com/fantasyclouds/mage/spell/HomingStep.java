package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

/**
 * 追踪修饰符：让待发射弹每 tick 向范围内最近的可锁目标转向（HomingStep）。
 * 施法者本体被排除在锁定之外。零依赖（服务端 SpellBolt tick 内直接转向 deltaMovement）。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code range} 锁定距离（格）；范围内最近目标才锁定，无则直线飞。</li>
 *   <li>{@code turn} 每 tick 转向角（度，缺省 15）；值越大越急，越小越平滑。</li>
 *   <li>{@code hostileOnly} 是否只锁定敌对目标（布尔，缺省 {@code true}）。敌对 = Enemy 原生攻击怪
 *       ∪ 正在攻击施法者 / 与施法者交战的生物（Target/HurtBy/Anger 仇恨态）；{@code false} 时也会
 *       追踪中立/被动生物（猪牛羊等）。无论何种模式，施法者驯养/所属的生物（OwnableEntity，
 *       含 马/狼/豹猫/鹦鹉 等）始终剔除。</li>
 * </ul>
 *
 * <p>示例：{@code homing(range:16, turn:12)} = 向 16 格内最近敌对目标每 tick 转 12°；
 * {@code homing(range:16, hostileOnly:false)} = 中立/被动生物也可锁定（仍剔除驯养物）。
 */
public class HomingStep implements SpellStep {

    private final double range;
    private final double turn;
    private final boolean hostileOnly;

    public HomingStep(JsonObject json) {
        this.range = json.has("range") ? json.get("range").getAsDouble() : 16.0;
        this.turn = json.has("turn") ? json.get("turn").getAsDouble() : 15.0;
        this.hostileOnly = json.has("hostileOnly") ? json.get("hostileOnly").getAsBoolean() : true;
        if (range <= 0) throw new IllegalArgumentException("homing range must be > 0");
    }

    @Override
    public void apply(CastContext ctx) {
        for (SpellProjectile p : ctx.pending()) {
            p.homingRange = range;
            p.homingTurn = turn;
            p.homingHostileOnly = hostileOnly;
            // 注册追踪运行时行为（顺序 = sequence 顺序）
            p.behaviors.add(new com.fantasyclouds.mage.entity.HomingBehavior(range, turn, hostileOnly));
        }
    }
}