package com.fantasyclouds.mage;

import com.fantasyclouds.mage.init.MageAttributes;
import com.fantasyclouds.mage.init.MageEffects;
import com.fantasyclouds.mage.init.MageEnchantments;
import com.fantasyclouds.mage.init.MageEntities;
import com.fantasyclouds.mage.init.MagePotions;
import com.fantasyclouds.mage.item.MagicCandyItem;
import com.fantasyclouds.mage.item.ManaItem;
import com.fantasyclouds.mage.item.TestStaffItem;
import com.fantasyclouds.mage.network.SyncCasterStatsPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Mage.MODID)
public class Mage {

    public static final String MODID = "mage";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Deferred Registers（block/item/tab 由本类持有，属性/附魔/实体各自 init 类持有）
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /** 测试法杖（施法行为全部由数据包 caster_stats 驱动）。 */
    public static final RegistryObject<Item> TEST_STAFF = ITEMS.register("test_staff", TestStaffItem::new);
    /** 魔法糖果（迁移自 SOFL）：吃回复 40 Brutality 蓝。 */
    public static final RegistryObject<Item> MAGIC_CANDY = ITEMS.register("magic_candy", MagicCandyItem::new);
    /** 魔力材料（迁移自 SOFL）：法力增幅药水酿造基底。 */
    public static final RegistryObject<Item> MANA_ITEM = ITEMS.register("mana_item", ManaItem::new);

    public static final RegistryObject<CreativeModeTab> MAGE_TAB = CREATIVE_MODE_TABS.register("mage_tab",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(TEST_STAFF.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(TEST_STAFF.get());
                        output.accept(MAGIC_CANDY.get());
                        output.accept(MANA_ITEM.get());
                    })
                    .build());

    // 网络通道（法杖配置同步）
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel PACKET_HANDLER = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, MODID), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);
    private static int messageID = 0;

    public static <T> void addNetworkMessage(Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder,
                                             Function<FriendlyByteBuf, T> decoder,
                                             BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
        PACKET_HANDLER.registerMessage(messageID, messageType, encoder, decoder, messageConsumer);
        messageID++;
    }

    public Mage() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        MageAttributes.ATTRIBUTES.register(modEventBus);
        MageEffects.EFFECTS.register(modEventBus);
        MagePotions.POTIONS.register(modEventBus);
        MageEnchantments.ENCHANTMENTS.register(modEventBus);
        MageEntities.ENTITY_TYPES.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> addNetworkMessage(SyncCasterStatsPacket.class,
                SyncCasterStatsPacket::encode, SyncCasterStatsPacket::decode, SyncCasterStatsPacket::handle));
        LOGGER.info("Mage mod common setup");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Mage mod server starting");
    }
}
