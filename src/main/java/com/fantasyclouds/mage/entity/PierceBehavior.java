package com.fantasyclouds.mage.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 穿透行为（2026-10-22 重构·纯飞行逻辑）。
 *
 * <p>pierce 只决定法术<b>能不能穿实体/穿墙</b>（三正交维度之一=碰撞策略）：
 * <ul>
 *   <li>{@code onHitBlock}/{@code onHitEntity} 返回 true = 命中不消失（穿墙/穿过实体继续飞）；</li>
 *   <li>每穿过一个<b>不同</b>实体消耗一次 {@code remaining}，穿够 count 个后消失；</li>
 *   <li><b>不结算任何伤害</b>——伤害由 {@code SpellBolt.onHitEntity} 通用命中结算负责
 *       （shouldDamage 按弹级 interval 裁决：缺省每实体一次 / 配 interval 每 interval tick 重复）。</li>
 * </ul>
 * 与 area（决定伤害生效范围）、interval（弹级伤害间隔）完全正交。
 */
public class PierceBehavior implements BoltBehavior {

    private int remaining;
    private String piercedUuids = "";

    public PierceBehavior(int count) {
        this.remaining = count;
    }

    @Override
    public boolean onTick(SpellBolt bolt, CompoundTag tag) {
        return false; // 纯飞行：无 tick 扫描（伤害走通用命中结算）
    }

    @Override
    public boolean onHitBlock(SpellBolt bolt, BlockHitResult result, CompoundTag tag) {
        return true; // 穿墙不消失
    }

    @Override
    public boolean onHitEntity(SpellBolt bolt, EntityHitResult result, CompoundTag tag) {
        Entity target = result.getEntity();
        if (target != null && !alreadyPierced(target)) {
            markPierced(target);
            remaining--; // 穿过一个不同实体消耗一次（同一实体反复命中不重复消耗）
        }
        if (remaining <= 0) {
            bolt.despawn(); // 穿够 count 个 → 消失（走统一消失入口，触发 onDeath 粒子）
            return false;
        }
        return true; // 穿过：不消失
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
