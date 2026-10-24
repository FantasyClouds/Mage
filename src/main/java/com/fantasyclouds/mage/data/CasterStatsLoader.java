package com.fantasyclouds.mage.data;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.cast.SpellDamageHelper;
import com.fantasyclouds.mage.network.SyncCasterStatsPacket;
import com.fantasyclouds.mage.spell.DamageGroup;
import com.fantasyclouds.mage.spell.SpellAnalyzer;
import com.fantasyclouds.mage.spell.SpellStep;
import com.fantasyclouds.mage.spell.SpellSteps;
import com.fantasyclouds.mage.spell.StepDefaults;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 法杖配置数据包加载器。读取所有命名空间下的 {@code caster_stats} 文件夹（与 SOFL 复用同名文件夹，
 * SOFL 侧系统已临时注释，避免双系统）。{@code /reload} 可热重载。
 *
 * <p>解析向后兼容：新格式 {@code cost:{attribute,amount}}；只有旧字段 {@code manaCost} 时，
 * 视为消耗默认属性 {@code mage:mana}（Mage 自注册的资源池）。
 */
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CasterStatsLoader extends SimpleJsonResourceReloadListener {

    public static final Gson GSON = new GsonBuilder().create();
    public static final String FOLDER = "caster_stats";

    /** 默认资源池（2026-10-23 定案）：cost 存在但<b>不写 attribute</b> = 消耗 <b>Brutality 当前蓝
     *  （魔力，非属性）</b>。SpellCostManager 按命名空间 "brutality" 走反射桥吃当前蓝条；
     *  id 的 path 段仅作展示，无实际属性含义。给别的属性池显式写 {@code attribute}。 */
    private static final ResourceLocation DEFAULT_COST_POOL = new ResourceLocation("brutality", "mana");

    public CasterStatsLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager manager, ProfilerFiller profiler) {
        CasterRegistry.clear();
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                CasterStats stats = parse(entry.getValue().getAsJsonObject());
                ResourceLocation itemKey = ResourceLocation.tryParse(stats.item());
                if (itemKey != null) {
                    CasterRegistry.register(itemKey, stats);
                }
            } catch (Exception e) {
                Mage.LOGGER.error("[MageCaster] Failed to load caster config: {}", id, e);
            }
        }
        Mage.LOGGER.info("[MageCaster] Loaded {} caster config(s).", CasterRegistry.getAll().size());
    }

    private CasterStats parse(JsonObject json) {
        String item = json.get("item").getAsString();
        String name = json.has("name") ? json.get("name").getAsString() : null;
        String projectileType = json.has("projectileType") ? json.get("projectileType").getAsString() : "";
        double ballDamage = json.has("ballDamage") ? json.get("ballDamage").getAsDouble() : 0.0;
        // 伤害类型（2026-10-22 定案·字符串）：damageType 字符串优先；旧 ballType 数字向后兼容转字符串。
        String damageType = json.has("damageType")
                ? json.get("damageType").getAsString()
                : SpellDamageHelper.fromLegacyInt(json.has("ballType") ? json.get("ballType").getAsInt() : 0);
        double projectileSpeed = json.has("projectileSpeed") ? json.get("projectileSpeed").getAsDouble() : 0.0;
        // 顶层是否显式声明了伤害类型（damageType 字符串 或 旧 ballType 数字）；否则 sequence 首颗 projectile 推演。
        boolean damageTypeExplicit = json.has("damageType") || json.has("ballType");
        int fastCooldown = json.has("fastCooldown") ? json.get("fastCooldown").getAsInt() : 0;
        int slowCooldown = json.has("slowCooldown") ? json.get("slowCooldown").getAsInt() : 0;
        boolean isOverwrite = json.has("isOverwrite") && json.get("isOverwrite").getAsBoolean();
        int lifetime = json.has("lifetime") ? json.get("lifetime").getAsInt() : 0;

        // 消耗：缺 cost = 免费施法；cost 无 attribute = 默认池 Brutality 当前蓝（魔力，非属性，2026-10-23 定案）；
        // cost.attribute 显式写 = 按指定属性池扣减。
        ResourceLocation costAttribute = null;
        double costAmount = 0.0;
        if (json.has("cost")) {
            JsonObject cost = json.getAsJsonObject("cost");
            costAttribute = cost.has("attribute")
                    ? ResourceLocation.tryParse(cost.get("attribute").getAsString())
                    : DEFAULT_COST_POOL;
            costAmount = cost.get("amount").getAsDouble();
        }

        // 有序法术步骤：有 sequence 用它（缺省回落顶层旧字段）；无 sequence = 旧扁平格式折叠成
        // projectile + visual 步骤（顶层旧 projectileType 自动转外观委托，见 SpellSteps）。
        StepDefaults defaults = new StepDefaults(ballDamage, damageType, projectileSpeed, lifetime);
        List<SpellStep> sequence;
        if (json.has("sequence") && json.get("sequence").isJsonArray()) {
            sequence = SpellSteps.parse(json.getAsJsonArray("sequence"), defaults);
        } else {
            sequence = SpellSteps.defaultFlatSequence(projectileType, ballDamage, damageType, projectileSpeed, lifetime);
        }

        // 伤害组（HUD「单体伤害x数量」显示源）：静态推演；空且顶层有伤害→兜底 [ballDamage×1]。
        List<DamageGroup> damageGroups = SpellAnalyzer.analyze(sequence);
        if (damageGroups.isEmpty() && ballDamage > 0) {
            damageGroups = List.of(new DamageGroup(ballDamage, 1));
        }

        // 顶层未显式声明伤害类型 → 从 sequence 首颗 projectile 推演（type 杖多步各类型时 HUD 显示第一颗，
        // 不再恒显兜底 magic——2026-10-22 修「type 杖看着全是魔法伤害」的显示误导）。
        if (!damageTypeExplicit) {
            for (SpellStep step : sequence) {
                if (step instanceof com.fantasyclouds.mage.spell.ProjectileStep p) {
                    damageType = p.damageType();
                    break;
                }
            }
        }

        return new CasterStats(item, name, ballDamage, damageType, costAttribute, costAmount,
                projectileSpeed, fastCooldown, slowCooldown, isOverwrite, lifetime, sequence, damageGroups);
    }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new CasterStatsLoader());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Map<String, CasterStats> data = new HashMap<>();
        for (Map.Entry<ResourceLocation, CasterStats> e : CasterRegistry.getAll().entrySet()) {
            data.put(e.getKey().toString(), e.getValue());
        }
        if (data.isEmpty()) return;
        if (event.getPlayer() != null) {
            SyncCasterStatsPacket.sendToPlayer(event.getPlayer(), data);
        } else {
            SyncCasterStatsPacket.sendToAll(data);
        }
    }
}
