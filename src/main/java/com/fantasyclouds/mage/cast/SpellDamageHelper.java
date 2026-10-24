package com.fantasyclouds.mage.cast;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import com.fantasyclouds.mage.init.MageEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 魔法伤害统一结算（移植自 SOFL 的 SpellDamageUtilProcedure，砍掉 MAGIC_BOOST 药水与魔力项链联动）。
 * 伤害 = NBT {@code spell_damage} + {@code spell_enchant_bonus}（mage:forceful 附魔）。
 *
 * <p><b>伤害类型（2026-10-22 定案：字符串 damageType）</b>：伤害类型不再是数字 ballType，
 * 改为字符串 id：{@code magic} / {@code fire} / {@code frost} / {@code lightning} / {@code void}。
 * NBT {@code spell_type} 存字符串；读取兼容旧 int（0-5）自动转字符串（旧 5=arcane 已废弃回退 magic）。
 * 药水效果不绑伤害类型（2026-10-22 移除）：施法者/目标效果由独立原语 grantEffect/applyEffect 配置。
 */
public class SpellDamageHelper {

    public static final String TYPE_MAGIC = "magic";
    public static final String TYPE_FIRE = "fire";
    public static final String TYPE_FROST = "frost";
    public static final String TYPE_LIGHTNING = "lightning";
    public static final String TYPE_VOID = "void";

    /** 字符串 id → DamageTypes。未识别回退 INDIRECT_MAGIC。 */
    private static final Map<String, ResourceKey<DamageType>> TYPE_MAP = Map.of(
            TYPE_MAGIC, DamageTypes.INDIRECT_MAGIC,
            TYPE_FIRE, DamageTypes.FIREBALL,
            TYPE_FROST, DamageTypes.FREEZE,
            TYPE_LIGHTNING, DamageTypes.LIGHTNING_BOLT,
            TYPE_VOID, DamageTypes.FELL_OUT_OF_WORLD
    );

    /** 旧 int ballType（0-4）→ 字符串 id（NBT 向后兼容读取）；旧 5=arcane 已废弃回退 magic。 */
    private static final Map<Integer, String> LEGACY_TYPE_BY_INT = new HashMap<>();
    static {
        LEGACY_TYPE_BY_INT.put(0, TYPE_MAGIC);
        LEGACY_TYPE_BY_INT.put(1, TYPE_FIRE);
        LEGACY_TYPE_BY_INT.put(2, TYPE_FROST);
        LEGACY_TYPE_BY_INT.put(3, TYPE_LIGHTNING);
        LEGACY_TYPE_BY_INT.put(4, TYPE_VOID);
    }

    private SpellDamageHelper() {}

    /** 弹射物命中结算：读弹射物 persistentData 的 spell_damage / spell_type / spell_enchant_bonus。 */
    public static void applySpellDamage(LevelAccessor world, Entity target, Entity source, Entity directSource) {
        if (target == null || source == null || directSource == null) return;
        CompoundTag tag = directSource.getPersistentData();
        double damage = tag.getDouble("spell_damage") + tag.getDouble("spell_enchant_bonus");
        String type = readType(tag);
        applyDamage(world, target, source, withMagicBoost(source, damage), type);
    }

    /** 直接数值结算（给后续无需弹射物的用法）。type 用字符串 damageType id。 */
    public static void applySpellDamageFromValues(LevelAccessor world, Entity target, Entity source, double baseDamage, String type) {
        applyDamage(world, target, source, withMagicBoost(source, baseDamage), type);
    }

    /**
     * 旧 {@code int ballType} 兼容重载（SOFL 等已编译调用方，2026-10-24 补回二进制兼容）。
     * 0=magic/1=fire/2=frost/3=lightning/4=void；旧 5(arcane) 已废弃回退 magic。
     * 新代码请用上面的 {@link String} 版本。
     */
    public static void applySpellDamageFromValues(LevelAccessor world, Entity target, Entity source, double baseDamage, int legacyType) {
        applySpellDamageFromValues(world, target, source, baseDamage, fromLegacyInt(legacyType));
    }

    /**
     * 范围爆裂结算（AreaStep）：对 center 半径 {@code spell_area} 内所有 LivingEntity
     * （排除 source/owner）逐个结算魔法伤害。伤害从弹 NBT 读（spell_damage + spell_enchant_bonus）。
     * 走本类统一魔法结算 → 吃 L2 法伤属性。目标药水效果由 applyEffect 原语另行施加。
     */
    public static void applyAreaDamage(Level level, Vec3 center, Entity source, CompoundTag tag) {
        if (level == null || center == null || source == null || tag == null || level.isClientSide) return;
        double radius = tag.getDouble("spell_area");
        if (radius <= 0) return;
        double damage = tag.getDouble("spell_damage") + tag.getDouble("spell_enchant_bonus");
        if (damage <= 0) return;
        String type = readType(tag);

        AABB box = new AABB(center, center).inflate(radius);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != source && e.isAlive())) {
            applyDamage(level, target, source, withMagicBoost(source, damage), type);
        }
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
    private static void applyDamage(LevelAccessor world, Entity target, Entity source, double damage, String type) {
        if (target == null || source == null || damage <= 0) return;
        ResourceKey<DamageType> dmgType = TYPE_MAP.getOrDefault(type, DamageTypes.INDIRECT_MAGIC);
        DamageSource dmgSource = new DamageSource(
                world.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(dmgType), source);
        target.hurt(dmgSource, (float) damage);
        // 2026-10-22 移除伤害类型的药水副作用（arcane 不再自动给施法者再生/目标减速）：
        // 施法者/目标药水效果是独立原语 buff，由 JSON 显式配置——副作用不该绑伤害类型。
    }

    /** 从弹 NBT 读伤害类型：字符串优先，兼容旧 int（ballType 0-5）自动转字符串。 */
    public static String readType(CompoundTag tag) {
        String id = tag.getString("spell_type");
        if (!id.isEmpty()) return id;
        return fromLegacyInt(tag.getInt("spell_type"));
    }

    /** 旧 int ballType（0-5）→ 字符串 id（数据包 JSON 向后兼容）。未识别回退 magic。 */
    public static String fromLegacyInt(int legacy) {
        return LEGACY_TYPE_BY_INT.getOrDefault(legacy, TYPE_MAGIC);
    }

    /** 显示名（HUD 用）。未识别回退 "Unknown"。 */
    public static String displayName(String type) {
        return switch (type) {
            case TYPE_MAGIC -> "Magic";
            case TYPE_FIRE -> "Fire";
            case TYPE_FROST -> "Frost";
            case TYPE_LIGHTNING -> "Lightning";
            case TYPE_VOID -> "Void";
            default -> "Unknown";
        };
    }
}