package com.fantasyclouds.mage.init;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.data.CasterRegistry;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 自注册法术附魔（adventuresmod 的 spell_potency/forceful/burst 已在注册表被
 * {@code AdventuresEnchantRegistryBanMixin} 剔除，这里复刻三者，行为/等级对齐原版）。
 * 适用性按 {@link CasterRegistry#isCaster}（数据包配置过 caster_stats 的物品即法杖）。
 */
public class MageEnchantments {

    public static final DeferredRegister<Enchantment> ENCHANTMENTS = DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, Mage.MODID);

    /** 快速施法：使法杖用 fastCooldown 而非 slowCooldown（对齐 adventuresmod max=1）。 */
    public static final RegistryObject<Enchantment> SPELL_POTENCY = ENCHANTMENTS.register("spell_potency", SpellPotencyEnchant::new);
    /** 强效法术：每级 +1 法伤（对齐 adventuresmod max=3）。 */
    public static final RegistryObject<Enchantment> FORCEFUL = ENCHANTMENTS.register("forceful", ForcefulEnchant::new);
    /** 爆裂法术：命中点燃目标 10 秒（对齐 adventuresmod max=1）。 */
    public static final RegistryObject<Enchantment> BURST = ENCHANTMENTS.register("burst", BurstEnchant::new);

    /** 仅可附到法杖（caster_stats 配置过的物品）上的通用附魔基类。 */
    public abstract static class CasterEnchantment extends Enchantment {
        protected CasterEnchantment(Rarity rarity, EnchantmentCategory category, EquipmentSlot... slots) {
            super(rarity, category, slots);
        }

        @Override
        public boolean canApplyAtEnchantingTable(ItemStack stack) {
            return CasterRegistry.isCaster(stack) || super.canApplyAtEnchantingTable(stack);
        }
    }

    public static class SpellPotencyEnchant extends CasterEnchantment {
        public SpellPotencyEnchant() {
            super(Rarity.RARE, EnchantmentCategory.WEAPON, EquipmentSlot.MAINHAND);
        }

        @Override
        public int getMaxLevel() {
            return 1;
        }

        @Override
        public int getMinCost(int level) {
            return 15;
        }

        @Override
        public int getMaxCost(int level) {
            return 40;
        }
    }

    public static class ForcefulEnchant extends CasterEnchantment {
        public ForcefulEnchant() {
            super(Rarity.RARE, EnchantmentCategory.WEAPON, EquipmentSlot.MAINHAND);
        }

        @Override
        public int getMaxLevel() {
            return 3;
        }

        @Override
        public int getMinCost(int level) {
            return 10 + level * 6;
        }

        @Override
        public int getMaxCost(int level) {
            return 35 + level * 6;
        }
    }

    public static class BurstEnchant extends CasterEnchantment {
        public BurstEnchant() {
            super(Rarity.RARE, EnchantmentCategory.WEAPON, EquipmentSlot.MAINHAND);
        }

        @Override
        public int getMaxLevel() {
            return 1;
        }

        @Override
        public int getMinCost(int level) {
            return 15;
        }

        @Override
        public int getMaxCost(int level) {
            return 40;
        }
    }
}
