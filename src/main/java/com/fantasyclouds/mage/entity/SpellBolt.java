package com.fantasyclouds.mage.entity;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 内置法弹 {@code mage:spell_bolt}（零外部依赖）。
 * - 移动：{@link #launch} 设置 deltaMovement 与 x/y/zPower（AbstractHurtingProjectile.tick 会从 xPower 重设速度）；
 * - 生命周期：NBT {@code spell_death_time}（世界时间，持久，不受 /reload 影响）；
 * - 命中：按 NBT spell_damage/spell_type/spell_enchant_bonus 走 {@link SpellDamageHelper} 统一魔法结算；
 * - 类型着色：spell_type 走 SynchedEntityData 同步给客户端渲染；
 * - 穿透：NBT {@code spell_pierce} > 0 时进入穿透模式——穿过实体/方块不消失、每实体只结算一次
 *   （UUID 去重）、命中 {@code count} 个后消失。参考 lethality AcidicSlashEntity。
 * - 追踪：NBT {@code spell_homing} > 0 时锁定范围内最近的可锁目标转向（度/tick 由 spell_homing_turn）；
 *   {@code spell_homing_hostile}=true（缺省）只锁敌对（Enemy ∪ 对施法者仇恨），false 也锁中立/被动，
 *   施法者驯养生物（OwnableEntity）在任何模式下都恒被排除。
 * - 范围伤害：NBT {@code spell_area} + {@code spell_area_interval} 时构成持续范围伤害场——
 *   飞行期间每 interval tick 对自身半径内实体结算范围伤害（lethality AcidicSlash 式），
 *   碰撞瞬间另额外结算一次。伤害统一走 SpellDamageHelper 魔法结算。
 * - 反弹：NBT {@code spell_bounce} > 0 时撞方块按碰撞面反射速度继续飞，count 耗尽才消失。
 *   严格对齐 conjurer BouncingBallEntity：反射速度大小不变（无恢复系数/无能量耗尽判别），
 *   受重力时保底起跳 vy 防贴地抖动，反弹多少次速度恒定、高度恒定，纯粹靠 count 兜底。
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
    /** 穿透模式每 tick 扫描半径（不含弹本体碰撞箱）。 */
    private static final double PIERCE_RADIUS = 0.9;
    /** 反弹后的最小垂直分量（起跳速度）。对齐 conjurer 无能量衰减的弹跳；但极小的 vy 会让
     *  受重力弹贴地逐 tick 反复撞（纯靠 bounce 次数兜底会烧完 count），给一个保底离面速度。 */
    private static final double MIN_BOUNCE_VY = 0.15;

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
        // 受重力：super.tick 前直接改 deltaMovement（每 tick 沿 Y 减 GRAVITY）。
        // x/y/zPower 恒 0（launch 不设），AbstractHurtingProjectile.tick 的
        // deltaMovement += (xPower,yPower,zPower) 不叠加，重力只生效一次。
        if (!this.level().isClientSide && !this.antigravity) {
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

        // 追踪（NBT spell_homing > 0）：每 tick 向范围内最近非施法者目标转向
        if (tag.getDouble("spell_homing") > 0) {
            doHoming(tag);
        }

        // 持续范围伤害场（lethality AcidicSlash 式）：spell_area>0 时飞行期间每 interval tick
        // 对自身半径内所有实体结算一次范围伤害（不是只碰撞时爆）。伤害走统一魔法结算、吃 L2 法伤。
        int areaInterval = tag.getInt("spell_area_interval");
        if (tag.getDouble("spell_area") > 0 && areaInterval > 0 && this.level().getGameTime() % areaInterval == 0) {
            Entity owner = this.getOwner();
            SpellDamageHelper.applyAreaDamage(this.level(), this.position(), owner != null ? owner : this, tag);
        }

        // 拖尾粒子（NBT spell_particles 列表；服务端每 tick 在弹位置发，可多种）
        spawnTrailParticles(tag);

        if (tag.contains("spell_death_time") && this.level().getGameTime() >= tag.getLong("spell_death_time")) {
            this.discard();
        }
    }

    /** 追踪：锁定范围内最近的可锁目标，把 deltaMovement 向它转 homingTurn 度（单位=格，度/tick）。
     *  可锁性由 spell_homing_hostile 控制过滤（false 时非敌对也对准，驯养物仍排除）。 */
    private void doHoming(CompoundTag tag) {
        double range = tag.getDouble("spell_homing");
        double turnDeg = tag.getDouble("spell_homing_turn");
        if (turnDeg <= 0) turnDeg = 15.0;
        Entity owner = this.getOwner();
        boolean hostileOnly = !tag.contains("spell_homing_hostile") || tag.getBoolean("spell_homing_hostile");
        Vec3 pos = this.position();
        AABB box = this.getBoundingBox().inflate(range);
        LivingEntity target = null;
        double best = range * range;
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != owner && e.isAlive() && isLockable(e, owner, hostileOnly))) {
            double d = e.distanceToSqr(this);
            if (d < best) { best = d; target = e; }
        }
        if (target == null) return;

        Vec3 current = this.getDeltaMovement();
        double speed = current.length();
        if (speed < 1.0E-6) return;
        Vec3 curDir = current.scale(1.0 / speed);
        Vec3 wanted = target.getEyePosition().subtract(pos).normalize();

        double dot = Mth.clamp(curDir.dot(wanted), -1.0, 1.0);
        double angleDeg = Math.acos(dot) * 180.0 / Math.PI;
        if (angleDeg < 0.01 || angleDeg >= 179.0) return; // 已对准 / 背对（axis 退化）都不转

        double step = Math.min(turnDeg, angleDeg);
        Vec3 axis = curDir.cross(wanted);
        if (axis.lengthSqr() < 1.0E-8) return;
        axis = axis.normalize();
        Quaternionf quat = new Quaternionf().rotationAxis((float) Math.toRadians(step),
                (float) axis.x, (float) axis.y, (float) axis.z);
        // Vec3（1.20.1 官方映射）没有 rotate(Quaternionf)——旋转走 JOML Vector3f.rotate 等同实现
        Vector3f v3 = new Vector3f((float) curDir.x, (float) curDir.y, (float) curDir.z).rotate(quat);
        Vec3 newDir = new Vec3(v3.x, v3.y, v3.z).normalize();
        this.setDeltaMovement(newDir.scale(speed));
    }

    /** 追踪候选资格：始终排除 = owner 自己 + 被施法者驯养/所属的生物（宠/坐骑）。
     *  hostileOnly=false 时空放其余一切生物；hostileOnly=true 时只放「敌对」：
     *  ① Enemy（原版怪物接口：僵尸/骷髅/苦力怕等）→「所有 Mob 敌对种类」；
     *  ② 对主人/施法者有仇恨：getTarget()==owner（正追着打你）或 lastHurtByMob()==owner（刚被你打）。 */
    private static boolean isLockable(Entity candidate, Entity owner, boolean hostileOnly) {
        if (owner != null && isOwnedBy(candidate, owner)) return false;
        if (!hostileOnly) return true;
        if (candidate instanceof Enemy) return true;
        if (candidate instanceof Mob mob && owner != null) {
            if (mob.getTarget() == owner) return true;
            if (mob.getLastHurtByMob() == owner) return true;
        }
        return false;
    }

    /** 是否属于施法者驯养/所属（OwnableEntity = 原版可被玩家驯养/骑乘：马/狼/猫/鹦鹉…）。
     *  owner 传 null 时按未驯养处理（无主的兜底不锁敌对判定里已有 Enemy 分支）。 */
    private static boolean isOwnedBy(Entity candidate, Entity owner) {
        if (!(candidate instanceof OwnableEntity owned) || owner == null) return false;
        UUID ownerUuid = owned.getOwnerUUID();
        return ownerUuid != null && ownerUuid.equals(owner.getUUID());
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
        CompoundTag tag = this.getPersistentData();
        Entity source = owner != null ? owner : this;
        SpellDamageHelper.applySpellDamage(this.level(), result.getEntity(), source, this);
        // 范围爆裂（AreaStep）：命中点半径内所有实体范围伤害
        if (tag.getDouble("spell_area") > 0) {
            SpellDamageHelper.applyAreaDamage(this.level(), result.getEntity().position(), source, tag);
        }
        // 爆裂法术（mage:burst）：命中点燃目标 10 秒（复刻 adventuresmod BURST 行为）
        if (tag.getBoolean("spell_enchant_burst")) {
            result.getEntity().setSecondsOnFire(10);
        }
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.level().isClientSide) return;
        CompoundTag tag = this.getPersistentData();
        if (isPiercing()) return; // 穿透：穿墙不消失

        // 反弹（NBT spell_bounce > 0）：撞方块反射速度继续飞，count 耗尽才消失。
        // 严格对齐 conjurer BouncingBallEntity：反射后速度<b>大小不变</b>（无恢复系数、
        // 无能量耗尽判别）——conjurer 球也只在翻转速度，衰减靠它自己的惯性 0.99，我们
        // getInertia=1.0 无衰减则反弹多少次速度都一样，弹跳高度恒定，纯粹由 count 兜底。
        int bounces = tag.getInt("spell_bounce");
        if (bounces > 0) {
            Direction face = result.getDirection();
            Vec3 motion = this.getDeltaMovement();
            // 轴对齐方块面：法线反射 = conjurer 式「逐轴取反」（BouncingBallEntity 同款：
            // EAST/WEST 翻 x、SOUTH/NORTH 翻 z、UP/DOWN 翻 y），两种重力模式共用。
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            double dot = motion.dot(normal);
            Vec3 reflected = motion.subtract(normal.scale(2.0 * dot));
            // 保底起跳 vy（仅受重力）：极小的垂直分量会让弹贴地板逐 tick 反复撞，烧完 count；
            // conjurer 靠 20/5 上限兜底，我们用 min-vy 让弹真正离面 + count 兜底。
            if (!this.antigravity && face.getAxis() == Direction.Axis.Y && Math.abs(reflected.y) < MIN_BOUNCE_VY) {
                reflected = new Vec3(reflected.x,
                        reflected.y >= 0 ? MIN_BOUNCE_VY : -MIN_BOUNCE_VY, reflected.z);
            }
            this.setDeltaMovement(reflected);
            // 推出碰撞面：0.05 对慢速弹不够（boundingBox 边缘仍压着面 → 下 tick 又触发碰撞，
            // 视觉上像"卡在墙上没弹起来"）。conjurer 不推出靠它自己的惯性衰减；我们速度不衰减，
            // 必须保证弹真正离面。0.1 让弹中心完全移出碰撞面。
            this.setPos(this.getX() + normal.x() * 0.1, this.getY() + normal.y() * 0.1, this.getZ() + normal.z() * 0.1);
            tag.putInt("spell_bounce", bounces - 1);
            // 命中即结算范围伤害（若配了 area），然后反射继续飞
            if (tag.getDouble("spell_area") > 0) {
                Entity owner = this.getOwner();
                SpellDamageHelper.applyAreaDamage(this.level(), this.position(), owner != null ? owner : this, tag);
            }
            return;
        }

        // 非反弹非穿透：命中结算范围伤害（若配了 area）后消失
        if (tag.getDouble("spell_area") > 0) {
            Entity owner = this.getOwner();
            SpellDamageHelper.applyAreaDamage(this.level(), this.position(), owner != null ? owner : this, tag);
        }
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