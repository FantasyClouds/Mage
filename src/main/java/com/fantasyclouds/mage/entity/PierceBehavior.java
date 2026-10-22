package com.fantasyclouds.mage.entity;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 穿透行为：弹不因碰撞消失，每 tick 扫描周围实体（UUID 去重、每实体一次），
 * 穿够 count 个实体后消失。onHitBlock/onHitEntity 恒消费（不消失）。
 * 参考 lethality AcidicSlashEntity。
 */
public class PierceBehavior implements BoltBehavior {

    private static final double SCAN_RADIUS = 0.9;

    private int remaining;
    private String piercedUuids = "";

    public PierceBehavior(int count) {
        this.remaining = count;
    }

    public boolean isActive() {
        return remaining > 0;
    }

    @Override
    public boolean onTick(SpellBolt bolt, CompoundTag tag) {
        if (remaining <= 0) return false;
        Entity owner = bolt.getOwner();
        AABB box = bolt.getBoundingBox().inflate(SCAN_RADIUS);
        for (LivingEntity target : bolt.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != owner && e.isAlive() && !alreadyPierced(e))) {
            if (remaining <= 0) break;
            markPierced(target);
            remaining--;
            SpellDamageHelper.applySpellDamage(bolt.level(), target, owner != null ? owner : bolt, bolt);
            if (tag.getBoolean("spell_enchant_burst")) {
                target.setSecondsOnFire(10);
            }
        }
        if (remaining <= 0) {
            bolt.discard();
            return true;
        }
        return false;
    }

    @Override
    public boolean onHitBlock(SpellBolt bolt, BlockHitResult result, CompoundTag tag) {
        return true; // 穿透：穿墙不消失
    }

    @Override
    public boolean onHitEntity(SpellBolt bolt, EntityHitResult result, CompoundTag tag) {
        return true; // 穿透：命中实体不在此结算/不消失，tick 扫描负责
    }

    @Override
    public BoltBehavior copy() {
        PierceBehavior p = new PierceBehavior(this.remaining);
        p.piercedUuids = this.piercedUuids;
        return p;
    }

    private boolean alreadyPierced(Entity e) {
        if (piercedUuids.isEmpty()) return false;
        return (" " + piercedUuids + " ").contains(" " + e.getUUID() + " ");
    }

    private void markPierced(Entity e) {
        if (piercedUuids.isEmpty()) {
            piercedUuids = e.getUUID().toString();
        } else if (!alreadyPierced(e)) {
            piercedUuids += " " + e.getUUID().toString();
        }
    }
}
