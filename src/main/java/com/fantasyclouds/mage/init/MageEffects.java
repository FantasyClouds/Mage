package com.fantasyclouds.mage.init;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.potion.MagicBoostMobEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class MageEffects {

    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, Mage.MODID);

    /** 法力增幅（迁移自 SOFL）：施法伤害 +（amplifier+1）。 */
    public static final RegistryObject<MobEffect> MAGIC_BOOST = EFFECTS.register("magic_boost", MagicBoostMobEffect::new);
}
