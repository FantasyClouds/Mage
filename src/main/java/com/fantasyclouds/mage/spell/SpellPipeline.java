package com.fantasyclouds.mage.spell;

import com.fantasyclouds.mage.data.CasterStats;

/**
 * 法术流水线执行器：按序跑完 {@link CasterStats#sequence()} 的所有步骤。
 *
 * <p>顺序语义（Noita 式）：
 * - 第一个 {@code projectile} 步骤定义基础弹；
 * - 之后的修饰符步骤按序改造该弹（分裂/追踪/穿透…）；
 * - 步骤之间通过 {@link CastContext} 共享「待发射弹」列表，因此顺序有实质意义。
 *
 * <p>两种消费方式：
 * - {@link #cast}：跑完步骤后<b>发射 Mage 内置弹</b>（isOverwrite=true 的接管路径）；
 * - {@link #applySteps}：只跑步骤、<b>不发射</b>（isOverwrite=false 路径——原版模组自己发弹，
 *   我们只把待发弹的配置序列化成 NBT，随后由 {@code ExternalProjectileTagging} 打到原版弹身上）。
 *
 * <p>{@link #cast} 返回 {@code true} = 至少一颗弹真正生成并加入世界（调用方据此才扣消耗——
 * "没真正施法不扣蓝"）。判定依据是 {@code launch} 结果而非 pending 是否为空：实体类型无效/创建失败时
 * pending 非空但 launch 返回 false，此时不应扣蓝（2026-10-21 修：杜绝"配置弹查不到实体还白扣蓝"）。
 */
public final class SpellPipeline {

    private SpellPipeline() {}

    /** 只执行 sequence 步骤，把待发弹装进 {@link CastContext#pending()}，不生成任何实体。 */
    public static void applySteps(CastContext ctx, CasterStats stats) {
        for (SpellStep step : stats.sequence()) {
            try {
                step.apply(ctx);
            } catch (RuntimeException e) {
                com.fantasyclouds.mage.Mage.LOGGER.error("[MageCaster] Spell step failed: {}", step.getClass().getSimpleName(), e);
            }
        }
    }

    /** 执行 sequence 并发射内置 mage:spell_bolt。 */
    public static boolean cast(CastContext ctx, CasterStats stats) {
        applySteps(ctx, stats);
        return ctx.launchAll();
    }
}