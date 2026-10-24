package com.fantasyclouds.mage.spell;

/**
 * 步骤解析的「缺省回落」载体：sequence 里某步没写的字段，回落到 caster_stats 顶层旧字段
 * （旧扁平格式的 ballDamage/damageType/projectileSpeed/lifetime）。
 *
 * <p>让 sequence 里的 {@code projectile} 步骤可以只写差异字段，顶层仍有兜底；
 * 纯 sequence 配置（顶层无旧字段）时各默认均取本身缺省值。
 * <p>旧顶层字段 {@code projectileType}（弹种类）已废弃（2026-10-23 定案：不再有外部弹射物），
 * loader 把它自动转成 {@code visual.entity} 外观委托，不再作为步骤默认。
 */
public record StepDefaults(
        double damage,
        String damageType,       // 字符串伤害类型 id（"magic"/"fire"/…）；空 = 步骤自己兜底
        double speed,
        int lifetime
) {
    public static final StepDefaults EMPTY = new StepDefaults(0, "", 0, 0);

    public double getDamageOr(double fallback) { return damage > 0 ? damage : fallback; }
    public double getSpeedOr(double fallback) { return speed > 0 ? speed : fallback; }
    public int getLifetimeOr(int fallback) { return lifetime > 0 ? lifetime : fallback; }
}