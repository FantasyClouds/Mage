package com.fantasyclouds.mage.spell;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 法术步骤 JSON → {@link SpellStep} 的解析工厂。
 *
 * <p>步骤对象形如 {@code {"type":"projectile","damage":8,"damageType":"fire"}} /
 * {@code {"type":"split","count":3,"spread":45}}。type 决定步骤类，其余键为步骤参数。
 * 伤害类型是字符串（2026-10-22 定案），兼容旧数字键 {@code ballType}。
 * 未知 type / 坏参数：跳过该步并记日志，不让单个坏步骤拖垮整条链。
 */
public final class SpellSteps {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SpellSteps() {}

    /** 解析一整条 sequence 数组（CasterStatsLoader 直接喂 JSON）。null/空 → 空链。 */
    public static List<SpellStep> parse(JsonArray array) {
        return parse(array, StepDefaults.EMPTY);
    }

    /** 解析 sequence；字段缺省回落 StepDefaults（顶层旧字段兜底）。 */
    public static List<SpellStep> parse(JsonArray array, StepDefaults defs) {
        List<SpellStep> steps = new ArrayList<>();
        if (array == null) return steps;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                LOGGER.warn("[MageCaster] spell step not an object, skipped: {}", element);
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("type")) {
                LOGGER.warn("[MageCaster] spell step missing 'type', skipped: {}", obj);
                continue;
            }
            String type = obj.get("type").getAsString();
            SpellStep step = switch (type) {
                // projectile=唯一的 payload（定义弹：速度/伤害/类型/寿命）；antigravity=修饰符（无重力标记，无 payload 属性）
                case "projectile" -> new ProjectileStep(obj, defs, false);
                case "antigravity" -> new AntigravityStep(obj);
                case "split" -> new SplitStep(obj);
                case "pierce" -> new PierceStep(obj);
                case "visual" -> new VisualStep(obj);
                case "homing" -> new HomingStep(obj);
                case "area" -> new AreaStep(obj);
                case "ground" -> new GroundStep(obj);
                case "grantEffect" -> new GrantEffectStep(obj);
                case "applyEffect" -> new ApplyEffectStep(obj);
                case "explode" -> new ExplodeStep(obj);
                case "bounce" -> new BounceStep(obj);
                case "beam" -> new BeamStep(obj);
                default -> {
                    LOGGER.warn("[MageCaster] unknown spell step type '{}', skipped", type);
                    yield null;
                }
            };
            if (step != null) steps.add(step);
        }
        return steps;
    }

    /** 向后兼容：旧扁平字段（无 sequence）折叠成 projectile 步骤；顶层旧字段 {@code projectileType}
     *  （弹种类，2026-10-23 定案已废弃）非空时追加一个 {@code visual(entity=...)} 步骤——
     *  「用别的模组实体当弹体」自动转成「用它的模型当渲染代理」（外观委托）。 */
    public static List<SpellStep> defaultFlatSequence(String projectileType, double damage, String damageType,
                                                      double speed, int lifetime) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "projectile");
        if (damage > 0) obj.addProperty("damage", damage);
        if (damageType != null && !damageType.isEmpty()) obj.addProperty("damageType", damageType);
        if (speed > 0) obj.addProperty("speed", speed);
        if (lifetime > 0) obj.addProperty("lifetime", lifetime);
        List<SpellStep> steps = new ArrayList<>();
        steps.add(new ProjectileStep(obj));
        if (projectileType != null && !projectileType.isEmpty()) {
            JsonObject visual = new JsonObject();
            visual.addProperty("entity", projectileType);
            steps.add(new VisualStep(visual));
        }
        return steps;
    }
}