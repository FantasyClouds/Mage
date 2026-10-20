package com.fantasyclouds.mage.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.UUID;

/**
 * AttributeModifier 增删工具类。
 *
 * <p>风格参考 Brutality 的 {@code net.goo.brutality.util.magic.ManaHelper}：普通类 + 静态方法，
 * 调用方拿到实体与属性直接调，不持有状态。
 *
 * <p><b>铁律</b>：同一来源的修正必须用<b>固定 UUID</b>（常量统一放 {@link ModifierUUIDs}，
 * 绝不要每次 new 一个随机 UUID）。{@link #apply} 会先移除同 UUID 旧值再挂新值，保证幂等不叠加；
 * {@link #remove} 按 UUID 精确命中，移除不掉同来源之外的东西。
 */
public class AttributeModifierHelper {

    private AttributeModifierHelper() {
    }

    /** 构造一个修正器（仅创建，不落实体）。 */
    public static AttributeModifier make(UUID uuid, String name, double amount, AttributeModifier.Operation operation) {
        return new AttributeModifier(uuid, name, amount, operation);
    }

    /**
     * 给实体挂修正器（幂等）：同 UUID 已存在则先删后挂，防止叠加。
     *
     * @return 挂上的修正器；目标属性在该实体上不存在时返回 null（调用方自行决定是否兜底提示）
     */
    public static AttributeModifier apply(LivingEntity entity, Attribute attribute, UUID uuid, String name, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return null;
        }
        remove(entity, attribute, uuid);
        AttributeModifier modifier = make(uuid, name, amount, operation);
        instance.addPermanentModifier(modifier);
        return modifier;
    }

    /**
     * 给实体挂<b>临时</b>修正器（可被原版 attribute 刷新自然清掉，适合短时 Buff 场景；同样幂等）。
     */
    public static AttributeModifier applyTransient(LivingEntity entity, Attribute attribute, UUID uuid, String name, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return null;
        }
        remove(entity, attribute, uuid);
        AttributeModifier modifier = make(uuid, name, amount, operation);
        instance.addTransientModifier(modifier);
        return modifier;
    }

    /** 移除同 UUID 修正器；返回是否确实存在并移除。 */
    public static boolean remove(LivingEntity entity, Attribute attribute, UUID uuid) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null || instance.getModifier(uuid) == null) {
            return false;
        }
        instance.removeModifier(uuid);
        return true;
    }

    /** 实体上是否挂着该 UUID 的修正器。 */
    public static boolean has(LivingEntity entity, Attribute attribute, UUID uuid) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance != null && instance.getModifier(uuid) != null;
    }

    /** 取实体该属性的当前总数值（ManaHelper 的读取风格：getAttribute(...).getValue()）。 */
    public static double getValue(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? attribute.getDefaultValue() : instance.getValue();
    }
}
