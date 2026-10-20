package com.fantasyclouds.mage.cast;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import com.fantasyclouds.mage.init.MageEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelAccessor;

import java.util.Map;

/**
 * 魔法伤害统一结算（移植自 SOFL 的 SpellDamageUtilProcedure，砍掉 MAGIC_BOOST 药水与魔力项链联动）。
 * 伤害 = NBT {@code spell_damage} + {@code spell_enchant_bonus}（mage:forceful 附魔）。
 * ballType → DamageTypes 映射与副作用（type 5 = Arcane：目标减速 + 施法者再生）保留。
 */
public class SpellDamageHelper {

    private static final Map<Integer, ResourceKey<DamageType>> TYPE_MAP = Map.of(
            0, DamageTypes.INDIRECT_MAGIC,
            1, DamageTypes.FIREBALL,
            2, DamageTypes.FREEZE,
            3, DamageTypes.LIGHTNING_BOLT,
            4, DamageTypes.FELL_OUT_OF_WORLD,
            5, DamageTypes.INDIRECT_MAGIC
    );

    private SpellDamageHelper() {}

    /** 弹射物命中结算：读弹射物 persistentData 的 spell_damage / spell_type / spell_enchant_bonus。 */
    public static void applySpellDamage(LevelAccessor world, Entity target, Entity source, Entity directSource) {
        if (target == null || source == null || directSource == null) return;
        CompoundTag tag = directSource.getPersistentData();
        double damage = tag.getDouble("spell_damage") + tag.getDouble("spell_enchant_bonus");
        int type = tag.getInt("spell_type");
        applyDamage(world, target, source, withMagicBoost(source, damage), type);
    }

    /** 直接数值结算（给后续无需弹射物的用法）。 */
    public static void applySpellDamageFromValues(LevelAccessor world, Entity target, Entity source, double baseDamage, int type) {
        applyDamage(world, target, source, withMagicBoost(source, baseDamage), type);
    }

    /** 法力增幅药水加成（迁移自 SOFL SpellDamageUtil）：伤害 +（amplifier+1）。 */
    private static double withMagicBoost(Entity source, double damage) {
        if (source instanceof LivingEntity liv && liv.hasEffect(MageEffects.MAGIC_BOOST.get())) {
            damage += liv.getEffect(MageEffects.MAGIC_BOOST.get()).getAmplifier() + 1;
        }
        return damage;
    }

    /**
     * 莱特兰法伤属性不在此处乘算：l2damagetracker 会<b>原生</b>对魔法伤害类型施加
     * {@code magic_damage} 倍率，这里再乘 = 双倍（实证：任何莱特兰法伤增幅都变 2 倍）。
     * 对齐原 SOFL：实际结算只加 强力 + 魔力强化；HUD 面板的 × magic_damage 是显示预估。
     */
    private static void applyDamage(LevelAccessor world, Entity target, Entity source, double damage, int type) {
        if (target == null || source == null || damage <= 0) return;
        ResourceKey<DamageType> dmgType = TYPE_MAP.getOrDefault(type, DamageTypes.INDIRECT_MAGIC);
        DamageSource dmgSource = new DamageSource(
                world.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(dmgType), source);
        target.hurt(dmgSource, (float) damage);
        if (type == 5) {
            if (target instanceof LivingEntity le) le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 1));
            if (source instanceof LivingEntity le) le.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20, 0));
        }
    }
}
