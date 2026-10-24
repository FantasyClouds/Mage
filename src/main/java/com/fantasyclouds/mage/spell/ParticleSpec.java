package com.fantasyclouds.mage.spell;

/**
 * 单个特效粒子配置：注册名 + 每次发射数 + 分布模式 + 范围。
 * 由 VisualStep 叠加进 SpellProjectile.particles（可多种粒子共存）。
 *
 * <p><b>分布模式（2026-10-22 定案·五种约定）</b>——mode 决定形状与投影方式，radius 为范围：
 * <ul>
 *   <li>{@code spread}（缺省）= <b>中心扩散</b>：弹当前位置小抖动散布（原有行为，拖尾火花，速度 0.02 动态飞散）。</li>
 *   <li>{@code sphere} = <b>立体球形·随弹</b>（静态）：粒子均匀填满弹周围 {@code radius} 格球体，速度 0 悬浮。</li>
 *   <li>{@code cube} = <b>立体正方形·随弹</b>（静态）：粒子均匀填满半边长 {@code radius} 的立方体，速度 0 悬浮。</li>
 *   <li>{@code disc} = <b>扁平圆形·贴地</b>（静态）：半径 {@code radius} 的极薄水平圆盘，粒子铺在<b>弹正下方完整方块顶面</b>（贴地毒雾/法阵）。</li>
 *   <li>{@code square} = <b>扁平正方形·贴地</b>（静态）：半边长 {@code radius} 的极薄水平方块，粒子铺在<b>弹正下方完整方块顶面</b>。</li>
 *   <li>{@code beam} = <b>线状·沿预演射线</b>：粒子沿弹缓存的整条 beam 路径铺线（需同时配 {@code beam} 原语；
 *       弹本体冻结在施法点，故 beam 必须用这个 mode，其他 mode 只会原地凝聚）。</li>
 * </ul>
 *
 * <p><b>贴地投影铁律（2026-10-22 用户定案）</b>：扁平形状（disc/square）<b>不跟弹保持相对位置</b>——
 * 弹射物在半空时，扁平粒子若在弹 y 处就会出现「半空扁平」的违和视觉。正确做法：粒子沿弹做
 * <b>向下投影</b>，落在弹正下方最近的<b>完整方块顶面</b>（GroundY）；找不到（虚空/极高）回落弹当前 y。
 * 立体形状（sphere/cube）才跟随弹射物。旧约定名 {@code cloud} 视为 {@code sphere}（兼容）。
 */
public record ParticleSpec(String id, int count, String mode, double radius) {

    public ParticleSpec {
        count = Math.max(1, count);
        if ("cloud".equals(mode)) mode = "sphere"; // 旧约定名兼容
        if (!isValidMode(mode)) mode = "spread";
        if (radius <= 0) radius = 1.0;
    }

    /** 兼容旧签名：缺省 spread 点扩散、范围 1。 */
    public ParticleSpec(String id, int count) {
        this(id, count, "spread", 1.0);
    }

    private static boolean isValidMode(String m) {
        return "spread".equals(m) || "sphere".equals(m) || "cube".equals(m)
                || "disc".equals(m) || "square".equals(m) || "beam".equals(m);
    }

    /** 是否贴地扁平形状（disc/square）——粒子铺在弹正下方完整方块顶面。 */
    public boolean isFlatGround() {
        return "disc".equals(mode) || "square".equals(mode);
    }

    /** 是否静态范围形状（sphere/cube/disc/square；spread=false）。 */
    public boolean isStaticRange() {
        return "sphere".equals(mode) || "cube".equals(mode) || isFlatGround();
    }
}
