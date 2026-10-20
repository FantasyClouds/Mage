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
    /** 视觉委托：目标实体注册名（如 lethality:acidic_slash）。非空时客户端渲染改为委托该实体的 Renderer——模型/动画照搬，兼容任意模组实体。空/查不到 → 默认 billboard。 */
    public String visual = "";
    /** 拖尾粒子注册名（如 minecraft:flame）；空 = 无拖尾。弹飞行时每 tick 发射。 */
    public String particle = "";
    /** 拖尾粒子每次发射数量（缺省 2）。 */
    public int particleCount = 2;

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
        p.particle = this.particle;
        p.particleCount = this.particleCount;
        return p;
    }

    /** 实际生成实体（外部弹保留视觉；内置弹走 SpellBolt）。NBT 协议与迁移前一致。 */
    public void launch(CastContext ctx) {
        Level level = ctx.level;
        Player player = ctx.player;

        Vec3 dir = dirWithOffset(player);
        EntityType<?> type = projectileType == null || projectileType.isEmpty()
                ? MageEntities.SPELL_BOLT.get()
                : ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(projectileType));
        if (type == null) return;
        Entity entity = type.create(level);
        if (entity == null) return;

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
        } else {
            entity.setDeltaMovement(motion);
        }

        CompoundTag tag = entity.getPersistentData();
        tag.putDouble("spell_damage", damage);
        tag.putInt("spell_type", ballType);
        tag.putLong("spell_death_time", level.getGameTime() + lifetime);
        if (pierceCount > 0) tag.putInt("spell_pierce", pierceCount);
        if (visual != null && !visual.isEmpty()) tag.putString("spell_visual", visual);
        if (particle != null && !particle.isEmpty()) {
            tag.putString("spell_particle", particle);
            tag.putInt("spell_particle_count", Math.max(1, particleCount));
        }
        int forceful = ctx.held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        if (forceful > 0) tag.putDouble("spell_enchant_bonus", forceful);
        if (ctx.held.getEnchantmentLevel(MageEnchantments.BURST.get()) > 0) tag.putBoolean("spell_enchant_burst", true);

        level.addFreshEntity(entity);
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