package com.fantasyclouds.mage.entity;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 范围伤害行为（lethality AcidicSlash 式持续范围伤害场）：
 * - onTick：飞行期间每 interval tick 对自身半径内所有实体结算范围伤害（掠过即持续伤）；
 * - onHitBlock/onHitEntity：碰撞瞬间额外结算一次范围伤害。
 * 伤害统一走 SpellDamageHelper（吃 L2 法伤）。不消费命中（弹是否消失由其他行为决定）。
 */
public class AreaBehavior implements BoltBehavior {

    private final double radius;
    private final int interval;

    public AreaBehavior(double radius, int interval) {
        this.radius = radius;
        this.interval = interval;
    }

    /** 结算一次范围伤害（供 onHit 复用；onHit 也要给 tag 才有伤害值）。 */
    private void apply(SpellBolt bolt, CompoundTag tag, Vec3 center) {
        Entity owner = bolt.getOwner();
        SpellDamageHelper.applyAreaDamage(bolt.level(), center, owner != null ? owner : bolt, tag);
    }

    @Override
    public boolean onTick(SpellBolt bolt, CompoundTag tag) {
        if (interval > 0 && bolt.level().getGameTime() % interval == 0) {
            apply(bolt, tag, bolt.position());
        }
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
