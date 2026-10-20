package com.fantasyclouds.mage.network;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.client.CasterStatsClientCache;
import com.fantasyclouds.mage.data.CasterStats;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 法杖配置全量同步包（服务端 → 客户端），客户端写入 {@link CasterStatsClientCache} 供 HUD/提示使用。
 */
public class SyncCasterStatsPacket {

    private final Map<String, CasterStats> data;

    public SyncCasterStatsPacket(Map<String, CasterStats> data) {
        this.data = data;
    }

    public static void encode(SyncCasterStatsPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.data.size());
        for (Map.Entry<String, CasterStats> e : msg.data.entrySet()) {
            CasterStats s = e.getValue();
            buf.writeUtf(e.getKey());
            buf.writeUtf(s.item());
            buf.writeBoolean(s.name() != null);
            if (s.name() != null) buf.writeUtf(s.name());
            buf.writeUtf(s.projectileType());
            buf.writeDouble(s.ballDamage());
            buf.writeInt(s.ballType());
            buf.writeUtf(s.costAttribute().toString());
            buf.writeDouble(s.costAmount());
            buf.writeDouble(s.projectileSpeed());
            buf.writeInt(s.fastCooldown());
            buf.writeInt(s.slowCooldown());
            buf.writeBoolean(s.isOverwrite());
            buf.writeInt(s.lifetime());
        }
    }

    public static SyncCasterStatsPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, CasterStats> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String key = buf.readUtf();
            String item = buf.readUtf();
            String name = buf.readBoolean() ? buf.readUtf() : null;
            String projectileType = buf.readUtf();
            double ballDamage = buf.readDouble();
            int ballType = buf.readInt();
            ResourceLocation costAttr = ResourceLocation.tryParse(buf.readUtf());
            double costAmount = buf.readDouble();
            double projectileSpeed = buf.readDouble();
            int fastCooldown = buf.readInt();
            int slowCooldown = buf.readInt();
            boolean isOverwrite = buf.readBoolean();
            int lifetime = buf.readInt();
            map.put(key, new CasterStats(item, name, projectileType, ballDamage, ballType, costAttr, costAmount,
                    projectileSpeed, fastCooldown, slowCooldown, isOverwrite, lifetime));
        }
        return new SyncCasterStatsPacket(map);
    }

    public static void handle(SyncCasterStatsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> CasterStatsClientCache.update(msg.data));
        ctx.get().setPacketHandled(true);
    }

    public static void sendToAll(Map<String, CasterStats> data) {
        Mage.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(), new SyncCasterStatsPacket(data));
    }

    public static void sendToPlayer(ServerPlayer player, Map<String, CasterStats> data) {
        Mage.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SyncCasterStatsPacket(data));
    }
}
