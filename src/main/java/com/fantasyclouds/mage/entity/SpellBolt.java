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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /** 已结算过一次伤害的实体（「每实体一次」去重集合，通用伤害次数机制：
     *  area 默认、pierce 等行为共用。配了 interval 的持续伤害场不走这里）。 */
    private final Set<UUID> damageOnce = new HashSet<>();

    private static final EntityDataAccessor<String> DATA_SPELL_TYPE =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.STRING);
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

    /** 弹级全局伤害间隔（tick，2026-10-22 定案·独立维度）：>0 = 每 interval tick 结算一次
     *  （持续重复场/帧伤，area/pierce/命中结算共用）；0（缺省）= 每实体只结算一次。 */
    private int damageInterval = 0;

    /** beam 预演缓存路径（2026-10-23 重构·瞬时射线）：施法第一个 tick 一次预演生成的整条折线
     *  （由 speed×lifetime、pierce/bounce、homing·一次 决定，预演完<b>永不改变</b>），整条同 tick 展开。 */
    public final List<BeamLine> beamPath = new ArrayList<>();

    /** beam hold 态：预演完成 → 弹不再飞行，当"射线持有者"（整条线停留 beamDuration 后整体淡出）。 */
    private boolean beamHold = false;

    /** beam 整条射线出生的世界时间（决定停留/淡出时长）。 */
    private long beamBornTick = 0;

    /** hold 进入诊断日志一次性标记。 */
    private boolean beamHoldLogged = false;

    /** beam 粒子分支触发诊断日志一次性标记。 */
    private boolean beamParticleLogged = false;

    /** 客户端冻结标志（SynchedEntityData 同步）：服务端预演完成后置 true，
     *  客户端收到后停止本地移动（路径/伤害/粒子全由服务端发出）。 */
    private static final EntityDataAccessor<Boolean> DATA_BEAM_HOLD =
            SynchedEntityData.defineId(SpellBolt.class, EntityDataSerializers.BOOLEAN);

    /** 预演预算（防一次施法卡死）：子步分辨率 0.5 格恒不漏块；路径总长/总子步封顶。 */
    private static final double BEAM_SUBSTEP = 0.5;
    private static final double BEAM_MAX_DISTANCE = 96.0;
    private static final int    BEAM_MAX_STEPS = 4096;

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

    /** 通用伤害次数机制——「每实体一次」：返回 true = 该实体首次被本弹结算，应造成伤害；
     *  返回 false = 已经结算过一次（不重复伤害）。area 默认 / pierce 共用。 */
    public boolean damageOnce(Entity target) {
        return target != null && this.damageOnce.add(target.getUUID());
    }

    /** 通用伤害次数机制——interval 节流：interval ≤ 0 恒放行（每实体一次模式）；
     *  interval > 0 每 interval tick 放行一次（持续重复伤害场/帧伤）。 */
    public boolean damageIntervalTick(int interval) {
        return interval <= 0 || this.level().getGameTime() % interval == 0;
    }

    /** 设置弹级全局伤害间隔（2026-10-22 定案·独立维度；ProjectileStep 注入）。 */
    public void setDamageInterval(int interval) {
        this.damageInterval = Math.max(0, interval);
    }

    public int getDamageInterval() {
        return this.damageInterval;
    }

    /** 通用伤害次数裁决（唯一入口，所有伤害行为共用）：
     *  interval>0 → 本 tick 是否放行（弹级全局节流，重复结算不去重）；
     *  interval≤0 → 该实体是否首次结算（每实体一次）。 */
    public boolean shouldDamage(Entity target) {
        return this.damageInterval > 0
                ? damageIntervalTick(this.damageInterval)
                : damageOnce(target);
    }

    /** 对单个目标结算魔法伤害 + burst 点燃 + applyEffect 药水效果（读 persistentData 的 spell_damage 等）。
     *  统一入口：命中结算 / area / ground 全部走这里——目标效果与伤害次数严格一致
     *  （interval=0 每实体一次 → 效果只施一次；interval>0 持续场 → 每次重复结算都刷新效果）。 */
    public void dealDamage(Entity target) {
        CompoundTag tag = this.getPersistentData();
        Entity owner = this.getOwner();
        Entity source = owner != null ? owner : this;
        SpellDamageHelper.applySpellDamage(this.level(), target, source, this);
        if (tag.getBoolean("spell_enchant_burst")) {
            target.setSecondsOnFire(10);
        }
        // applyEffect 原语：对结算目标施加药水效果列表（命中/范围/贴地全覆盖）
        net.minecraft.nbt.ListTag list = tag.getList("spell_apply_effects", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (!list.isEmpty() && target instanceof net.minecraft.world.entity.LivingEntity living) {
            for (net.minecraft.nbt.Tag raw : list) {
                if (!(raw instanceof CompoundTag pt)) continue;
                try {
                    net.minecraft.resources.ResourceLocation id =
                            net.minecraft.resources.ResourceLocation.tryParse(pt.getString("effect"));
                    net.minecraft.world.effect.MobEffect effect =
                            net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getValue(id);
                    if (effect != null) {
                        living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                effect, pt.getInt("duration"), pt.getInt("amplifier")));
                    }
                } catch (Throwable t) {
                    // 单个效果异常：跳过，不影响伤害
                }
            }
        }
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

    /** 伤害类型字符串 id（"magic"/"fire"/…；2026-10-22 定案，不再是数字）。 */
    public void setSpellType(String damageType) {
        this.entityData.set(DATA_SPELL_TYPE, damageType == null ? "" : damageType);
    }

    public String getSpellType() {
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

    /** 消失粒子（onDeath）已触发标志：despawn 可能被多个路径调用（寿命到/命中/pierce/bounce），只发一次。 */
    private boolean deathParticlesDone = false;

    /** 统一消失入口（Entity.discard 是 final 无法覆写，故包装）：服务端第一次消失时一次性发出
     *  spell_death_particles（法术消散特效，如剑气爆开）。所有消失路径（lifetime 到 / 命中结算后 /
     *  pierce 用完 / beam 停留期满）都应走这里。 */
    public void despawn() {
        if (!this.level().isClientSide && !this.deathParticlesDone) {
            this.deathParticlesDone = true;
            try {
                spawnParticleList(this.getPersistentData(), "spell_death_particles");
            } catch (Throwable t) {
                // 死亡粒子异常不影响弹的移除
            }
        }
        this.discard();
    }

    /** 取弹装配里 beam 行为的 duration（多条取最大；无 beam 行为 = 0）。 */
    public int beamDuration() {
        int d = 0;
        for (BoltBehavior b : this.behaviors) {
            if (b instanceof BeamBehavior bb) d = Math.max(d, bb.duration());
        }
        return d;
    }

    /** 弹是否装配了 beam 行为（供直达命中让位 / pre-sim 触发判断用）。 */
    public boolean hasBeamBehavior() {
        for (BoltBehavior b : this.behaviors) {
            if (b instanceof BeamBehavior) return true;
        }
        return false;
    }

    /** beam hold tick：弹不再飞行，整条缓存射线停留——期满整体淡出（onDeath+discard）；
     *  停留期间按 interval 对整条线结算伤害 + 持续铺线粒子。 */
    private void tickBeamHold() {
        if (!this.beamHoldLogged) {
            this.beamHoldLogged = true;
            // 诊断：把 spell_particles 里第一条的 id/mode 打出来（确认 NBT 到底写了什么模式）
            String firstParticle = "none";
            net.minecraft.nbt.ListTag ptl = this.getPersistentData().getList(
                    "spell_particles", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (!ptl.isEmpty() && ptl.get(0) instanceof CompoundTag fp) {
                firstParticle = "id=" + fp.getString("id") + ",mode=" + fp.getString("mode")
                        + ",count=" + fp.getInt("count");
            }
            com.fantasyclouds.mage.Mage.LOGGER.info(
                    "[MageBeam] hold start: pathLines={} len={} duration={} particlesTag={} first={}",
                    beamPath.size(), totalBeamLength(), beamDuration(), ptl.size(), firstParticle);
        }
        long now = this.level().getGameTime();
        if (now - this.beamBornTick >= beamDuration()) {
            this.beamHold = false; // 防 despawn 残留
            this.despawn();
            return;
        }
        damageBeamPath();
        spawnParticleList(this.getPersistentData(), "spell_particles");
    }

    /**
     * 预演：施法后第一个 tick 跑<b>一次</b>，把弹将来整段飞行（speed×lifetime）以 0.5 格子步
     * 预演成折线缓存到 {@code beamPath}，然后<b>永不改变</b>。预演内：
     * <ul>
     *   <li>homing 只参与<b>一次</b>（开始时对最近目标转向一次，之后路径固定）；</li>
     *   <li>bounce 在子步命中方块处反射路径并（配 explode 时）爆炸；pierce 无视墙直穿；</li>
     *   <li>无 bounce/pierce 时激光止于第一堵墙（配 explode 在墙面爆炸）；</li>
     *   <li>预算封顶：总长 96 格 / 总子步 4096。</li>
     * </ul>
     * 预演后弹冻结为"射线持有者"（客户端经 DATA_BEAM_HOLD 同步冻结）。
     *
     * @return 是否成功生成非空路径（=进入 beam hold）。
     */
    private boolean runBeamPreSim() {
        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1e-4) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageBeam] presim skip: no speed ({})", speed);
            return false; // 无初速：不预演
        }
        Vec3 dir = motion.scale(1.0 / speed); // 发射方向（含 split 偏角）
        CompoundTag tag = this.getPersistentData();
        long now = this.level().getGameTime();
        if (!tag.contains("spell_death_time")) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageBeam] presim skip: no spell_death_time");
            return false;
        }
        int lifetime = (int) Math.max(1, tag.getLong("spell_death_time") - now);

        // homing：只参与一次（用户定案）——预演开头对最近可锁目标转向一步，之后路径固定
        com.fantasyclouds.mage.entity.HomingBehavior homing = firstOf(com.fantasyclouds.mage.entity.HomingBehavior.class);
        if (homing != null) {
            var target = com.fantasyclouds.mage.entity.HomingBehavior.findTarget(
                    this, this.getOwner(), homing.range(), homing.hostileOnly());
            if (target != null) {
                Vec3 steered = com.fantasyclouds.mage.entity.HomingBehavior.steer(
                        dir, this.position(), target.getEyePosition(), homing.turnDeg());
                if (steered != null) dir = steered;
            }
        }
        int bounces = sumBounce();
        boolean pierce = hasBehavior(com.fantasyclouds.mage.entity.PierceBehavior.class);

        List<BeamLine> lines = new ArrayList<>();
        Vec3 pos = this.position();
        Vec3 cur = dir;
        int substeps = 0;
        int ticksLeft = lifetime;
        boolean stopped = false;

        while (ticksLeft > 0 && substeps < BEAM_MAX_STEPS && !stopped) {
            double remain = speed;
            while (remain > 1e-6 && substeps < BEAM_MAX_STEPS && !stopped) {
                double step = Math.min(BEAM_SUBSTEP, remain);
                Vec3 next = pos.add(cur.scale(step));
                // 碰撞优先级（用户定案）：有弹跳时方块命中一律弹跳，穿透只对实体生效；
                // 只有「纯穿透、没有弹跳」才无视墙直接推进。旧逻辑让 pierce 抢先穿墙，
                // 导致 pierce+bounce 的射线永远不撞墙（魔血法杖实测）。
                if (pierce && bounces == 0) {
                    // 纯穿透：无视墙，直接推进
                    lines.add(new BeamLine(pos.x, pos.y, pos.z, next.x, next.y, next.z));
                    pos = next;
                    remain -= step;
                    substeps++;
                    continue;
                }
                BlockHitResult hit = this.level().clip(new net.minecraft.world.level.ClipContext(
                        pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, this));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    Vec3 wall = hit.getLocation();
                    lines.add(new BeamLine(pos.x, pos.y, pos.z, wall.x, wall.y, wall.z)); // 本段画到墙面
                    if (bounces > 0) {
                        bounces--;
                        cur = reflectAt(cur, hit);
                        pos = wall.add(cur.scale(0.1)); // 推出面一点再继续
                        explodeAt(wall);
                    } else {
                        explodeAt(wall);
                        stopped = true; // 激光止于墙
                    }
                    continue;
                }
                lines.add(new BeamLine(pos.x, pos.y, pos.z, next.x, next.y, next.z));
                pos = next;
                remain -= step;
                substeps++;
                if (pos.distanceTo(this.position()) >= BEAM_MAX_DISTANCE) stopped = true;
            }
            if (!this.antigravity) {
                double len2 = cur.lengthSqr();
                if (len2 > 1e-12) cur = cur.add(0, -GRAVITY, 0).normalize(); // 受重力：逐 tick 下沉
            }
            ticksLeft--;
        }

        if (lines.isEmpty()) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageBeam] presim empty: speed={} lifetime={}", speed, lifetime);
            return false;
        }
        this.beamPath.clear();
        this.beamPath.addAll(lines);
        this.beamBornTick = now;
        this.setDeltaMovement(Vec3.ZERO);          // 服务端冻结
        this.entityData.set(DATA_BEAM_HOLD, true); // 客户端冻结
        com.fantasyclouds.mage.Mage.LOGGER.info("[MageBeam] presim ok: speed={} lifetime={} lines={} len={}",
                speed, lifetime, lines.size(), totalBeamLength());
        return true;
    }

    /** 整条预演路径结算（用户定案：以<b>线段集合</b>为最小数据单位）——先收集与任意线段相交的实体
     *  （一个实体同 tick 跨多条线也只入集一次），再统一按 shouldDamage/dealDamage 各结算一次；
     *  不按子步点反复结算，避免同一条线上相邻点对同一实体重复伤害。
     *
     *  <p>2026-10-24 性能修（效果不变）：原实现对每条 BeamLine 各做一次 getEntitiesOfClass
     *  （96 格满长 = 约 192 次区块实体查询/束/tick，线段包围盒高度重叠重复扫）——改为
     *  <b>整束包围盒一次查询 + 逐线 clip 判定</b>。beamPath 仍是预演产物（0.5 格子步折线，
     *  反射/穿墙几何完全由 runBeamPreSim 决定，本方法不碰），整束包围盒是每条线段包围盒的超集
     *  → 候选超集 → 再经与原来完全相同的 clip 判定精确过滤 → 命中集合逐位一致。
     *  另：interval 节流本 tick 不结算时整段跳过（所有 shouldDamage 本就返回 false）。 */
    public void damageBeamPath() {
        if (beamPath.isEmpty()) return;
        // interval 节流：本 tick 不结算时所有 shouldDamage 都返回 false —— 整段跳过（零效果变化）
        if (this.damageInterval > 0 && !damageIntervalTick(this.damageInterval)) return;

        // 射线粗度 = area 半径（原语正交组合）：beam 只摊时间/路径，area 负责空间半径——
        // beam+area = 沿整条射线的「管状」伤害，半径 = area.radius（spell_area NBT）。
        // 无 area 时退化为细线（0.35）。与 area/ground 的“形状”语义一致：线 + 半径 = 管。
        double thickness = Math.max(0.35, this.getPersistentData().getDouble("spell_area"));

        // 一次性预处理：整束包围盒 + 每条线段端点/包围盒（每 tick 一次，实体循环内零分配）
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int n = beamPath.size();
        Vec3[] from = new Vec3[n];
        Vec3[] to = new Vec3[n];
        AABB[] lineBoxes = new AABB[n];
        for (int i = 0; i < n; i++) {
            BeamLine l = beamPath.get(i);
            double x1 = l.x1(), y1 = l.y1(), z1 = l.z1();
            double x2 = l.x2(), y2 = l.y2(), z2 = l.z2();
            minX = Math.min(minX, Math.min(x1, x2));
            minY = Math.min(minY, Math.min(y1, y2));
            minZ = Math.min(minZ, Math.min(z1, z2));
            maxX = Math.max(maxX, Math.max(x1, x2));
            maxY = Math.max(maxY, Math.max(y1, y2));
            maxZ = Math.max(maxZ, Math.max(z1, z2));
            from[i] = new Vec3(x1, y1, z1);
            to[i] = new Vec3(x2, y2, z2);
            lineBoxes[i] = new AABB(x1, y1, z1, x2, y2, z2);
        }
        AABB union = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(thickness + 0.6);

        Set<net.minecraft.world.entity.LivingEntity> hit = new HashSet<>();
        for (net.minecraft.world.entity.LivingEntity e : this.level().getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, union,
                t -> t != this.getOwner() && t.isAlive())) {
            AABB entityBox = e.getBoundingBox().inflate(thickness);
            for (int i = 0; i < n; i++) {
                // 粗筛：线段包围盒不相交直接跳过（clip 前的廉价拒绝）
                if (!entityBox.intersects(lineBoxes[i])) continue;
                if (entityBox.clip(from[i], to[i]).isPresent()) {
                    hit.add(e);
                    break; // 一条线段命中即可（Set 去重语义与原来一致）
                }
            }
        }
        for (net.minecraft.world.entity.LivingEntity e : hit) {
            if (shouldDamage(e)) dealDamage(e);
        }
    }

    /** 预演中撞墙点爆炸（beam×explode 兼容）：配 explode 时在每面反弹墙/终墙处起爆。 */
    private void explodeAt(Vec3 at) {
        for (BoltBehavior b : this.behaviors) {
            if (b instanceof com.fantasyclouds.mage.entity.ExplodeBehavior ex) {
                try {
                    // MOB 而非 BLOCK：BLOCK 无视 mobGriefing（防爆开了也炸方块）
                    net.minecraft.world.level.Level.ExplosionInteraction interaction =
                            ex.destroyBlocks() ? net.minecraft.world.level.Level.ExplosionInteraction.MOB
                                               : net.minecraft.world.level.Level.ExplosionInteraction.NONE;
                    this.level().explode(this.getOwner(), at.x, at.y, at.z, ex.power(), interaction);
                } catch (Throwable t) {
                    // 爆炸异常不影响射线本身
                }
            }
        }
    }

    /** 命中面向反射方向（轴对齐方块面法线反射）。 */
    private static Vec3 reflectAt(Vec3 dir, BlockHitResult hit) {
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        return dir.subtract(normal.scale(2.0 * dir.dot(normal))).normalize();
    }

    private <T extends BoltBehavior> T firstOf(Class<T> cls) {
        for (BoltBehavior b : this.behaviors) {
            if (cls.isInstance(b)) return cls.cast(b);
        }
        return null;
    }

    private boolean hasBehavior(Class<? extends BoltBehavior> cls) {
        for (BoltBehavior b : this.behaviors) {
            if (cls.isInstance(b)) return true;
        }
        return false;
    }

    private int sumBounce() {
        int n = 0;
        for (BoltBehavior b : this.behaviors) {
            if (b instanceof com.fantasyclouds.mage.entity.BounceBehavior bb) n += bb.remaining();
        }
        return n;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SPELL_TYPE, "");
        this.entityData.define(DATA_SPELL_VISUAL, "");
        this.entityData.define(DATA_SPELL_YAW_OFFSET, 0f);
        this.entityData.define(DATA_SPELL_DIR_X, 0f);
        this.entityData.define(DATA_SPELL_DIR_Y, 0f);
        this.entityData.define(DATA_SPELL_DIR_Z, 0f);
        this.entityData.define(DATA_BEAM_HOLD, false);
    }

    @Override
    public void tick() {
        // 客户端 beam hold：本地冻结（路径/伤害/粒子全由服务端发出，客户端只负责渲染收到的粒子）
        if (this.level().isClientSide && this.entityData.get(DATA_BEAM_HOLD)) {
            return;
        }
        // 服务端：施法后第一个 tick 预演一次（用户定案：预演完路径永不变）
        if (!this.level().isClientSide && !this.beamHold && hasBeamBehavior()) {
            if (runBeamPreSim()) {
                this.beamHold = true;
            }
        }
        if (this.beamHold) {
            this.tickBeamHold(); // 整条射线同 tick 展开：停留/结算/铺线，期满整体淡出
            return;
        }

        // 普通弹（无 beam）：原飞行逻辑。
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
        spawnParticleList(tag, "spell_particles");

        if (tag.contains("spell_death_time") && this.level().getGameTime() >= tag.getLong("spell_death_time")) {
            this.despawn();
        }
    }

    /** 服务端发特效粒子列表（可多种，遍历指定 NBT key 的列表；单个粒子坏/查不到静默跳过）。
     *  分布模式（2026-10-22 定案·五种约定 + 贴地投影铁律）：
     *  <ul>
     *   <li>{@code spread}=中心扩散（小抖动动态，随弹）；</li>
     *   <li>{@code sphere}/{@code cube}=立体·随弹（速度 0 悬浮，填满球体/立方体）；</li>
     *   <li>{@code disc}/{@code square}=扁平·贴地（粒子铺在<b>弹正下方完整方块顶面</b>——不跟弹保持
     *       相对位置，否则弹在半空时会出现「半空扁平」违和视觉）。</li>
     *  </ul>
     *  cube 用 sendParticles 单次调用（原生长方体）；sphere/disc/square 逐粒子均匀采样
     *  （sendParticles 的 dx/dy/dz 只能出长方体，真球/圆盘/贴地方块必须逐点发坐标）。
     *  trail（spell_particles 每 tick 发）与死亡（spell_death_particles 消失时一次性发）共用。 */
    private void spawnParticleList(CompoundTag tag, String key) {
        if (this.level().isClientSide) return;
        net.minecraft.nbt.ListTag list = tag.getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND);
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
                String mode = pt.getString("mode");
                if ("cloud".equals(mode)) mode = "sphere"; // 旧约定名兼容
                double radius = pt.getDouble("radius");
                if (radius <= 0) radius = 1.0;
                // 简单粒子直接作为 ParticleOptions；复杂粒子（需 NBT）跳过以免 API 强转错
                if (type instanceof SimpleParticleType simple) {
                    ServerLevel server = (ServerLevel) this.level();
                    double cx = this.getX(), cy = this.getY(), cz = this.getZ();
                    switch (mode) {
                        case "sphere" -> {
                            // 立体球·随弹：球内均匀采样（方向随机 × cbrt(u)·radius）
                            for (int i = 0; i < count; i++) {
                                Vec3 p = randomSpherePoint(cx, cy, cz, radius);
                                server.sendParticles(simple, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
                            }
                        }
                        case "cube" -> {
                            // 立体正方·随弹：sendParticles 原生长方体（半边长 radius，三轴全范围）
                            server.sendParticles(simple, cx, cy, cz, count, radius, radius, radius, 0.0);
                        }
                        case "disc" -> {
                            // 扁平圆·贴地：圆内均匀采样，粒子铺在弹正下方完整方块顶面（±0.15 薄层）
                            double gy = groundY(cx, cy, cz);
                            for (int i = 0; i < count; i++) {
                                double ang = this.random.nextDouble() * Math.PI * 2;
                                double r = radius * Math.sqrt(this.random.nextDouble()); // 圆内均匀（面积加权）
                                double px = cx + Math.cos(ang) * r;
                                double pz = cz + Math.sin(ang) * r;
                                double py = gy + this.random.nextDouble() * 0.15;
                                server.sendParticles(simple, px, py, pz, 1, 0, 0, 0, 0.0);
                            }
                        }
                        case "square" -> {
                            // 扁平正方·贴地：半边长 radius 的薄方块，粒子铺在弹正下方完整方块顶面
                            double gy = groundY(cx, cy, cz);
                            for (int i = 0; i < count; i++) {
                                double px = cx + (this.random.nextDouble() * 2 - 1) * radius;
                                double pz = cz + (this.random.nextDouble() * 2 - 1) * radius;
                                double py = gy + this.random.nextDouble() * 0.15;
                                server.sendParticles(simple, px, py, pz, 1, 0, 0, 0, 0.0);
                            }
                        }
                        case "beam" -> {
                            // 预演瞬时射线：沿整条缓存路径铺线（视觉=伤害线）。
                            // count = 每 tick 全线段粒子预算下限；间距约 0.5 格保连续；
                            // 单束每 tick 上限 512；粒子绕线 ±0.3 格抖动成粗柱——细点沿视线方向是看不见的。
                            double totalLen = totalBeamLength();
                            if (totalLen <= 1e-4) break;
                            int budget = Math.min(512, Math.max(count, (int) Math.ceil(totalLen / 0.5)));
                            // 光束粗度与伤害管一致：= area 半径（无 area 时细线 0.3）
                            double jitter = Math.max(0.3, this.getPersistentData().getDouble("spell_area"));
                            // 网络裁剪（2026-10-24 性能修·效果不变）：每 tick 算一次可见距离，逐粒子复用
                            double maxDistSqr = beamParticleViewDistanceSqr(server);
                            if (!this.beamParticleLogged) {
                                this.beamParticleLogged = true;
                                com.fantasyclouds.mage.Mage.LOGGER.info(
                                        "[MageBeam] beamParticle fired: budget={} len={} lines={} first=({},{},{})->({},{},{})",
                                        budget, totalLen, beamPath.size(),
                                        beamPath.get(0).x1(), beamPath.get(0).y1(), beamPath.get(0).z1(),
                                        beamPath.get(0).x2(), beamPath.get(0).y2(), beamPath.get(0).z2());
                            }
                            for (BeamLine line : this.beamPath) {
                                double dx = line.x2() - line.x1();
                                double dy = line.y2() - line.y1();
                                double dz = line.z2() - line.z1();
                                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                if (len <= 1e-4) continue;
                                int n = Math.max(1, (int) Math.round(budget * len / totalLen));
                                for (int i = 0; i < n; i++) {
                                    double t = (i + this.random.nextDouble()) / n;
                                    // longDistance=true：原版广播只发 32 格内，长激光远端会看不到——
                                    // beam 逐玩家发送且标记 longDistance，整条射线全程可见；
                                    // 视距外玩家跳过（客户端根本不渲染已加载区块外的粒子，发过去纯浪费）
                                    // 加粗：粒子在垂直于线的 ±0.3 格小立方体内抖动，凑成可见的光柱
                                    beamParticle(server, simple,
                                            line.x1() + dx * t + (this.random.nextDouble() * 2 - 1) * jitter,
                                            line.y1() + dy * t + (this.random.nextDouble() * 2 - 1) * jitter,
                                            line.z1() + dz * t + (this.random.nextDouble() * 2 - 1) * jitter,
                                            maxDistSqr);
                                }
                            }
                        }
                        default -> {
                            // 中心扩散：弹位置小抖动（原有行为）
                            server.sendParticles(simple, cx, cy, cz, count,
                                    this.random.nextDouble() * 0.1 - 0.05,
                                    this.random.nextDouble() * 0.1 - 0.05,
                                    this.random.nextDouble() * 0.1 - 0.05, 0.02);
                        }
                    }
                }
            } catch (Throwable t) {
                // 单个粒子异常：跳过，不影响弹
            }
        }
    }

    /** 缓存折线总长（beam 粒子按长度分配每 tick 预算）。 */
    private double totalBeamLength() {
        double total = 0;
        for (BeamLine l : beamPath) {
            double dx = l.x2() - l.x1(), dy = l.y2() - l.y1(), dz = l.z2() - l.z1();
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /** beam 粒子可见距离（格，平方）：服务端视距 × 16 + 1 格余量。原版客户端只在<b>已加载区块</b>
     *  里生成/渲染粒子——玩家距粒子超过视距时收了包也丢弃，纯浪费流量。按服务端视距裁剪
     *  对视觉零影响（玩家永远看不到视距外的粒子），只省掉「看不见」的发送（2026-10-24 性能修）。 */
    private double beamParticleViewDistanceSqr(ServerLevel server) {
        int view = server.getServer().getPlayerList().getViewDistance();
        double dist = (view + 1) * 16.0;
        return dist * dist;
    }

    /** beam 粒子逐玩家发送（longDistance=true）：原版 {@code ServerLevel.sendParticles} 广播只发给
     *  32 格内玩家，96 格长激光远端会不可见——这里每个粒子对每个可见玩家单独发并标 longDistance，
     *  客户端在长距离也渲染；视距外玩家跳过（见 {@link #beamParticleViewDistanceSqr}）。 */
    private void beamParticle(ServerLevel server, SimpleParticleType type,
                              double x, double y, double z, double maxDistSqr) {
        for (net.minecraft.server.level.ServerPlayer p : server.players()) {
            if (p.distanceToSqr(x, y, z) <= maxDistSqr) {
                server.sendParticles(p, type, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** 球内均匀采样点：方向随机、径向距离 = cbrt(u)·radius（u∈[0,1]，体积均匀）。 */
    private Vec3 randomSpherePoint(double cx, double cy, double cz, double radius) {
        double u = this.random.nextDouble() * 2 - 1;
        double v = this.random.nextDouble() * 2 - 1;
        double w = this.random.nextDouble() * 2 - 1;
        double len = Math.sqrt(u * u + v * v + w * w);
        if (len < 1e-6) { // 退化方向（概率≈0，兜底）
            u = 1; v = 0; w = 0; len = 1;
        }
        double r = radius * Math.cbrt(this.random.nextDouble());
        return new Vec3(cx + u / len * r, cy + v / len * r, cz + w / len * r);
    }

    /** groundY 缓存（2026-10-24 性能修·效果不变）：同一 tick 内 disc/square 粒子与 GroundBehavior
     *  用的是弹的同一列同一起始高度，且地面在一个 tick 内不会变化——扫描结果按
     *  (tick, 列, 起始 y, 起始高度原始值) 缓存，同 tick 内多次调用只真正向下扫一次方块；
     *  悬浮不动的弹跨 tick 也复用。键含起始高度的原始值 cy，保证任何调用组合下
     *  返回值与逐次扫描完全一致（虚空回落分支依赖 cy）。 */
    private long groundYCacheTick = Long.MIN_VALUE;
    private int groundYCacheX = Integer.MIN_VALUE;
    private int groundYCacheStartY = Integer.MIN_VALUE;
    private int groundYCacheZ = Integer.MIN_VALUE;
    private double groundYCacheCy = Double.NaN;
    private double groundYCacheValue = 0.0;

    /** 贴地投影（扁平粒子铁律 + ground 地板投影伤害共用同一逻辑，保证视觉-伤害严格对齐）：
     *  从 (x,z) 列向下找最近的完整方块顶面 y；找不到（虚空/极高空中）回落弹当前 y。 */
    public double groundY(double cx, double cy, double cz) {
        long now = this.level().getGameTime();
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(cx, cy, cz);
        if (now == this.groundYCacheTick && cy == this.groundYCacheCy
                && bp.getX() == this.groundYCacheX && bp.getY() == this.groundYCacheStartY
                && bp.getZ() == this.groundYCacheZ) {
            return this.groundYCacheValue;
        }
        double value = scanGroundY(cx, cy, cz);
        this.groundYCacheTick = now;
        this.groundYCacheX = bp.getX();
        this.groundYCacheStartY = bp.getY();
        this.groundYCacheZ = bp.getZ();
        this.groundYCacheCy = cy;
        this.groundYCacheValue = value;
        return value;
    }

    private double scanGroundY(double cx, double cy, double cz) {
        net.minecraft.core.BlockPos.MutableBlockPos pos =
                new net.minecraft.core.BlockPos.MutableBlockPos();
        pos.set(net.minecraft.core.BlockPos.containing(cx, cy, cz));
        for (int y = pos.getY(); y >= this.level().getMinBuildHeight(); y--) {
            pos.setY(y);
            net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(pos);
            // 完整方块（有实心碰撞箱）顶面 = y+1；气体/非完整方块继续下探
            if (!state.isAir() && state.isSolid()) {
                return y + 1.0;
            }
        }
        return cy;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.level().isClientSide) return;
        // 幂等：通用 ProjectileSpellDamageMixin 已结算过则不再结算（防双路径双倍伤害）
        if (this.getPersistentData().getBoolean("mage$spell_handled")) {
            this.despawn();
            return;
        }
        CompoundTag tag = this.getPersistentData();

        // 通用命中结算（2026-10-22 定案）：命中实体先按弹级伤害次数机制结算
        // （interval>0 每 interval tick 重复 / interval=0 每实体一次）——pierce 穿过实体同样结算。
        // beam 存在时直达命中让位给线结算：线把整段都覆盖（含本 tick 刚铺的尖端段），避免同 tick 双打；
        // 弹是否被目标挡住（despawn）仍由 pierce 决定，与伤害解耦。
        if (!hasBeamBehavior() && shouldDamage(result.getEntity())) {
            dealDamage(result.getEntity());
        }

        // 行为驱动：按序跑命中钩子。行为消费（返回 true）则弹不消失（如 pierce 穿透 / bounce 反弹）。
        boolean consumed = false;
        for (BoltBehavior behavior : this.behaviors) {
            if (behavior.onHitEntity(this, result, tag)) consumed = true;
        }
        if (consumed) return;

        this.despawn();
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

        this.despawn();
    }

    @Override
    protected float getInertia() {
        return 1.0f; // 无衰减
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }

    /** 尾迹粒子：Mage 弹默认无粒子效果（2026-10-23 用户定案）。
     *  原版 {@code AbstractHurtingProjectile.tick} 每 tick 内联在弹位置发一个 {@code getTrailParticle()}
     *  （基类默认 {@code ParticleTypes.SMOKE} 黑烟）——换成无渲染的空粒子 {@code mage:empty_trail}：
     *  客户端 ParticleEngine 无 provider → 静默跳过，不产生任何粒子。
     *  要给某把法杖加拖尾/毒雾特效，用 caster_stats 的 visual 步骤配 {@code particles}。 */
    @Override
    protected net.minecraft.core.particles.ParticleOptions getTrailParticle() {
        return com.fantasyclouds.mage.init.MageParticles.EMPTY_TRAIL.get();
    }

    /** 魔法弹不进水阻（2026-10-23 用户定案）：原版基类 {@code AbstractHurtingProjectile.tick}
     *  在水里会把惯性降为 0.8（越飞越慢）+ 每 tick 发 4 个气泡。重写 {@code isInWater()=false}
     *  → 全程匀速直穿水体、无气泡无减速。 */
    @Override
    public boolean isInWater() {
        return false;
    }

    /** 弹不命中其他弹射物。分裂弹在<b>同一坐标</b>生成，而 {@code AbstractHurtingProjectile.isPickable()}
     *  返回 true → 默认会互相命中：配 {@code pierce} 时分裂出的弹会把彼此的穿透次数打光、刚出生就消失
     *  （神话法杖 split=5 只剩 2 颗的根因）。排除所有 Projectile（含我方 SpellBolt）后各飞各的。 */
    @Override
    protected boolean canHitEntity(net.minecraft.world.entity.Entity target) {
        if (target instanceof net.minecraft.world.entity.projectile.Projectile) return false;
        return super.canHitEntity(target);
    }
}