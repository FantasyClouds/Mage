package com.fantasyclouds.mage.client.hud;

import com.fantasyclouds.mage.client.CasterStatsClientCache;
import com.fantasyclouds.mage.data.CasterStats;
import com.fantasyclouds.mage.init.MageEffects;
import com.fantasyclouds.mage.init.MageEnchantments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.registries.ForgeRegistries;

import java.text.DecimalFormat;

/**
 * 法杖数值 HUD：复刻 l2archery BowInfoOverlay 观感（屏幕右侧半透明面板 + 彩色行），零第三方依赖。
 * 手持 caster_stats 配置过的物品时显示：伤害/类型/耗/速/冷却。
 */
public class CasterHudOverlay {

    private static final DecimalFormat FORMAT = new DecimalFormat("#.##");

    public static final IGuiOverlay RENDER = (ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) -> {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        String itemId = ForgeRegistries.ITEMS.getKey(held.getItem()).toString();
        CasterStats s = CasterStatsClientCache.get(itemId);
        if (s == null) return;

        double dmg = s.ballDamage() + held.getEnchantmentLevel(MageEnchantments.FORCEFUL.get());
        // 法力增幅药水加成（迁移自 SOFL HUD 口径）
        if (player.hasEffect(MageEffects.MAGIC_BOOST.get())) {
            dmg += player.getEffect(MageEffects.MAGIC_BOOST.get()).getAmplifier() + 1;
        }
        // 莱特兰法伤属性联动：伤害 × l2damagetracker:magic_damage（零依赖按注册名查）
        Attribute magicAttr = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation("l2damagetracker", "magic_damage"));
        if (magicAttr != null && player.getAttribute(magicAttr) != null) {
            dmg *= player.getAttributeValue(magicAttr);
        }
        int cdTicks = held.getEnchantmentLevel(MageEnchantments.SPELL_POTENCY.get()) > 0
                ? s.fastCooldown() : s.slowCooldown();

        String[] lines = {
                "§a" + Component.translatable("gui.caster_stats.damage", FORMAT.format(dmg)).getString(),
                "§b" + Component.translatable("gui.caster_stats.type", typeName(s.ballType())).getString(),
                "§9" + Component.translatable("gui.caster_stats.mana", FORMAT.format(s.costAmount())).getString(),
                "§6" + Component.translatable("gui.caster_stats.speed", FORMAT.format(s.projectileSpeed())).getString(),
                "§c" + Component.translatable("gui.caster_stats.cooldown", FORMAT.format(cdTicks / 20.0)).getString()
        };

        Font font = mc.font;
        int boxWidth = 0;
        for (String line : lines) boxWidth = Math.max(boxWidth, font.width(line));
        // 法杖数值栏显示在屏幕左侧（用户定案）
        int x = 12;
        int y = height / 2 - lines.length * 9 / 2;

        graphics.fill(x - 3, y - 3, x + boxWidth + 3, y + lines.length * 9 + 3, 0x80000000);
        for (int i = 0; i < lines.length; i++) {
            graphics.drawString(font, lines[i], x, y + i * 9, 0xFFFFFF, true);
        }
    };

    private static String typeName(int type) {
        return switch (type) {
            case 0 -> "Magic";
            case 1 -> "Fire";
            case 2 -> "Frost";
            case 3 -> "Lightning";
            case 4 -> "Void";
            case 5 -> "Arcane";
            default -> "Unknown";
        };
    }

    private CasterHudOverlay() {}
}
