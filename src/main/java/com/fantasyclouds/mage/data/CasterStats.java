package com.fantasyclouds.mage.data;

import com.fantasyclouds.mage.spell.DamageGroup;
import com.fantasyclouds.mage.spell.SpellStep;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 法杖配置数据，对应数据包 {@code data/<命名空间>/caster_stats/*.json}。
 *
 * <p>新格式用 {@code sequence} 声明有序法术步骤（payload + 修饰符，Noita 式流水线）；
 * 消耗用 {@code cost:{attribute, amount}}（纯属性扣减，零依赖）。
 * 旧扁平字段（ballDamage/damageType/projectileSpeed/lifetime）保留：
 * - 作为 {@code StepDefaults}，sequence 里某步没写的字段回落用它；
 * - 作为 HUD 显示来源。
 * 旧格式 {@code manaCost} 由 {@link CasterStatsLoader} 向后兼容解析为默认消耗 {@code mage:mana}。
 * 伤害类型已是字符串（2026-10-22 定案）：{@code damageType}，不再用数字。
 * {@code projectileType} 弹种字段已废弃（2026-10-23 定案：统一内置 mage:spell_bolt，外观走 visual 委托）。
 *
 * <p>{@code sequence} 服务端永不为空（loader 保证：无 sequence 时折叠成单 projectile 步骤）——
 * 发射依据只有 {@link #sequence()} 一个入口，顶层扁平字段不直接参与发射。
 * 客户端经网络同步拿到的实例 {@code sequence} 为空表（HUD 只读顶层字段）——勿在客户端用 sequence。
 */
public record CasterStats(
        String item,                       // 物品注册名
        String name,                       // 可选描述
        double ballDamage,                 // 基础伤害（顶层默认 / HUD 显示）
        String damageType,                 // 伤害类型（顶层默认；字符串 id，如 "magic"/"fire"）
        ResourceLocation costAttribute,    // 消耗的属性（当前值不足则拒绝施放）
        double costAmount,
        double projectileSpeed,            // 初速（顶层默认）
        int fastCooldown,
        int slowCooldown,
        boolean isOverwrite,               // 是否吞掉原物品右键行为
        int lifetime,                      // 弹射物存活 ticks（顶层默认）
        List<SpellStep> sequence,          // 有序法术步骤（发射唯一依据，永不为空）
        List<DamageGroup> damageGroups     // 静态推演的伤害组（HUD「NxM」显示源；客户端经网络同步拿到）
) {

    /**
     * 旧 {@code int ballType} 兼容（SOFL 等已编译调用方，2026-10-24 补回）。
     * 由字符串 {@link #damageType} 反推旧数字：magic=0/fire=1/frost=2/lightning=3/void=4；未知=0。
     * 新代码请直接用 {@link #damageType()} 字符串，不要再依赖本方法。
     */
    public int ballType() {
        return switch (damageType == null ? "" : damageType) {
            case "fire" -> 1;
            case "frost" -> 2;
            case "lightning" -> 3;
            case "void" -> 4;
            default -> 0;
        };
    }

    /** sequence 内所有 projectile 步骤基础伤害之和（供 HUD 总伤预估，未来扩展）。 */
    public double totalProjectileDamage() {
        double sum = 0;
        for (SpellStep step : sequence) {
            if (step instanceof com.fantasyclouds.mage.spell.ProjectileStep p) {
                sum += p.damage();
            }
        }
        return sum;
    }
}