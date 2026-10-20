package com.fantasyclouds.mage.init;

import com.fantasyclouds.mage.Mage;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 自注册属性（范式照抄 story 模组 StoryOfForgottenLandModAttributes）。
 * {@code mage:mana} 为法术资源池的默认消耗属性（纯属性扣减模型：当前值=base+modifiers-已扣）。
 */
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class MageAttributes {

    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(ForgeRegistries.ATTRIBUTES, Mage.MODID);

    public static final RegistryObject<Attribute> MANA = ATTRIBUTES.register("mana",
            () -> new RangedAttribute("attribute." + Mage.MODID + ".mana", 100, 0, 100000).setSyncable(true));

    @SubscribeEvent
    public static void addAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, MANA.get());
    }

    /**
     * PlayerEvent.Clone 是 FORGE 事件（非 IModBusEvent），必须放默认（FORGE）bus 订阅者，
     * 不能与 MOD bus 的 MageAttributes 同用（否则 @SubscribeEvent 校验直接抛异常）。
     * 死亡重生后保留 base 值。
     */
    @Mod.EventBusSubscriber(modid = Mage.MODID)
    public static class CloneHandler {
        @SubscribeEvent
        public static void persistOnClone(PlayerEvent.Clone event) {
            Player oldP = event.getOriginal();
            Player newP = (Player) event.getEntity();
            if (oldP.getAttribute(MANA.get()) != null && newP.getAttribute(MANA.get()) != null) {
                newP.getAttribute(MANA.get()).setBaseValue(oldP.getAttribute(MANA.get()).getBaseValue());
            }
        }
    }
}
