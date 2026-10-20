package com.fantasyclouds.mage.spell;

/**
 * 法术流水线步骤（Noita 式「payload + 顺序修饰符」的最小单元）。
 *
 * <p>每把法杖的 {@code sequence} 是有序的 {@link SpellStep} 列表：
 * 步骤按序对 {@link CastContext} 作用——第一步通常是 {@code projectile}（推入基础弹），
 * 后续修饰符步骤（split/homing/pierce…）读取并改造上下文中已收集的待发射弹。
 * 全部步骤执行完由 {@link SpellPipeline} 统一发射。
 *
 * <p>类型对应 JSON {@code sequence[i].type}，由 {@link SpellSteps#parse} 分发解析。
 */
public interface SpellStep {

    /** 对施法上下文作用（收集弹 / 改造已收集的弹）。步骤之间通过上下文共享状态，顺序有语义。 */
    void apply(CastContext ctx);
}