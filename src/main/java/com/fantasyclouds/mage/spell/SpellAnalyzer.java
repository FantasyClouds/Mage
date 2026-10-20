package com.fantasyclouds.mage.spell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态推演 sequence → 伤害统计组（服务端加载时算，随网络包同步给客户端）。
 *
 * <p>只数「伤害与数量」两个维度，供 HUD 显示「NxM」（单体伤害x数量）：
 * - projectile 步骤：新增一组（damage, 1）；
 * - split 步骤：把已收集组各乘 count（数量×count，伤害不变）；
 * - 未知/未来步骤：不改变伤害与数量，忽略。
 *
 * <p>顺序敏感（与 {@link SpellPipeline} 执行语义一致）：split 只作用于它之前的弹。
 * sequence 为空（理论上不会，loader 保证至少一步）→ 空组，由 HUD 回落顶层 ballDamage。
 */
public final class SpellAnalyzer {

    private SpellAnalyzer() {}

    /** 从 sequence 推演出伤害组列表。分组不合并（每种弹一行，符合「8x3 · 5x1」展示）。 */
    public static List<DamageGroup> analyze(List<SpellStep> sequence) {
        List<DamageGroup> groups = new ArrayList<>();
        if (sequence == null) return groups;
        for (SpellStep step : sequence) {
            if (step instanceof ProjectileStep p) {
                groups.add(new DamageGroup(p.damage(), 1));
            } else if (step instanceof SplitStep sp) {
                int count = sp.count();
                if (count <= 1) continue;
                for (int i = 0; i < groups.size(); i++) {
                    DamageGroup g = groups.get(i);
                    groups.set(i, g.withCount(g.count() * count));
                }
            }
            // 其余修饰符步骤：不改伤害与数量，忽略
        }
        return groups;
    }

    /** 合并同伤害的组（总数量累加），供需要「总发数/总伤」的场景。 */
    public static int totalCount(List<DamageGroup> groups) {
        int sum = 0;
        for (DamageGroup g : groups) sum += g.count();
        return sum;
    }

    /** 合并同伤害：伤害相同则数量相加——用于去重展示。 */
    public static List<DamageGroup> mergeByDamage(List<DamageGroup> groups) {
        Map<Double, Integer> map = new LinkedHashMap<>();
        for (DamageGroup g : groups) {
            double key = Math.round(g.perDamage() * 100.0) / 100.0; // 避免浮点键分裂
            map.merge(key, g.count(), Integer::sum);
        }
        List<DamageGroup> merged = new ArrayList<>();
        map.forEach((dmg, count) -> merged.add(new DamageGroup(dmg, count)));
        return merged;
    }
}