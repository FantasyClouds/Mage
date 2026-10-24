package com.fantasyclouds.mage.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 范围伤害行为（2026-10-22 重构·纯范围维度）。
 *
 * <p>area 只决定<b>伤害生效的范围</b>（三正交维度之一=空间）：
 * 对自身 radius 格内所有实体结算伤害。伤害<b>次数</b>不归 area——
 * 统一走 {@code SpellBolt.shouldDamage/dealDamage}（弹级 {@code damageInterval} 裁决：
 * 缺省每实体一次 / 配 interval 每 interval tick 重复）。与 pierce（碰撞策略）、interval（时间）完全正交。
 *
 * <p>结算时机：onTick 飞行期间（持续作用）+ onHitBlock/onHitEntity 碰撞瞬间额外结算一次。
 * 不消费命中（弹是否消失由其他行为决定）。
 */
public class AreaBehavior implements BoltBehavior {

    private final double radius;

    public AreaBehavior(double radius) {
        this.radius = radius;
    }

    /** 结算范围伤害：对 center 半径内每个实体，走弹的通用伤害次数裁决（shouldDamage/dealDamage）。 */
    private void apply(SpellBolt bolt, CompoundTag tag, Vec3 center) {
        AABB box = new AABB(center, center).inflate(radius);
        for (LivingEntity target : bolt.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != bolt.getOwner() && e.isAlive())) {
            if (bolt.shouldDamage(target)) {
                bolt.dealDamage(target);
            }
        }
    }

    @Override
    public boolean onTick(SpellBolt bolt, CompoundTag tag) {
        // 飞行期间持续作用：弹级 interval>0 时每 interval tick 全量重复；interval=0 每实体一次。
        apply(bolt, tag, bolt.position());
        return false;
    }

    @Override
    public boolean onHitBlock(SpellBolt bolt, BlockHitResult result, CompoundTag tag) {
        apply(bolt, tag, bolt.position());
        return false; // 不消费：是否消失由 bounce/pierce 决定
    }

    @Override
    public boolean onHitEntity(SpellBolt bolt, EntityHitResult result, CompoundTag tag) {
        apply(bolt, tag, result.getEntity().position());
        return false; // 不消费
    }
}
