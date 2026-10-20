package com.fantasyclouds.mage.item;

import com.fantasyclouds.mage.Mage;
import com.fantasyclouds.mage.cost.SpellCostManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;

/**
 * 魔法糖果（迁移自 SOFL）：吃掉回复 40 点 Brutality 当前蓝（反射桥，零编译依赖）。
 */
public class MagicCandyItem extends Item {

    private static final double MANA_AMOUNT = 40.0;
    /** 整合包资源 = Brutality 蓝（反射桥按命名空间路由，扣/加的是当前蓝条）。 */
    private static final ResourceLocation MANA_ATTRIBUTE = new ResourceLocation("brutality", "max_mana");

    public MagicCandyItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.COMMON)
                .food(new FoodProperties.Builder().nutrition(0).saturationMod(0f).alwaysEat().build()));
    }

    @Override
    public int getUseDuration(ItemStack itemstack) {
        return 10;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack itemstack, Level world, LivingEntity entity) {
        ItemStack retval = super.finishUsingItem(itemstack, world, entity);
        if (entity instanceof net.minecraft.world.entity.player.Player player && !world.isClientSide) {
            SpellCostManager.restore(player, MANA_ATTRIBUTE, MANA_AMOUNT);
        }
        return retval;
    }
}
