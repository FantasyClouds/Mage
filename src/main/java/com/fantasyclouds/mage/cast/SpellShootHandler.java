package com.fantasyclouds.mage.cast;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.cost.SpellCostManager;
import com.fantasyclouds.mage.data.CasterRegistry;
import com.fantasyclouds.mage.data.CasterStats;
import com.fantasyclouds.mage.entity.SpellBolt;
import com.fantasyclouds.mage.init.MageEnchantments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 右键施法入口（还原原 MCR 模组 SpellShootHandlerProcedure 的连发语义）。
 *
 * <p>行为：按住右键 = <b>连发</b>，施法频率由物品冷却限速；每次发射扣消耗资源，资源不足即停。
 * <b>保底冷却</b>：配置 cooldown ≤ 0 时强制用 0.5s 下限，防无冷却配置机枪式扣蓝。
 *
 * <p>弹射物：<b>按配置 {@code projectileType} 生成外部法弹实体</b>（整合包内容视觉保留），
 * 携带 spell_damage / spell_type / spell_enchant_* NBT。命中伤害由
 * {@code ProjectileSpellDamageMixin}（通用 Projectile 接管，零外部依赖）改走
 * {@code SpellDamageHelper} 统一结算（ballDamage + ballType 伤害类型）。
 */
@Mod.EventBusSubscriber(modid = Mage.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
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

        // 资源检查/消耗；不足即停（连发到蓝空自动断）
        if (!SpellCostManager.tryConsume(player, stats.costAttribute(), stats.costAmount())) return;

        // 施法成功 → 主手挥动（双参 swing，本人可见）
        player.swing(InteractionHand.MAIN_HAND, true);

        // 清旧冷却并重设（每冷却周期发射一发；保底下限防机枪）
        player.getCooldowns().removeCooldown(item.getItem());
        int cd = item.getEnchantmentLevel(MageEnchantments.SPELL_POTENCY.get()) > 0
                ? stats.fastCooldown() : stats.slowCooldown();
        if (cd <= 0) cd = MIN_COOLDOWN_TICKS;
        player.getCooldowns().addCooldown(item.getItem(), cd);

        // 按配置生成弹射物（projectileType 空 → 内置 mage:spell_bolt）
        summonSpellEntity(level, player, item, stats);
    }

    private static void summonSpellEntity(Level level, Player player, ItemStack held, CasterStats stats) {
        EntityType<?> type = stats.projectileType().isEmpty()
                ? com.fantasyclouds.mage.init.MageEntities.SPELL_BOLT.get()
                : ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(stats.projectileType()));
        if (type == null) return;
        Entity entity = type.create(level);
        if (entity == null) return;

        if (entity instanceof Projectile projectile) {
            projectile.setOwner(player);
        }
        entity.setNoGravity(true);
        if (entity instanceof Mob mob) mob.setNoAi(true);

        entity.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
        Vec3 look = player.getLookAngle();
        double speed = stats.projectileSpeed();

        if (entity instanceof SpellBolt bolt) {
            bolt.launch(look.scale(speed));
            bolt.setSpellType(stats.ballType());
        } else {
            entity.setDeltaMovement(look.scale(speed));
        }

        // NBT 存储法术参数（命中由 ProjectileSpellDamageMixin → SpellDamageHelper 统一结算）
        CompoundTag tag = entity.getPersistentData();
        tag.putDouble("spell_damage", stats.ballDamage());
        tag.putInt("spell_type", stats.ballType());
        tag.putLong("spell_death_time", level.getGameTime() + stats.lifetime());
        int forceful = held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        if (forceful > 0) tag.putDouble("spell_enchant_bonus", forceful);
        if (held.getEnchantmentLevel(MageEnchantments.BURST.get()) > 0) tag.putBoolean("spell_enchant_burst", true);

        level.addFreshEntity(entity);
    }
}
