package com.fantasyclouds.mage.spell;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 分裂修饰符：把每一颗待发射弹复制 {@code count} 份，按 {@code spread} 扇形均匀偏角分布。
 *
 * <p>JSON 参数：
 * <ul>
 *   <li>{@code count} 每颗弹分裂出的总数（总弹数 = 原弹数 × count）</li>
 *   <li>{@code spread} 扇形总角度（度，跨全部子弹对称分布；缺省 30）</li>
 * </ul>
 *
 * <p>无待发射弹时（顺序把 split 放在最前）静默不动作——修饰符只对已存在 payload 生效。
 */
public class SplitStep implements SpellStep {

    private final int count;
    private final double spread;

    public int count() { return count; }
    public double spread() { return spread; }

    public SplitStep(JsonObject json) {
        this.count = json.has("count") ? json.get("count").getAsInt() : 2;
        this.spread = json.has("spread") ? json.get("spread").getAsDouble() : 30.0;
        if (count < 1) throw new IllegalArgumentException("split count must be >= 1");
    }

    @Override
    public void apply(CastContext ctx) {
        List<SpellProjectile> pending = ctx.pending();
        if (pending.isEmpty() || count <= 1) return;
        List<SpellProjectile> result = new ArrayList<>(pending.size() * count);
        double spreadRad = Math.toRadians(spread);
        for (SpellProjectile p : pending) {
            for (int i = 0; i < count; i++) {
                SpellProjectile copy = p.copy();
                // 均匀分布：p0 在最左，最后一份在最右
                double t = count == 1 ? 0.0 : (double) i / (count - 1) - 0.5;
                copy.angleOffset = p.angleOffset + t * spreadRad;
                result.add(copy);
            }
        }
        pending.clear();
        pending.addAll(result);
    }
}