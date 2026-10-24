package com.fantasyclouds.mage.spell;

import com.fantasyclouds.mage.entity.SpellBolt;
import com.fantasyclouds.mage.init.MageEnchantments;
import com.fantasyclouds.mage.init.MageEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线中流动的「一颗弹」的最终配置（可变对象，修饰符步骤改它的字段）。
 *
 * <p>发射时携带 Mage 施法 NBT（spell_damage/spell_type/spell_death_time/spell_enchant_*），
 * 命中由 SpellBolt 自身统一走 SpellDamageHelper 结算。
 *
 * <p>伤害类型已是字符串 {@code damageType}（2026-10-22 定案），NBT {@code spell_type} 存字符串。
 *
 * <p><b>不再支持外部弹射物</b>（2026-10-23 定案）：任何法杖都发自内置 {@code mage:spell_bolt}，
 * 外观用 {@link #visual}（visual 步骤把目标实体模型当渲染代理），发射逻辑/伤害由 SpellBolt +
 * BoltBehavior 全量接管——一种弹 + 任意外观即可，不再生成外部模组弹。
 */
public class SpellProjectile {

    public double damage;
    public String damageType;     // 伤害类型字符串 id（"magic"/"fire"/…）
    public double speed;
    public int lifetime;
    /** 无重力：false=受重力抛物线（projectile），true=直线（antigravity，即当前主流法术）。 */
    public boolean antigravity;
    /** 相对玩家朝向的水平偏角（弧度，0 = 正前方）。split 风格修饰符改写它。 */
    public double angleOffset;
    /** 穿透数：>0 时弹穿墙不消失、每实体只结算一次，命中 count 个后消失（PierceStep 累加）。 */
    public int pierceCount;
    /** 追踪：>0 时每 tick 向范围内最近目标转向（HomingStep）；单位=格。 */
    public double homingRange = 0;
    /** 追踪转向速率（每 tick 转向角度，度；值越大转得越急）。 */
    public double homingTurn = 0;
    /** 追踪是否只锁敌对目标（缺省 true）：true=nearest Enemy ∪ aggro；false=也锁中立/被动，但仍剔除驯养物。 */
    public boolean homingHostileOnly = true;
    /** 范围伤害半径：>0 时飞行期间对自身半径内所有实体结算范围伤害（AreaStep·球体随弹）；单位=格。 */
    public double areaRadius = 0;
    /** 地板投影伤害半径：>0 时以弹正下方完整方块顶面为中心的水平圆盘伤害（GroundStep·贴地）；单位=格。 */
    public double groundRadius = 0;
    /** 弹级全局伤害间隔（tick，2026-10-22 定案·三正交维度之一=时间频率）：
     *  >0 每 interval tick 结算一次（持续重复场/帧伤）；=0（缺省）每实体只结算一次。所有伤害行为共用。 */
    public int damageInterval = 0;
    /** 反弹：>0 时撞方块按碰撞面反射速度继续飞，count 次后消失（BounceStep）。 */
    public int bounceCount = 0;
    /** 视觉委托：目标实体注册名（如 adventuresmod:spell）。非空时客户端渲染改用该实体的 Renderer——
     * 模型/动画/贴图照搬，兼容任意模组实体。空/查不到 → 默认 billboard。 */
    public String visual = "";
    /** 视觉委托的额外水平偏角（度，0 = 不偏）。目标实体模型自带朝向基准时用来校准（如酸性斩击需转 90°）。 */
    public float yawOffset = 0f;
    /** 拖尾粒子列表（可叠加多种，如火焰+酸泡同时拖尾）。VisualStep 往这追加，不覆盖。 */
    public final List<ParticleSpec> particles = new ArrayList<>();
    /** 消失粒子列表（法术消失时一次性爆发的特效，如剑气消散爆开）。VisualStep 的 onDeath 参数。 */
    public final List<ParticleSpec> deathParticles = new ArrayList<>();
    /** 命中目标药水效果列表（applyEffect 原语，可叠加多种；发射时写 NBT，dealDamage 对结算目标统一施加）。 */
    public final List<EffectSpec> applyEffects = new ArrayList<>();
    /** 运行时行为列表（Pierce/Homing/Area/Bounce 各修饰符注册一个；顺序 = sequence 顺序，发射时注入 SpellBolt）。 */
    public final List<com.fantasyclouds.mage.entity.BoltBehavior> behaviors = new ArrayList<>();

    public SpellProjectile(double damage, String damageType, double speed, int lifetime) {
        this(damage, damageType, speed, lifetime, true, 0.0);
    }

    public SpellProjectile(double damage, String damageType, double speed, int lifetime, double angleOffset) {
        this(damage, damageType, speed, lifetime, true, angleOffset);
    }

    public SpellProjectile(double damage, String damageType, double speed, int lifetime, boolean antigravity, double angleOffset) {
        this.damage = damage;
        this.damageType = damageType;
        this.speed = speed;
        this.lifetime = lifetime;
        this.antigravity = antigravity;
        this.angleOffset = angleOffset;
    }

    public SpellProjectile copy() {
        SpellProjectile p = new SpellProjectile(damage, damageType, speed, lifetime, antigravity, angleOffset);
        p.pierceCount = this.pierceCount;
        p.visual = this.visual;
        p.yawOffset = this.yawOffset;
        p.particles.addAll(this.particles);
        p.deathParticles.addAll(this.deathParticles);
        p.applyEffects.addAll(this.applyEffects);
        p.homingRange = this.homingRange;
        p.homingTurn = this.homingTurn;
        p.homingHostileOnly = this.homingHostileOnly;
        p.areaRadius = this.areaRadius;
        p.groundRadius = this.groundRadius;
        p.damageInterval = this.damageInterval;
        p.bounceCount = this.bounceCount;
        // 行为深拷贝：split 各弹独立状态（如 pierce 剩余次数）。
        for (com.fantasyclouds.mage.entity.BoltBehavior b : this.behaviors) {
            p.behaviors.add(b.copy());
        }
        return p;
    }

    /**
     * isOverwrite=false 兼容层：把「伤害语义」序列化成 NBT，供 {@code ExternalProjectileTagging}
     * 打到原版模组自己生成的弹射物上（由 {@code ProjectileSpellDamageMixin} 命中时接管结算）。
     *
     * <p>只写伤害相关字段，<b>不写</b> {@code spell_pierce/spell_homing/spell_bounce/spell_visual/
     * spell_death_time/spell_particles}——这些是 Mage 内置弹的运动/外观字段；写进外部弹会污染其原生行为，
     * 且 {@code spell_pierce>0} 会让 mixin 主动放弃伤害接管。
     */
    public CompoundTag buildExternalTag(ItemStack held) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("spell_damage", damage);
        tag.putString("spell_type", damageType);
        if (areaRadius > 0) tag.putDouble("spell_area", areaRadius);
        if (!applyEffects.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (EffectSpec spec : applyEffects) {
                CompoundTag pt = new CompoundTag();
                pt.putString("effect", spec.effectId().toString());
                pt.putInt("duration", spec.duration());
                pt.putInt("amplifier", spec.amplifier());
                list.add(pt);
            }
            tag.put("spell_apply_effects", list);
        }
        int forceful = held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        if (forceful > 0) tag.putDouble("spell_enchant_bonus", forceful);
        if (held.getEnchantmentLevel(MageEnchantments.BURST.get()) > 0) tag.putBoolean("spell_enchant_burst", true);
        return tag;
    }

    /** 实际生成实体：一律内置 {@code mage:spell_bolt}，外观走 visual 委托。NBT 协议与迁移前一致。
     *  @return 弹是否真正生成并加入世界（false = 实体创建失败 → 调用方不扣蓝）。 */
    public boolean launch(CastContext ctx) {
        Level level = ctx.level;
        Player player = ctx.player;

        Vec3 dir = dirWithOffset(player);
        SpellBolt bolt = MageEntities.SPELL_BOLT.get().create(level);
        if (bolt == null) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageCaster] mage:spell_bolt create failed, bolt not spawned");
            return false;
        }

        bolt.setOwner(player);
        bolt.setNoGravity(antigravity);
        bolt.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        // 关键：弹必须带上发射朝向——从实际飞行方向换算 yaw/pitch（含 split 偏角），
        // 否则视觉委托/模型渲染时 yRot 恒为 0（朝 +Z）或所有分裂弹同向，模型朝向错
        Vec3 motion = dir.scale(speed);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
        bolt.setYRot(yaw);
        bolt.setXRot(pitch);
        bolt.yRotO = yaw;
        bolt.xRotO = pitch;
        // 统一落弹（2026-10-23 定案）：不再生成外部模组弹——外观/逻辑/伤害全由 Mage 接管。
        // 视觉 = SpellBolt.spell_visual（visual 步骤 delegate 目标实体渲染）；逻辑 = BoltBehavior 列表。
        bolt.launch(motion, antigravity);
        bolt.setSpellType(damageType);
        bolt.setDamageInterval(damageInterval);
        if (visual != null && !visual.isEmpty()) bolt.setSpellVisual(visual);
        if (yawOffset != 0f) bolt.setSpellYawOffset(yawOffset);
        // 注入运行时行为（顺序 = sequence 顺序；split 已深拷贝独立状态）
        bolt.setBehaviors(this.behaviors);

        CompoundTag tag = bolt.getPersistentData();
        tag.putDouble("spell_damage", damage);
        tag.putString("spell_type", damageType);
        tag.putLong("spell_death_time", level.getGameTime() + lifetime);
        if (pierceCount > 0) tag.putInt("spell_pierce", pierceCount);
        if (homingRange > 0) {
            tag.putDouble("spell_homing", homingRange);
            if (homingTurn > 0) tag.putDouble("spell_homing_turn", homingTurn);
            tag.putBoolean("spell_homing_hostile", homingHostileOnly);
        }
        if (areaRadius > 0) {
            tag.putDouble("spell_area", areaRadius);
        }
        if (groundRadius > 0) {
            tag.putDouble("spell_ground", groundRadius);
        }
        if (damageInterval > 0) tag.putInt("spell_damage_interval", damageInterval);
        if (bounceCount > 0) tag.putInt("spell_bounce", bounceCount);
        if (visual != null && !visual.isEmpty()) tag.putString("spell_visual", visual);
        if (yawOffset != 0f) tag.putFloat("spell_yaw_offset", yawOffset);
        // 特效粒子列表（多种可共存，spread 点扩散 / cloud 范围雾）→ spell_particles 复合列表
        if (!particles.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ParticleSpec spec : particles) {
                CompoundTag pt = new CompoundTag();
                pt.putString("id", spec.id());
                pt.putInt("count", spec.count());
                pt.putString("mode", spec.mode());
                pt.putDouble("radius", spec.radius());
                list.add(pt);
            }
            tag.put("spell_particles", list);
        }
        // 消失粒子列表（法术消失时一次性爆发）→ spell_death_particles 复合列表
        if (!deathParticles.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ParticleSpec spec : deathParticles) {
                CompoundTag pt = new CompoundTag();
                pt.putString("id", spec.id());
                pt.putInt("count", spec.count());
                pt.putString("mode", spec.mode());
                pt.putDouble("radius", spec.radius());
                list.add(pt);
            }
            tag.put("spell_death_particles", list);
        }
        // 命中目标药水效果（applyEffect 原语，多种可共存）→ spell_apply_effects 复合列表
        if (!applyEffects.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (EffectSpec spec : applyEffects) {
                CompoundTag pt = new CompoundTag();
                pt.putString("effect", spec.effectId().toString());
                pt.putInt("duration", spec.duration());
                pt.putInt("amplifier", spec.amplifier());
                list.add(pt);
            }
            tag.put("spell_apply_effects", list);
        }
        int forceful = ctx.held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        if (forceful > 0) tag.putDouble("spell_enchant_bonus", forceful);
        if (ctx.held.getEnchantmentLevel(MageEnchantments.BURST.get()) > 0) tag.putBoolean("spell_enchant_burst", true);

        level.addFreshEntity(bolt);
        return true;
    }

    /** 玩家视线向量绕 Y 轴旋转 angleOffset 后的方向。 */
    private Vec3 dirWithOffset(Player player) {
        Vec3 look = player.getLookAngle();
        if (angleOffset == 0.0) return look;
        double cos = Math.cos(angleOffset);
        double sin = Math.sin(angleOffset);
        return new Vec3(look.x * cos - look.z * sin, look.y, look.x * sin + look.z * cos);
    }
}