package com.fantasyclouds.mage.init;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.entity.SpellBolt;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class MageEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Mage.MODID);

    public static final RegistryObject<EntityType<SpellBolt>> SPELL_BOLT = ENTITY_TYPES.register("spell_bolt",
            () -> EntityType.Builder.<SpellBolt>of(SpellBolt::new, MobCategory.MISC)
                    .sized(0.4f, 0.4f)
                    .setTrackingRange(64)
                    .setUpdateInterval(1)
                    .build("spell_bolt"));
}
