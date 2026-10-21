package com.fantasyclouds.mage.spell;

import com.fantasyclouds.mage.entity.SpellBolt;
import com.fantasyclouds.mage.init.MageEnchantments;
import com.fantasyclouds.mage.init.MageEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线中流动的「一颗弹」的最终配置（可变对象，修饰符步骤改它的字段）。
 *
 * <p>发射时携带 Mage 施法 NBT（spell_damage/spell_type/spell_death_time/spell_enchant_*），
 * 命中由 {@code ProjectileSpellDamageMixin} 统一接管走 SpellDamageHelper——地基阶段复用
 * 现有 NBT 协议，伤害结算零改动。
 */
public class SpellProjectile {

    public String projectileType; // 空 = 内置 mage:spell_bolt
    public double damage;
    public int ballType;
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
    /** 范围爆裂：>0 时命中后对命中点半径内所有实体造成范围伤害（AreaStep）；单位=格。 */
    public double areaRadius = 0;
    /** 反弹：>0 时撞方块按碰撞面反射速度继续飞，count 次后消失（BounceStep）。 */
    public int bounceCount = 0;
    /** 视觉委托：目标实体注册名（如 lethality:acidic_slash）。非空时客户端渲染改为委托该实体的 Renderer——模型/动画照搬，兼容任意模组实体。空/查不到 → 默认 billboard。 */
    public String visual = "";
    /** 视觉委托的额外水平偏角（度，0 = 不偏）。目标实体模型自带朝向基准时用来校准（如酸性斩击需转 90°）。 */
    public float yawOffset = 0f;
    /** 拖尾粒子列表（可叠加多种，如火焰+酸泡同时拖尾）。VisualStep 往这追加，不覆盖。 */
    public final List<ParticleSpec> particles = new ArrayList<>();

    public SpellProjectile(String projectileType, double damage, int ballType, double speed, int lifetime) {
        this(projectileType, damage, ballType, speed, lifetime, true, 0.0);
    }

    public SpellProjectile(String projectileType, double damage, int ballType, double speed, int lifetime, double angleOffset) {
        this(projectileType, damage, ballType, speed, lifetime, true, angleOffset);
    }

    public SpellProjectile(String projectileType, double damage, int ballType, double speed, int lifetime, boolean antigravity, double angleOffset) {
        this.projectileType = projectileType;
        this.damage = damage;
        this.ballType = ballType;
        this.speed = speed;
        this.lifetime = lifetime;
        this.antigravity = antigravity;
        this.angleOffset = angleOffset;
    }

    public SpellProjectile copy() {
        SpellProjectile p = new SpellProjectile(projectileType, damage, ballType, speed, lifetime, antigravity, angleOffset);
        p.pierceCount = this.pierceCount;
        p.visual = this.visual;
        p.yawOffset = this.yawOffset;
        p.particles.addAll(this.particles);
        p.homingRange = this.homingRange;
        p.homingTurn = this.homingTurn;
        p.homingHostileOnly = this.homingHostileOnly;
        p.areaRadius = this.areaRadius;
        p.bounceCount = this.bounceCount;
        return p;
    }

    /** 实际生成实体（外部弹保留视觉；内置弹走 SpellBolt）。NBT 协议与迁移前一致。
     *  @return 弹是否真正生成并加入世界（false = 实体类型无效/创建失败 → 调用方不扣蓝）。 */
    public boolean launch(CastContext ctx) {
        Level level = ctx.level;
        Player player = ctx.player;

        Vec3 dir = dirWithOffset(player);
        EntityType<?> type = projectileType == null || projectileType.isEmpty()
                ? MageEntities.SPELL_BOLT.get()
                : ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(projectileType));
        if (type == null) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageCaster] unknown projectileType '{}', bolt not spawned", projectileType);
            return false;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            com.fantasyclouds.mage.Mage.LOGGER.warn("[MageCaster] entity create failed for '{}', bolt not spawned", projectileType);
            return false;
        }

        if (entity instanceof Projectile projectile) projectile.setOwner(player);
        entity.setNoGravity(antigravity);
        if (entity instanceof Mob mob) mob.setNoAi(true);

        entity.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        // 关键：弹必须带上发射朝向——从实际飞行方向换算 yaw/pitch（含 split 偏角），
        // 否则视觉委托/模型渲染时 yRot 恒为 0（朝 +Z）或所有分裂弹同向，模型朝向错
        Vec3 motion = dir.scale(speed);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)));
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        if (entity instanceof SpellBolt bolt) {
            bolt.launch(motion, antigravity);
            bolt.setSpellType(ballType);
            if (visual != null && !visual.isEmpty()) bolt.setSpellVisual(visual);
            if (yawOffset != 0f) bolt.setSpellYawOffset(yawOffset);
        } else {
            entity.setDeltaMovement(motion);
        }

        CompoundTag tag = entity.getPersistentData();
        tag.putDouble("spell_damage", damage);
        tag.putInt("spell_type", ballType);
        tag.putLong("spell_death_time", level.getGameTime() + lifetime);
        if (pierceCount > 0) tag.putInt("spell_pierce", pierceCount);
        if (homingRange > 0) {
            tag.putDouble("spell_homing", homingRange);
            if (homingTurn > 0) tag.putDouble("spell_homing_turn", homingTurn);
            tag.putBoolean("spell_homing_hostile", homingHostileOnly);
        }
        if (areaRadius > 0) tag.putDouble("spell_area", areaRadius);
        if (bounceCount > 0) tag.putInt("spell_bounce", bounceCount);
        if (visual != null && !visual.isEmpty()) tag.putString("spell_visual", visual);
        if (yawOffset != 0f) tag.putFloat("spell_yaw_offset", yawOffset);
        // 拖尾粒子列表（多种可共存）→ spell_particles 复合列表
        if (!particles.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (ParticleSpec spec : particles) {
                CompoundTag pt = new CompoundTag();
                pt.putString("id", spec.id());
                pt.putInt("count", spec.count());
                list.add(pt);
            }
            tag.put("spell_particles", list);
        }
        int forceful = ctx.held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        if (forceful > 0) tag.putDouble("spell_enchant_bonus", forceful);
        if (ctx.held.getEnchantmentLevel(MageEnchantments.BURST.get()) > 0) tag.putBoolean("spell_enchant_burst", true);

        level.addFreshEntity(entity);
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