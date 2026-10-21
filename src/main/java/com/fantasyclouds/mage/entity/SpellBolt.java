package com.fantasyclouds.mage.entity;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * 内置法弹 {@code mage:spell_bolt}（零外部依赖）。
 * - 移动：{@link #launch} 设置 deltaMovement 与 x/y/zPower（AbstractHurtingProjectile.tick 会从 xPower 重设速度）；
 * - 生命周期：NBT {@code spell_death_time}（世界时间，持久，不受 /reload 影响）；
 * - 命中：按 NBT spell_damage/spell_type/spell_enchant_bonus 走 {@link SpellDamageHelper} 统一魔法结算；
 * - 类型着色：spell_type 走 SynchedEntityData 同步给客户端渲染；
 * - 穿透：NBT {@code spell_pierce} > 0 时进入穿透模式——穿过实体/方块不消失、每实体只结算一次
 *   （UUID 去重）、命中 {@code count} 个后消失。参考 lethality AcidicSlashEntity。
 */
public class SpellBolt extends AbstractHurtingProjectile {

    private static final EntityDataAccessor<Integer> DATA_SPELL_TYPE =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_SPELL_VISUAL =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_SPELL_YAW_OFFSET =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.FLOAT);
    // 发射方向（单位向量，SynchedEntityData 同步给客户端）——客户端实体 deltaMovement 恒为 0
    // （launch 只在服务端跑、deltaMovement 不同步），视觉代理/目标 renderer 需要"朝哪飞"只能靠这里
    private static final EntityDataAccessor<Float> DATA_SPELL_DIR_X =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPELL_DIR_Y =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SPELL_DIR_Z =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.FLOAT);

    /** 无重力（直线）？默认 true，对应 antigravity 原语（当前主流法术）。projectile 原语为 false 走重力抛物线。 */
    private boolean antigravity = true;

    /** 穿透剩余次数（NBT spell_pierce 初始化）；<=0 时非穿透。 */
    private int pierceRemaining = 0;

    /** 穿透模式已结算过的实体 UUID（每 tick AABB 扫描用）；持久 NBT spell_pierced 跨存档兜底。 */
    private String piercedUuids = "";

    public SpellBolt(EntityType<? extends AbstractHurtingProjectile> type, Level level) {
        super(type, level);
    }

    /** 发射：同时设置 deltaMovement 与 x/y/zPower（后者是本类 tick 的实际驱动源）。 */
    public void launch(Vec3 movement, boolean antigravity) {
        this.antigravity = antigravity;
        this.setDeltaMovement(movement);
        this.xPower = movement.x;
        this.yPower = movement.y;
        this.zPower = movement.z;
        // 同步发射方向到客户端（单位向量）——视觉代理/目标 renderer 需要它算朝向
        double len = movement.length();
        this.entityData.set(DATA_SPELL_DIR_X, (float) (len > 0 ? movement.x / len : 0));
        this.entityData.set(DATA_SPELL_DIR_Y, (float) (len > 0 ? movement.y / len : 0));
        this.entityData.set(DATA_SPELL_DIR_Z, (float) (len > 0 ? movement.z / len : 0));
    }

    /** 客户端取发射方向（单位向量；未发射/无方向 = 零向量）。 */
    public Vec3 getSpellDirection() {
        return new Vec3(
                this.entityData.get(DATA_SPELL_DIR_X),
                this.entityData.get(DATA_SPELL_DIR_Y),
                this.entityData.get(DATA_SPELL_DIR_Z));
    }

    /** 受重力弹的逐 tick 重力加速度（y 方向，每 tick 减；值越小越平）。 */
    private static final double GRAVITY = 0.04;
    /** 穿透模式每 tick 扫描半径（不含弹本体碰撞箱）。 */
    private static final double PIERCE_RADIUS = 0.9;

    public void setSpellType(int type) {
        this.entityData.set(DATA_SPELL_TYPE, type);
    }

    public int getSpellType() {
        return this.entityData.get(DATA_SPELL_TYPE);
    }

    /** 视觉委托：目标实体注册名（同步到客户端供渲染器使用）；空 = 默认 billboard。 */
    public void setSpellVisual(String visual) {
        this.entityData.set(DATA_SPELL_VISUAL, visual == null ? "" : visual);
    }

    public String getSpellVisual() {
        return this.entityData.get(DATA_SPELL_VISUAL);
    }

    /** 视觉委托的水平偏角校准（度，0 = 不偏；目标模型自带朝向基准时用来转正）。 */
    public void setSpellYawOffset(float offset) {
        this.entityData.set(DATA_SPELL_YAW_OFFSET, offset);
    }

    public float getSpellYawOffset() {
        return this.entityData.get(DATA_SPELL_YAW_OFFSET);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SPELL_TYPE, 0);
        this.entityData.define(DATA_SPELL_VISUAL, "");
        this.entityData.define(DATA_SPELL_YAW_OFFSET, 0f);
        this.entityData.define(DATA_SPELL_DIR_X, 0f);
        this.entityData.define(DATA_SPELL_DIR_Y, 0f);
        this.entityData.define(DATA_SPELL_DIR_Z, 0f);
    }

    private boolean isPiercing() {
        return pierceRemaining > 0;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide && !this.antigravity) {
            // 受重力抛物线：每 tick 沿 Y 减速度（xPower 机制在 super.tick 会从 deltaMovement/xPower 重设速度）
            this.yPower -= GRAVITY;
            this.setDeltaMovement(this.getDeltaMovement().add(0, -GRAVITY, 0));
        }
        super.tick();
        if (this.level().isClientSide) return;

        CompoundTag tag = this.getPersistentData();
        if (tag.contains("spell_pierce") && pierceRemaining == 0) {
            pierceRemaining = tag.getInt("spell_pierce");
            piercedUuids = tag.getString("spell_pierced");
        }

        // 穿透模式：每 tick 扫描周围实体，未结算过的结算（每实体一次 = 消耗一次穿透），count 用完即消失
        if (isPiercing()) {
            Entity owner = this.getOwner();
            AABB box = this.getBoundingBox().inflate(PIERCE_RADIUS);
            for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != owner && e.isAlive() && !alreadyPierced(e))) {
                if (pierceRemaining <= 0) break;
                markPierced(target);
                pierceRemaining--; // 命中一个目标消耗一次，即使伤害被盔甲减免也计数（保证"穿N个"可控）
                SpellDamageHelper.applySpellDamage(this.level(), target, owner != null ? owner : this, this);
                if (tag.getBoolean("spell_enchant_burst")) {
                    target.setSecondsOnFire(10);
                }
            }
            tag.putString("spell_pierced", piercedUuids);
            if (pierceRemaining <= 0) {
                this.discard();
                return;
            }
        }

        // 拖尾粒子（NBT spell_particles 列表；服务端每 tick 在弹位置发，可多种）
        spawnTrailParticles(tag);

        if (tag.contains("spell_death_time") && this.level().getGameTime() >= tag.getLong("spell_death_time")) {
            this.discard();
        }
    }

    /** 服务端每 tick 发拖尾粒子（可多种，遍历 spell_particles 列表；单个粒子坏/查不到静默跳过）。 */
    private void spawnTrailParticles(CompoundTag tag) {
        if (this.level().isClientSide) return;
        net.minecraft.nbt.ListTag list = tag.getList("spell_particles", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (list.isEmpty()) return;
        for (net.minecraft.nbt.Tag raw : list) {
            if (!(raw instanceof CompoundTag pt)) continue;
            String id = pt.getString("id");
            if (id.isEmpty()) continue;
            try {
                ParticleType<?> type = ForgeRegistries.PARTICLE_TYPES.getValue(new ResourceLocation(id));
                if (type == null) continue;
                int count = pt.getInt("count");
                if (count <= 0) count = 2;
                // 简单粒子直接作为 ParticleOptions；复杂粒子（需 NBT）跳过以免 API 强转错
                if (type instanceof SimpleParticleType simple) {
                    ((ServerLevel) this.level()).sendParticles(simple,
                            this.getX(), this.getY(), this.getZ(), count,
                            this.random.nextDouble() * 0.1 - 0.05,
                            this.random.nextDouble() * 0.1 - 0.05,
                            this.random.nextDouble() * 0.1 - 0.05, 0.02);
                }
            } catch (Throwable t) {
                // 单个粒子异常：跳过，不影响弹
            }
        }
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

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.level().isClientSide) return;
        if (isPiercing()) return; // 穿透：不在此结算/不消失，tick AABB 扫描负责（每实体一次）
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

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.level().isClientSide) return;
        if (isPiercing()) return; // 穿透：穿墙不消失
        this.discard();
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