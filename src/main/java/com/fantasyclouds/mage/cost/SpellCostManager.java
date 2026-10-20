package com.fantasyclouds.mage.cost;

import com.fantasyclouds.mage.Config;
import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.util.AttributeModifierHelper;
import com.fantasyclouds.mage.util.ModifierUUIDs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 法术消耗资源管理（纯属性扣减·零依赖）。
 *
 * <p>模型：JSON {@code cost.attribute} 指定的属性即资源池。
 * - 当前值 = {@code getAttributeValue(attribute)}（含已扣修饰符，即剩余量）；
 * - 消耗 = 记账 consumed += amount，并挂固定 UUID 的 {@code -consumed} 永久修饰符（幂等）；
 * - 回复 = 每 tick 从 consumed 中减 {@code regenPerSecond/20}，回到 ≤0 时摘掉修饰符。
 *
 * <p>记账存玩家 persistentData 的 {@code mage_cost} 复合标签（attrId -> consumed），跨会话持久。
 * 消耗属性不存在（未注册/未挂到玩家）时不拦截，避免配置错误锁死施法。
 */
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SpellCostManager {

    private static final String LEDGER_TAG = "mage_cost";

    private SpellCostManager() {}

    /**
     * 检查并消耗。成功返回 true（已扣）；失败返回 false 且不扣。
     *
     * <p>两条路径：
     * - {@code cost.attribute} 命名空间为 {@code brutality} → <b>反射桥接</b>调 Brutality 的
     *   ManaHelper 扣<b>当前蓝条</b>（非上限；Mage 零编译依赖，运行时检测到才生效，没有则放行）；
     * - 其他属性 → 纯属性扣减（记账 + 固定 UUID 修饰符 + Mage 侧回复）。
     */
    public static boolean tryConsume(Player player, ResourceLocation costAttribute, double amount) {
        if (costAttribute == null || amount <= 0) return true;
        if ("brutality".equals(costAttribute.getNamespace())) {
            return tryConsumeBrutalityMana(player, amount);
        }
        Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(costAttribute);
        if (attr == null || player.getAttribute(attr) == null) return true; // 属性不可用：不拦截
        if (player.getAttributeValue(attr) < amount) return false;           // 剩余不足

        Map<ResourceLocation, Double> ledger = readLedger(player);
        double consumed = ledger.getOrDefault(costAttribute, 0.0) + amount;
        ledger.put(costAttribute, consumed);
        applyConsumedModifier(player, attr, consumed);
        writeLedger(player, ledger);
        return true;
    }

    /**
     * 恢复资源（如魔法糖果 +40 蓝）：把已消耗记账减回去，等价于给当前值加 amount。
     * brutality 命名空间走反射桥接加当前蓝条。返回是否确实恢复。
     */
    public static boolean restore(Player player, ResourceLocation costAttribute, double amount) {
        if (costAttribute == null || amount <= 0) return false;
        if ("brutality".equals(costAttribute.getNamespace())) {
            return restoreBrutalityMana(player, amount);
        }
        Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(costAttribute);
        if (attr == null || player.getAttribute(attr) == null) return false;
        Map<ResourceLocation, Double> ledger = readLedger(player);
        double consumed = ledger.getOrDefault(costAttribute, 0.0) - amount;
        if (consumed <= 0) {
            AttributeModifierHelper.remove(player, attr, ModifierUUIDs.from(ledgerUuidSeed(costAttribute)));
            ledger.remove(costAttribute);
        } else {
            ledger.put(costAttribute, consumed);
            applyConsumedModifier(player, attr, consumed);
        }
        writeLedger(player, ledger);
        return true;
    }

    /**
     * Brutality 蓝反射桥（零编译依赖）：扣<b>当前蓝条</b>（capability），不是上限属性。
     * 运行时检测到 Brutality 才生效；没有/反射失败则放行不扣。
     */
    private static boolean tryConsumeBrutalityMana(Player player, double amount) {
        try {
            Class<?> helper = Class.forName("net.goo.brutality.util.magic.ManaHelper");
            Method getMana = helper.getMethod("getMana", Player.class);
            Method modify = helper.getMethod("modifyManaValue", Player.class, float.class);
            float current = (float) getMana.invoke(null, player);
            if (current < (float) amount) return false;
            modify.invoke(null, player, -(float) amount);
            return true;
        } catch (Exception e) {
            Mage.LOGGER.warn("[Mage] Brutality mana bridge unavailable, cost skipped: {}", e.toString());
            return true;
        }
    }

    /** Brutality 蓝反射桥：加当前蓝条（上限由 Brutality 自己的 ticker 钳制）。 */
    private static boolean restoreBrutalityMana(Player player, double amount) {
        try {
            Class<?> helper = Class.forName("net.goo.brutality.util.magic.ManaHelper");
            Method modify = helper.getMethod("modifyManaValue", Player.class, float.class);
            modify.invoke(null, player, (float) amount);
            return true;
        } catch (Exception e) {
            Mage.LOGGER.warn("[Mage] Brutality mana bridge unavailable, restore skipped: {}", e.toString());
            return false;
        }
    }

    /** 每 tick 回复。 */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        Map<ResourceLocation, Double> ledger = readLedger(player);
        if (ledger.isEmpty()) return;

        boolean changed = false;
        for (Map.Entry<ResourceLocation, Double> e : new HashMap<>(ledger).entrySet()) {
            ResourceLocation attrId = e.getKey();
            double consumed = e.getValue() - Config.resourceRegenPerSecond / 20.0;
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(attrId);
            if (consumed <= 0) {
                if (attr != null && player.getAttribute(attr) != null) {
                    AttributeModifierHelper.remove(player, attr, ModifierUUIDs.from(ledgerUuidSeed(attrId)));
                }
                ledger.remove(attrId);
            } else {
                ledger.put(attrId, consumed);
                if (attr != null && player.getAttribute(attr) != null) {
                    applyConsumedModifier(player, attr, consumed);
                }
            }
            changed = true;
        }
        if (changed) writeLedger(player, ledger);
    }

    private static void applyConsumedModifier(Player player, Attribute attr, double consumed) {
        UUID uuid = ModifierUUIDs.from(ledgerUuidSeed(ForgeRegistries.ATTRIBUTES.getKey(attr)));
        AttributeModifierHelper.apply(player, attr, uuid, "mage_spell_cost",
                -consumed, AttributeModifier.Operation.ADDITION);
    }

    /** 固定种子：按属性 id 生成稳定 UUID，幂等替换不叠加。 */
    private static String ledgerUuidSeed(ResourceLocation attrId) {
        return "mage_cost_" + attrId;
    }

    private static Map<ResourceLocation, Double> readLedger(Player player) {
        Map<ResourceLocation, Double> map = new HashMap<>();
        CompoundTag tag = player.getPersistentData().getCompound(LEDGER_TAG);
        for (String key : tag.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl != null) map.put(rl, tag.getDouble(key));
        }
        return map;
    }

    private static void writeLedger(Player player, Map<ResourceLocation, Double> ledger) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<ResourceLocation, Double> e : ledger.entrySet()) {
            tag.putDouble(e.getKey().toString(), e.getValue());
        }
        player.getPersistentData().put(LEDGER_TAG, tag);
    }
}
