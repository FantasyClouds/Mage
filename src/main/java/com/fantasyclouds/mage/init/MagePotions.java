package com.fantasyclouds.mage.init;

import com.fantasyclouds.mage.Mage;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 法力增幅药水（迁移自 SOFL，id 照搬：magic_damage_boost / _long / _plus）。
 */
public class MagePotions {

    public static final DeferredRegister<Potion> POTIONS = DeferredRegister.create(ForgeRegistries.POTIONS, Mage.MODID);

    public static final RegistryObject<Potion> MAGIC_DAMAGE_BOOST = POTIONS.register("magic_damage_boost",
            () -> new Potion(new MobEffectInstance(MageEffects.MAGIC_BOOST.get(), 3600, 1, false, true)));
    public static final RegistryObject<Potion> LONG_MAGIC_DAMAGE_BOOST = POTIONS.register("magic_damage_boost_long",
            () -> new Potion(new MobEffectInstance(MageEffects.MAGIC_BOOST.get(), 9600, 1, false, true)));
    public static final RegistryObject<Potion> PLUS_MAGIC_DAMAGE_BOOST = POTIONS.register("magic_damage_boost_plus",
            () -> new Potion(new MobEffectInstance(MageEffects.MAGIC_BOOST.get(), 1800, 2, false, true)));
}
