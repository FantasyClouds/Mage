package com.fantasyclouds.mage.data;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 法杖配置注册表（服务端权威；客户端用 {@link com.fantasyclouds.mage.client.CasterStatsClientCache}）。
 * 数据来源：{@link CasterStatsLoader} 从数据包 {@code caster_stats} 文件夹热加载。
 */
public class CasterRegistry {

    private static final Map<ResourceLocation, CasterStats> CASTERS = new HashMap<>();

    private CasterRegistry() {}

    public static void register(ResourceLocation itemId, CasterStats stats) {
        CASTERS.put(itemId, stats);
    }

    public static CasterStats get(ResourceLocation itemId) {
        return CASTERS.get(itemId);
    }

    public static CasterStats get(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null ? get(key) : null;
    }

    /** 该物品是否配置了法杖数据（附魔适用性 / HUD 显示都以此为准）。 */
    public static boolean isCaster(ItemStack stack) {
        return get(stack) != null;
    }

    public static void clear() {
        CASTERS.clear();
    }

    public static Map<ResourceLocation, CasterStats> getAll() {
        return Collections.unmodifiableMap(CASTERS);
    }
}
