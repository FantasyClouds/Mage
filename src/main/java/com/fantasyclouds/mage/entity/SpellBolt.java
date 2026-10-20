package com.fantasyclouds.mage.entity;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 内置法弹 {@code mage:spell_bolt}（零外部依赖）。
 * - 移动：{@link #launch} 设置 deltaMovement 与 x/y/zPower（AbstractHurtingProjectile.tick 会从 xPower 重设速度）；
 * - 生命周期：NBT {@code spell_death_time}（世界时间，持久，不受 /reload 影响）；
 * - 命中：按 NBT spell_damage/spell_type/spell_enchant_bonus 走 {@link SpellDamageHelper} 统一魔法结算；
 * - 类型着色：spell_type 走 SynchedEntityData 同步给客户端渲染。
 */
public class SpellBolt extends AbstractHurtingProjectile {

    private static final EntityDataAccessor<Integer> DATA_SPELL_TYPE =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.INT);

    public SpellBolt(EntityType<? extends AbstractHurtingProjectile> type, Level level) {
        super(type, level);
    }

    /** 发射：同时设置 deltaMovement 与 x/y/zPower（后者是本类 tick 的实际驱动源）。 */
    public void launch(Vec3 movement) {
        this.setDeltaMovement(movement);
        this.xPower = movement.x;
        this.yPower = movement.y;
        this.zPower = movement.z;
    }

    public void setSpellType(int type) {
        this.entityData.set(DATA_SPELL_TYPE, type);
    }

    public int getSpellType() {
        return this.entityData.get(DATA_SPELL_TYPE);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SPELL_TYPE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        CompoundTag tag = this.getPersistentData();
        if (tag.contains("spell_death_time") && this.level().getGameTime() >= tag.getLong("spell_death_time")) {
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!this.level().isClientSide) {
            // 幂等：通用 ProjectileSpellDamageMixin 已结算过则不再结算（防双路径双倍伤害）
            if (this.getPersistentData().getBoolean("mage$spell_handled")) {
                this.discard();
                return;
            }
            Entity owner = this.getOwner();
            SpellDamageHelper.applySpellDamage(this.level(), result.getEntity(), owner != null ? owner : this, this);
            // 爆裂法术（mage:burst）：命中点燃目标 10 秒（复刻 adventuresmod BURST 行为）
            if (this.getPersistentData().getBoolean("spell_enchant_burst")) {
                result.getEntity().setSecondsOnFire(10);
            }
            this.discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (!this.level().isClientSide) {
            this.discard();
        }
    }

    @Override
    protected float getInertia() {
        return 1.0f; // 无衰减
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }
}
