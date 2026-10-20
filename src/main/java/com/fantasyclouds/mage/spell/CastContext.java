package com.fantasyclouds.mage.spell;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次施法的运行时上下文：把 {@code sequence} 各步骤串起来的共享状态。
 *
 * <p>持有施法者 / 世界 / 手持物品，以及「本次施法已收集的待发射弹」列表。
 * projectile 步骤往里推弹，split 等修饰符步骤读改写它，全部执行完由 launchAll 一次发射。
 */
public class CastContext {

    public final Level level;
    public final Player player;
    public final ItemStack held;
    public final RandomSource random;

    private final List<SpellProjectile> pending = new ArrayList<>();

    public CastContext(Level level, Player player, ItemStack held) {
        this.level = level;
        this.player = player;
        this.held = held;
        this.random = player.getRandom();
    }

    /** projectile 步骤：推入一颗待发射弹。 */
    public void addProjectile(SpellProjectile projectile) {
        pending.add(projectile);
    }

    /** 已收集的待发射弹（修饰符步骤读改写；不要从这里直接发射）。 */
    public List<SpellProjectile> pending() {
        return pending;
    }

    /** pipeline 收尾：把所有待发射弹生成实体。 */
    public void launchAll() {
        for (SpellProjectile projectile : pending) {
            projectile.launch(this);
        }
        pending.clear();
    }
}