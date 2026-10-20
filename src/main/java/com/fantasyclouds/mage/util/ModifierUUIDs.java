package com.fantasyclouds.mage.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 法师模组固定 UUID 注册表。
 *
 * <p>任何要在游戏里增删的 AttributeModifier 必须用<b>写死的 UUID</b>（不要每次 new 随机值），
 * 否则同一来源会反复叠加、且移除时按 UUID 找不到目标。给新来源在下方加一个常量即可，
 * 或用 {@link #from(String)} 按种子字符串生成稳定值。
 *
 * <p>使用例（配合 {@link AttributeModifierHelper}）：
 * <pre>
 * AttributeModifierHelper.apply(player, BrutalityAttributes.MAX_MANA.get(),
 *         ModifierUUIDs.MAX_MANA_BONUS, "mage.max_mana_bonus", 20.0,
 *         AttributeModifier.Operation.ADDITION);
 * ...
 * AttributeModifierHelper.remove(player, BrutalityAttributes.MAX_MANA.get(),
 *         ModifierUUIDs.MAX_MANA_BONUS);
 * </pre>
 */
public final class ModifierUUIDs {

    private ModifierUUIDs() {
    }

    // ---- 魔法属性修正（按需增删，命名：属性_用途） ----
    // 例：魔力上限临时加成
    // public static final UUID MAX_MANA_BONUS = from("max_mana_bonus");
    // 例：法伤加成
    // public static final UUID MAGIC_DAMAGE_BONUS = from("magic_damage_bonus");
    // 例：耗蓝减免
    // public static final UUID MANA_COST_REDUCTION = from("mana_cost_reduction");

    /**
     * 按种子字符串生成稳定 UUID（同名同值，跨会话一致，可用于局部变量的临时来源）。
     * 注意：正式来源请在下方登记常量，保证全局唯一、一处可查。
     */
    public static UUID from(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
