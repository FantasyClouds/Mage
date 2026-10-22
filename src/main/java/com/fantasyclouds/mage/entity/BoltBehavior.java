package com.fantasyclouds.mage.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 内置弹运行时行为（策略模式，替代 SpellBolt.tick/onHit 里的散落 if 判断）。
 *
 * <p>每个修饰符原语 = 一个行为实现，装配时由对应 Step 注册进 SpellProjectile.behaviors，
 * launch 时注入 SpellBolt。SpellBolt 在 tick / onHitBlock / onHitEntity 处按序遍历行为列表，
 * 每个行为决定自己做什么、以及是否「消费」这次事件（阻止默认的碰撞消失等）。
 * 顺序 = sequence 顺序，天然可组合、可叠加，加新原语不改 SpellBolt 主类。
 */
public interface BoltBehavior {

    /** 每 tick（服务端）调用。返回 true = 本次 tick 已被本行为「消费」并阻止后续默认处理。 */
    default boolean onTick(SpellBolt bolt, CompoundTag tag) { return false; }

    /** 命中方块。返回 true = 已消费该命中（弹不消失，如 bounce 反弹 / pierce 穿透）。 */
    default boolean onHitBlock(SpellBolt bolt, BlockHitResult result, CompoundTag tag) { return false; }

    /** 命中实体。返回 true = 已消费该命中（弹不消失，如 pierce 穿透）。 */
    default boolean onHitEntity(SpellBolt bolt, EntityHitResult result, CompoundTag tag) { return false; }

    /** 复制（split 每颗弹需要独立状态，如 pierce 剩余次数）。无状态行为可返回 this。 */
    default BoltBehavior copy() { return this; }
}
