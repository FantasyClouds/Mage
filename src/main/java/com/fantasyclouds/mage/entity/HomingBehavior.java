package com.fantasyclouds.mage.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 追踪行为：每 tick 向范围内最近的可锁目标转向（turn 度/tick）。
 * hostileOnly=true 只锁敌对（Enemy ∪ 对施法者仇恨）；施法者驯养生物（OwnableEntity）恒排除。
 */
public class HomingBehavior implements BoltBehavior {

    private final double range;
    private final double turnDeg;
    private final boolean hostileOnly;

    public HomingBehavior(double range, double turn, boolean hostileOnly) {
        this.range = range;
        this.turnDeg = turn > 0 ? turn : 15.0;
        this.hostileOnly = hostileOnly;
    }

    @Override
    public boolean onTick(SpellBolt bolt, CompoundTag tag) {
        Entity owner = bolt.getOwner();
        Vec3 pos = bolt.position();
        AABB box = bolt.getBoundingBox().inflate(range);
        LivingEntity target = null;
        double best = range * range;
        for (LivingEntity e : bolt.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != owner && e.isAlive() && isLockable(e, owner, hostileOnly))) {
            double d = e.distanceToSqr(bolt);
            if (d < best) { best = d; target = e; }
        }
        if (target == null) return false;

        Vec3 current = bolt.getDeltaMovement();
        double speed = current.length();
        if (speed < 1.0E-6) return false;
        Vec3 curDir = current.scale(1.0 / speed);
        Vec3 wanted = target.getEyePosition().subtract(pos).normalize();

        double dot = Mth.clamp(curDir.dot(wanted), -1.0, 1.0);
        double angleDeg = Math.acos(dot) * 180.0 / Math.PI;
        if (angleDeg < 0.01 || angleDeg >= 179.0) return false; // 已对准 / 背对（axis 退化）都不转

        double step = Math.min(turnDeg, angleDeg);
        Vec3 axis = curDir.cross(wanted);
        if (axis.lengthSqr() < 1.0E-8) return false;
        axis = axis.normalize();
        Quaternionf quat = new Quaternionf().rotationAxis((float) Math.toRadians(step),
                (float) axis.x, (float) axis.y, (float) axis.z);
        // Vec3（1.20.1 官方映射）没有 rotate(Quaternionf)——旋转走 JOML Vector3f.rotate 等同实现
        Vector3f v3 = new Vector3f((float) curDir.x, (float) curDir.y, (float) curDir.z).rotate(quat);
        Vec3 newDir = new Vec3(v3.x, v3.y, v3.z).normalize();
        bolt.setDeltaMovement(newDir.scale(speed));
        return false;
    }

    /** 追踪候选资格：始终排除 owner 自己 + 被施法者驯养/所属的生物（宠/坐骑）。
     *  hostileOnly=false 时空放其余一切生物；true 时只放「敌对」：
     *  ① Enemy（原版怪物接口）→「所有 Mob 敌对种类」；
     *  ② 对施法者有仇恨：getTarget()==owner / lastHurtByMob()==owner。 */
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

    private static boolean isOwnedBy(Entity candidate, Entity owner) {
        if (!(candidate instanceof OwnableEntity owned) || owner == null) return false;
        UUID ownerUuid = owned.getOwnerUUID();
        return ownerUuid != null && ownerUuid.equals(owner.getUUID());
    }
}
