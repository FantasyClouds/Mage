package com.fantasyclouds.mage.spell;

/**
 * 步骤解析的「缺省回落」载体：sequence 里某步没写的字段，回落到 caster_stats 顶层旧字段
 * （旧扁平格式的 projectileType/ballDamage/ballType/projectileSpeed/lifetime）。
 *
 * <p>让 sequence 里的 {@code projectile} 步骤可以只写差异字段，顶层仍有兜底；
 * 纯 sequence 配置（顶层无旧字段）时各默认均取本身缺省值。
 */
public record StepDefaults(
        String projectileType,
        double damage,
        int ballType,
        double speed,
        int lifetime
) {
    public static final StepDefaults EMPTY = new StepDefaults("", 0, 0, 0, 0);

    public double getDamageOr(double fallback) { return damage > 0 ? damage : fallback; }
    public double getSpeedOr(double fallback) { return speed > 0 ? speed : fallback; }
    public int getLifetimeOr(int fallback) { return lifetime > 0 ? lifetime : fallback; }
}