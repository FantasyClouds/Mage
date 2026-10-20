package com.fantasyclouds.mage;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

// A minimal config class. Add options here instead of hardcoding values in code.
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DEBUG = BUILDER.comment("Whether to log debug info on common setup").define("logDebug", true);

    private static final ForgeConfigSpec.DoubleValue RESOURCE_REGEN_PER_SECOND = BUILDER.comment("法术资源（cost.attribute 指定的属性）每秒回复多少点")
            .defineInRange("resourceRegenPerSecond", 1.0, 0.0, 10000.0);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean logDebug;
    public static double resourceRegenPerSecond;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        logDebug = LOG_DEBUG.get();
        resourceRegenPerSecond = RESOURCE_REGEN_PER_SECOND.get();
    }
}
