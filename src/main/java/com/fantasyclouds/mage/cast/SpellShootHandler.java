package com.fantasyclouds.mage.cast;

import com.fantasyclouds.mage.cost.SpellCostManager;
import com.fantasyclouds.mage.data.CasterRegistry;
import com.fantasyclouds.mage.data.CasterStats;
import com.fantasyclouds.mage.init.MageEnchantments;
import com.fantasyclouds.mage.spell.CastContext;
import com.fantasyclouds.mage.spell.SpellPipeline;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 右键施法入口（还原原 MCR 模组 SpellShootHandlerProcedure 的连发语义）。
 *
 * <p>行为：按住右键 = <b>连发</b>，施法频率由物品冷却限速；每次发射扣消耗资源，资源不足即停。
 * <b>保底冷却</b>：配置 cooldown ≤ 0 时强制用 0.5s 下限，防无冷却配置机枪式扣蓝。
 *
 * <p>扣蓝顺序（2026-10-20 修）：<b>先 canAfford 检查、施法成功后才 consume</b>——
 * 弹未真正生成则不扣蓝、不挥动、不进入冷却（可立即重试）。杜绝"没施法却扣蓝"。
 *
 * <p>发射：资源/冷却通过后走 {@link SpellPipeline}（Noita 式有序步骤），
 * 弹由 sequence 里的步骤装配（payload + 修饰符），具体生成实体逻辑在
 * {@code SpellProjectile.launch}（外部弹视觉保留、带 Mage NBT、命中走通用接管）。
 */
@Mod.EventBusSubscriber(modid = com.fantasyclouds.mage.Mage.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SpellShootHandler {

    /** 配置冷却为 0 时的保底下限（tick，0.5s），防无冷却配置每 tick 扣蓝。 */
    private static final int MIN_COOLDOWN_TICKS = 10;

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        ItemStack item = player.getMainHandItem();

        CasterStats stats = CasterRegistry.get(item);
        if (stats == null) return;

        if (stats.isOverwrite()) event.setCanceled(true);
        if (level.isClientSide()) return;

        // 冷却门控（保险；正常时 Forge 已不触发冷却中的事件）
        if (player.getCooldowns().isOnCooldown(item.getItem())) return;

        // 资源足够才施法（先查不扣；真正施法成功后才 consume——"没真正施法不扣蓝"）。
        // 蓝不足时自动吃背包魔法糖果补蓝再判（恢复 SOFL RemoveManaProcedure 兜底行为）。
        if (!SpellCostManager.affordWithCandy(player, stats.costAttribute(), stats.costAmount())) return;

        // 走法术流水线：按 sequence 装配弹并统一发射（返回是否有弹真正生成）
        boolean cast = SpellPipeline.cast(new CastContext(level, player, item), stats);
        if (!cast) return; // 弹没生成：不扣蓝、不挥动、不触发冷却（可立即重试）

        // 施法成功 → 扣消耗 + 主手挥动（双参 swing，本人可见）
        SpellCostManager.consume(player, stats.costAttribute(), stats.costAmount());
        player.swing(InteractionHand.MAIN_HAND, true);

        // 清旧冷却并重设（每冷却周期发射一发；保底下限防机枪）
        player.getCooldowns().removeCooldown(item.getItem());
        int cd = item.getEnchantmentLevel(MageEnchantments.SPELL_POTENCY.get()) > 0
                ? stats.fastCooldown() : stats.slowCooldown();
        if (cd <= 0) cd = MIN_COOLDOWN_TICKS;
        player.getCooldowns().addCooldown(item.getItem(), cd);
    }
}