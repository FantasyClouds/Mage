package com.fantasyclouds.mage.spell;

/**
 * 伤害统计组：某类弹的「单体伤害 × 数量」，供 HUD 显示「8x3」这类口径。
 * 由 {@link SpellAnalyzer#analyze} 从 sequence 静态推演（客户端无需可执行步骤）。
 *
 * @param perDamage 单体伤害（该组每发造成的伤害）
 * @param count     该组弹的数量
 */
public record DamageGroup(double perDamage, int count) {

    public DamageGroup {
        count = Math.max(1, count);
    }

    public DamageGroup withCount(int c) {
        return new DamageGroup(perDamage, c);
    }
}