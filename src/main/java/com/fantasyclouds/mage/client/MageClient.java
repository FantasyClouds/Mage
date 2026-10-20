package com.fantasyclouds.mage.client;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.client.hud.CasterHudOverlay;
import com.fantasyclouds.mage.client.renderer.SpellBoltRenderer;
import com.fantasyclouds.mage.init.MageEntities;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** 客户端装配：内置弹渲染器 + 法杖数值 HUD。 */
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MageClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        EntityRenderers.register(MageEntities.SPELL_BOLT.get(), SpellBoltRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("caster_hud", CasterHudOverlay.RENDER);
    }

    private MageClient() {}
}
