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
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置法弹 {@code mage:spell_bolt}（零外部依赖）。
 * - 移动：{@link #launch} 只设 deltaMovement，x/y/zPower 恒 0（AbstractHurtingProjectile 的
 *   持续推进机制不参与，纯 deltaMovement 驱动速度恒定）。
 * - 生命周期：NBT {@code spell_death_time}（世界时间，持久，不受 /reload 影响）；
 * - 命中/修饰符：运行时行为由 {@link BoltBehavior} 列表驱动（Pierce/Homing/Area/Bounce 各一个
 *   行为实现，装配时由 Step 注册进 SpellProjectile、launch 时注入本类）——tick / onHitBlock /
 *   onHitEntity 只遍历行为列表，行为决定做什么与是否消费事件。顺序 = sequence 顺序，可组合、
 *   可叠加，加新原语不改本类。
 * - 伤害：命中实体走 SpellDamageHelper 统一魔法结算；类型着色走 SynchedEntityData 同步客户端。
 */
public class SpellBolt extends AbstractHurtingProjectile {

    /** 运行时行为列表（装配时注入；顺序 = sequence 顺序）。 */
    private final List<BoltBehavior> behaviors = new ArrayList<>();

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

    public SpellBolt(EntityType<? extends AbstractHurtingProjectile> type, Level level) {
        super(type, level);
    }

    /** 装配运行时行为（顺序 = sequence 顺序；split 各弹独立注入）。 */
    public void setBehaviors(List<BoltBehavior> list) {
        this.behaviors.clear();
        if (list != null) this.behaviors.addAll(list);
    }

    public boolean isAntigravity() {
        return this.antigravity;
    }

    /** 发射：只设 deltaMovement，x/y/zPower 保持 0。
     *  AbstractHurtingProjectile.tick 每 tick 做 deltaMovement += (xPower,yPower,zPower)（原版持续推进机制）；
     *  若把 xPower 设成发射速度 = 每 tick 累加一次初速 → 越飞越快（反弹后暴涨）。纯 deltaMovement 驱动速度恒定。 */
    public void launch(Vec3 movement, boolean antigravity) {
        this.antigravity = antigravity;
        this.setDeltaMovement(movement);
        this.xPower = 0;
        this.yPower = 0;
        this.zPower = 0;
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

    @Override
    public void tick() {
        // 受重力：super.tick 前直接改 deltaMovement（每 tick 沿 Y 减 GRAVITY）。
        // x/y/zPower 恒 0（launch 不设），AbstractHurtingProjectile.tick 的
        // deltaMovement += (xPower,yPower,zPower) 不叠加，重力只生效一次。
        if (!this.level().isClientSide && !this.antigravity) {
            this.setDeltaMovement(this.getDeltaMovement().add(0, -GRAVITY, 0));
        }
        super.tick();
        if (this.level().isClientSide) return;

        CompoundTag tag = this.getPersistentData();

        // 修饰符行为：按 sequence 顺序逐 tick 驱动（追踪/穿透/持续范围伤害场…）。
        for (BoltBehavior behavior : this.behaviors) {
            if (behavior.onTick(this, tag)) return; // 行为消费了本 tick（如穿透耗尽消失）
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

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.level().isClientSide) return;
        // 幂等：通用 ProjectileSpellDamageMixin 已结算过则不再结算（防双路径双倍伤害）
        if (this.getPersistentData().getBoolean("mage$spell_handled")) {
            this.discard();
            return;
        }
        CompoundTag tag = this.getPersistentData();

        // 行为驱动：按序跑命中钩子。行为消费（返回 true）则弹不消失（如 pierce 穿透）。
        boolean consumed = false;
        for (BoltBehavior behavior : this.behaviors) {
            if (behavior.onHitEntity(this, result, tag)) consumed = true;
        }
        if (consumed) return;

        // 无行为消费：默认直接命中结算 → 伤害 + 范围伤害（若配了 area）+ 点燃 + 消失。
        Entity owner = this.getOwner();
        Entity source = owner != null ? owner : this;
        SpellDamageHelper.applySpellDamage(this.level(), result.getEntity(), source, this);
        if (tag.getBoolean("spell_enchant_burst")) {
            result.getEntity().setSecondsOnFire(10);
        }
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.level().isClientSide) return;
        CompoundTag tag = this.getPersistentData();

        // 行为驱动：按序跑命中钩子。行为消费（返回 true）则弹不消失（如 bounce 反弹 / pierce 穿透）。
        boolean consumed = false;
        for (BoltBehavior behavior : this.behaviors) {
            if (behavior.onHitBlock(this, result, tag)) consumed = true;
        }
        if (consumed) return;

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