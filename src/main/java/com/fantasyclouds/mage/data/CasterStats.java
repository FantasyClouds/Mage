package com.fantasyclouds.mage.data;

import net.minecraft.resources.ResourceLocation;

/**
 * 法杖配置数据，对应数据包 {@code data/<命名空间>/caster_stats/*.json}。
 *
 * <p>新格式用 {@code cost:{attribute, amount}} 声明消耗的资源属性（纯属性扣减，零依赖）；
 * 旧格式 {@code manaCost} 由 {@link CasterStatsLoader} 向后兼容解析为默认消耗 {@code mage:mana}。
 */
public record CasterStats(
        String item,                       // 物品注册名
        String name,                       // 可选描述
        String projectileType,             // 弹射物注册名；空 → 内置 mage:spell_bolt
        double ballDamage,
        int ballType,
        ResourceLocation costAttribute,    // 消耗的属性（当前值不足则拒绝施放）
        double costAmount,
        double projectileSpeed,
        int fastCooldown,
        int slowCooldown,
        boolean isOverwrite,               // 是否吞掉原物品右键行为
        int lifetime                       // 弹射物存活 ticks
) {}
