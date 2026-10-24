package com.fantasyclouds.mage.entity;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 反弹行为：撞方块按碰撞面反射速度继续飞，count 耗尽才消失（严格对齐 conjurer BouncingBallEntity）。
 * 反射后速度<b>大小不变</b>（无恢复系数/无能量耗尽判别），反弹多少次速度恒定、高度恒定，
 * 纯粹靠 count 兜底。onHitBlock 恒消费（弹不消失）；onHitEntity 不消费（命中实体正常结算消失）。
 */
public class BounceBehavior implements BoltBehavior {

    /** 反弹后的最小垂直分量（起跳速度）。对齐 conjurer 无能量衰减的弹跳；但极小的 vy 会让
     *  受重力弹贴地逐 tick 反复撞（纯靠 bounce 次数兜底会烧完 count），给一个保底离面速度。 */
    private static final double MIN_BOUNCE_VY = 0.15;

    private int remaining;

    public BounceBehavior(int count) {
        this.remaining = count;
    }

    public boolean isActive() {
        return remaining > 0;
    }

    /** 剩余反弹次数（预演 beam 时读取：初始次数决定路径能在墙上反射几次）。 */
    public int remaining() {
        return remaining;
    }

    @Override
    public boolean onHitBlock(SpellBolt bolt, BlockHitResult result, CompoundTag tag) {
        if (remaining <= 0) return false;
        Direction face = result.getDirection();
        Vec3 motion = bolt.getDeltaMovement();
        // 轴对齐方块面：法线反射 = conjurer 式「逐轴取反」（BouncingBallEntity 同款：
        // EAST/WEST 翻 x、SOUTH/NORTH 翻 z、UP/DOWN 翻 y），两种重力模式共用。
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        double dot = motion.dot(normal);
        Vec3 reflected = motion.subtract(normal.scale(2.0 * dot));
        // 保底起跳 vy（仅受重力）：极小的垂直分量会让弹贴地板逐 tick 反复撞，烧完 count。
        if (!bolt.isAntigravity() && face.getAxis() == Direction.Axis.Y && Math.abs(reflected.y) < MIN_BOUNCE_VY) {
            reflected = new Vec3(reflected.x,
                    reflected.y >= 0 ? MIN_BOUNCE_VY : -MIN_BOUNCE_VY, reflected.z);
        }
        bolt.setDeltaMovement(reflected);
        // 推出碰撞面：0.05 对慢速弹不够（boundingBox 边缘仍压着面 → 下 tick 又触发碰撞，
        // 视觉上像"卡在墙上没弹起来"）。0.1 让弹中心完全移出碰撞面。
        bolt.setPos(bolt.getX() + normal.x() * 0.1, bolt.getY() + normal.y() * 0.1, bolt.getZ() + normal.z() * 0.1);
        remaining--;
        return true; // 消费命中：弹不消失
    }

    @Override
    public boolean onHitEntity(SpellBolt bolt, EntityHitResult result, CompoundTag tag) {
        return false; // 命中实体正常结算消失（与 conjurer 一致：实体命中即伤害消失）
    }

    @Override
    public BoltBehavior copy() {
        return new BounceBehavior(this.remaining);
    }
}
