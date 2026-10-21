package com.fantasyclouds.mage.spell;

import com.fantasyclouds.mage.data.CasterStats;

/**
 * 法术流水线执行器：按序跑完 {@link CasterStats#sequence()} 的所有步骤，然后统一发射。
 *
 * <p>顺序语义（Noita 式）：
 * - 第一个 {@code projectile} 步骤定义基础弹；
 * - 之后的修饰符步骤按序改造该弹（分裂/追踪/穿透…）；
 * - 步骤之间通过 {@link CastContext} 共享「待发射弹」列表，因此顺序有实质意义。
 *
 * <p>该执行器是本模组的施法正入口——SpellShootHandler 检查资源/冷却后调它，
 * 不再自己拼装实体（旧直接发射逻辑迁入 {@link SpellProjectile#launch}）。
 *
 * <p>返回 {@code true} = 至少一颗弹真正生成并加入世界（调用方据此才扣消耗——"没真正施法不扣蓝"）。
 * 判定依据是 {@code launch} 结果而非 pending 是否为空：实体类型无效/创建失败时 pending 非空但
 * launch 返回 false，此时不应扣蓝（2026-10-21 修：杜绝"配置弹查不到实体还白扣蓝"）。
 */
public final class SpellPipeline {

    private SpellPipeline() {}

    public static boolean cast(CastContext ctx, CasterStats stats) {
        for (SpellStep step : stats.sequence()) {
            try {
                step.apply(ctx);
            } catch (RuntimeException e) {
                com.fantasyclouds.mage.Mage.LOGGER.error("[MageCaster] Spell step failed: {}", step.getClass().getSimpleName(), e);
            }
        }
        return ctx.launchAll();
    }
}