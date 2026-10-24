package com.fantasyclouds.mage.cost;

import com.fantasyclouds.mage.Config;
import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.item.MagicCandyItem;
import com.fantasyclouds.mage.util.AttributeModifierHelper;
import com.fantasyclouds.mage.util.ModifierUUIDs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
     * 检查是否足以施法（<b>不扣</b>）。施法成功后才调 {@link #consume} 真正扣——保证"没真正施法不扣蓝"。
     *
     * <p>两条路径：
     * - {@code cost.attribute} 命名空间为 {@code brutality} → <b>反射桥接</b>读 Brutality
     *   ManaHelper 的<b>当前蓝条</b>（非上限；零编译依赖，运行时无则放行）；
     * - 其他属性 → 读当前属性值是否足够。
     */
    public static boolean canAfford(Player player, ResourceLocation costAttribute, double amount) {
        if (costAttribute == null || amount <= 0) return true;
        if ("brutality".equals(costAttribute.getNamespace())) {
            return hasBrutalityMana(player, amount);
        }
        Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(costAttribute);
        if (attr == null || player.getAttribute(attr) == null) return true; // 属性不可用：不拦截
        return player.getAttributeValue(attr) >= amount;
    }

    /**
     * 真正扣减（施法流水线执行成功后调用）。返回是否已扣。
     *
     * <p>brutality 命名空间走反射桥扣当前蓝条；其他属性走记账 + 固定 UUID 修饰符。
     * 属性不存在/反射失败 → 不扣但返回 true（放行施法，避免配置错误锁死法术）。
     */
    public static boolean consume(Player player, ResourceLocation costAttribute, double amount) {
        if (costAttribute == null || amount <= 0) return true;
        if ("brutality".equals(costAttribute.getNamespace())) {
            float before = currentBrutalityMana(player);
            boolean ok = consumeBrutalityMana(player, amount);
            float after = currentBrutalityMana(player);
            Mage.LOGGER.info("[MageCost] brutality consume amount={} mana {} -> {} (ok={}) thread={} player={} client={} stack={}",
                    amount, before, after, ok,
                    Thread.currentThread().getName(),
                    player.getClass().getSimpleName(),
                    player.level().isClientSide(),
                    shortStack());
            return ok;
        }
        Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(costAttribute);
        if (attr == null || player.getAttribute(attr) == null) return true;

        Map<ResourceLocation, Double> ledger = readLedger(player);
        double consumed = ledger.getOrDefault(costAttribute, 0.0) + amount;
        ledger.put(costAttribute, consumed);
        applyConsumedModifier(player, attr, consumed);
        writeLedger(player, ledger);
        return true;
    }

    /** 兼容旧调用：检查+扣一次完成（不推荐——施法应先 canAfford，成功后 consume）。 */
    public static boolean tryConsume(Player player, ResourceLocation costAttribute, double amount) {
        if (!canAfford(player, costAttribute, amount)) return false;
        return consume(player, costAttribute, amount);
    }

    /**
     * 魔法糖果自动服用兜底（恢复 SOFL RemoveManaProcedure 原行为）：蓝不住却要施法时，
     * 自动从背包找魔法糖果吃一颗（+40 蓝），重新判定是否足够。零依赖：按物品注册名匹配——
     * {@code mage:magic_candy}（本模组）/ {@code story_of_forgotten_land:magic_candy}（SOFL）。
     *
     * @return 是否「吃糖后」足够施法（含本不需要糖果就够的情况）。
     */
    public static boolean affordWithCandy(Player player, ResourceLocation costAttribute, double amount) {
        if (canAfford(player, costAttribute, amount)) return true; // 本身够，无需吃糖
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key == null) continue;
            String id = key.toString();
            if (!id.equals("mage:magic_candy") && !id.equals("story_of_forgotten_land:magic_candy")) continue;
            stack.shrink(1);
            restore(player, costAttribute, MagicCandyItem.MANA_AMOUNT);
            Mage.LOGGER.info("[MageCost] auto-ate magic candy (afford fail, cost={}, manaNow={})",
                    amount, currentBrutalityMana(player));
            return canAfford(player, costAttribute, amount); // 剩一个后再查一次，仍不够=真不够
        }
        return false;
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

    /** 诊断：取调用栈前 7 帧（跳过本方法自身的帧），用于定位是谁在 Render thread 扣蓝。 */
    private static String shortStack() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 1; i < frames.length && shown < 7; i++, shown++) {
            if (shown > 0) sb.append(" <- ");
            sb.append(frames[i].getClassName()).append('#').append(frames[i].getMethodName())
              .append(':').append(frames[i].getLineNumber());
        }
        return sb.toString();
    }

    /** 读 Brutality 当前蓝（诊断用；反射失败返回 NaN）。 */
    private static float currentBrutalityMana(Player player) {
        try {
            Class<?> helper = Class.forName("net.goo.brutality.util.magic.ManaHelper");
            Method getMana = helper.getMethod("getMana", Player.class);
            return (float) getMana.invoke(null, player);
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    /** Brutality 蓝反射桥：检查当前蓝条是否足够（不扣）。无/反射失败 → 放行。 */
    private static boolean hasBrutalityMana(Player player, double amount) {
        try {
            Class<?> helper = Class.forName("net.goo.brutality.util.magic.ManaHelper");
            Method getMana = helper.getMethod("getMana", Player.class);
            float current = (float) getMana.invoke(null, player);
            return current >= (float) amount;
        } catch (Exception e) {
            Mage.LOGGER.warn("[Mage] Brutality mana bridge unavailable, check skipped: {}", e.toString());
            return true;
        }
    }

    /**
     * Brutality 蓝反射桥（零编译依赖）：扣<b>当前蓝条</b>（capability），不是上限属性。
     * 运行时检测到 Brutality 才生效；没有/反射失败则放行不扣。
     */
    private static boolean consumeBrutalityMana(Player player, double amount) {
        try {
            Class<?> helper = Class.forName("net.goo.brutality.util.magic.ManaHelper");
            Method modify = helper.getMethod("modifyManaValue", Player.class, float.class);
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
