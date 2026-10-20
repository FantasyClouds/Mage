package com.fantasyclouds.mage.client;

import com.fantasyclouds.mage.data.CasterStats;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端法杖配置缓存，由 {@code SyncCasterStatsPacket} 在登录/数据包同步时更新。
 * 供 HUD（手持判定 + 数值展示）与附魔适用性提示使用。
 */
public class CasterStatsClientCache {

    private static Map<String, CasterStats> stats = new HashMap<>();

    private CasterStatsClientCache() {}

    public static void update(Map<String, CasterStats> data) {
        stats = data;
    }

    public static CasterStats get(String itemId) {
        return stats.get(itemId);
    }
}
