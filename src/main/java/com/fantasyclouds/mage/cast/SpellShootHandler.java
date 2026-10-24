package com.fantasyclouds.mage.cast;

import com.fantasyclouds.mage.cost.SpellCostManager;
import com.fantasyclouds.mage.data.CasterRegistry;
import com.fantasyclouds.mage.data.CasterStats;
import com.fantasyclouds.mage.init.MageEnchantments;
import com.fantasyclouds.mage.spell.CastContext;
import com.fantasyclouds.mage.spell.SpellPipeline;
import com.fantasyclouds.mage.spell.SpellProjectile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 右键施法入口（还原原 MCR 模组 SpellShootHandlerProcedure 的连发语义）。
 *
 * <p>行为：按住右键 = <b>连发</b>，施法频率由物品冷却限速；<b>每次发射只扣一次消耗</b>，资源不足即停。
 * <b>施法/扣蓝严格限定在服务端 + 主手</b>（2026-10-24 修：单人共享静态注册表导致客户端也施法 → 一次点击多放多扣）。
 * <b>保底冷却</b>：配置 cooldown ≤ 0 时强制用 0.5s 下限，防无冷却配置机枪式扣蓝。
 *
 * <p>扣蓝顺序（2026-10-20 修）：<b>先 canAfford 检查、施法成功后才 consume</b>——
 * 弹未真正生成则不扣蓝、不挥动、不进入冷却（可立即重试）。杜绝"没施法却扣蓝"。
 *
 * <p>两种模式（由 {@code isOverwrite} 决定）：
 * <ul>
 *   <li><b>true（接管）</b>：取消原物品右键行为，Mage 用 {@link SpellPipeline#cast} 发内置
 *       {@code mage:spell_bolt}，外观走 visual 委托，运动/伤害全由 Mage 接管。</li>
 *   <li><b>false（兼容原版模组法术）</b>：<b>不取消</b>右键事件、<b>不发</b> Mage 弹——让原物品
 *       的 {@code use()} 自己发弹。若 sequence 配了 {@code projectile} 步骤，则把伤害语义序列化成
 *       NBT，由 {@link ExternalProjectileTagging} 在原版弹加入世界时打上去；命中由
 *       {@code ProjectileSpellDamageMixin} 改走 Mage 统一结算。没配 {@code projectile} 的
 *       （原版法术是加 buff 之类、不发射弹）就是纯原版行为，Mage 只负责消耗与冷却。</li>
 * </ul>
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

        boolean overwrite = stats.isOverwrite();
        // 接管模式：吞掉原物品右键行为（客户端也取消，避免本地先预测一次 use）。
        if (overwrite) event.setCanceled(true);

        // 只允许服务端 + 主手真正施法/扣蓝（2026-10-24 修"一次施法扣多次"）：
        // 单人存档里客户端与服务端同 JVM、静态 CasterRegistry 共享，客户端事件也会拿到法杖配置，
        // 若不加这两道闸，一次点击会客户端施法一次 + 服务端施法一次 → 多放一颗弹、多扣一次蓝。
        // 用 instanceof ServerPlayer 兜底（比 level.isClientSide 更硬，客户端玩家永远不是 ServerPlayer）。
        if (level.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer)) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        // 冷却门控：用 vanilla ItemCooldowns（与其他模组一致的标准做法）。
        // 服务端 + 主手两道闸已在上面挡掉客户端/副手；这里再挡冷却中的重复触发。
        if (player.getCooldowns().isOnCooldown(item.getItem())) return;
        int cd = item.getEnchantmentLevel(MageEnchantments.SPELL_POTENCY.get()) > 0
                ? stats.fastCooldown() : stats.slowCooldown();
        if (cd <= 0) cd = MIN_COOLDOWN_TICKS;

        // 资源足够才放行（先查不扣；真正施法成功后才 consume——"没真正施法不扣蓝"）。
        // 蓝不足时自动吃背包魔法糖果补蓝再判（恢复 SOFL RemoveManaProcedure 兜底行为）。
        // 兼容模式（不覆盖原版）蓝不够时也要拦：Mage 在这里充当资源层，否则原版法术会白嫖。
        if (!SpellCostManager.affordWithCandy(player, stats.costAttribute(), stats.costAmount())) {
            if (!overwrite) event.setCanceled(true);
            return;
        }

        if (overwrite) {
            // 接管：Mage 自己发内置弹；没生成则视为施法失败（不扣蓝/冷却）。
            boolean cast = SpellPipeline.cast(new CastContext(level, player, item), stats);
            if (!cast) return;
        } else {
            // 兼容：原版模组自己发弹。仅当 sequence 配了 projectile 步骤时，把伤害语义序列化成
            // NBT，等原版弹加入世界时由 ExternalProjectileTagging 打上去（命中走通用结算）。
            // 没配 projectile（原版法术不发射弹）→ 纯原版行为，Mage 只扣消耗与冷却。
            CastContext ctx = new CastContext(level, player, item);
            SpellPipeline.applySteps(ctx, stats);
            List<SpellProjectile> pending = ctx.pending();
            if (!pending.isEmpty()) {
                List<CompoundTag> tags = new ArrayList<>(pending.size());
                for (SpellProjectile projectile : pending) {
                    tags.add(projectile.buildExternalTag(item));
                }
                ExternalProjectileTagging.expect(player, tags, level.getGameTime());
                pending.clear(); // 不发射：清空待发弹
            }
        }

        // 施法成功 → 扣消耗 + 主手挥动（双参 swing，本人可见）
        SpellCostManager.consume(player, stats.costAttribute(), stats.costAmount());
        player.swing(InteractionHand.MAIN_HAND, true);

        // 设 vanilla 物品冷却（清旧再设；冷却门与物品冷却条都靠它）
        player.getCooldowns().removeCooldown(item.getItem());
        player.getCooldowns().addCooldown(item.getItem(), cd);
    }
}