package com.fantasyclouds.mage.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * 魔力材料（迁移自 SOFL 的 ManaItemItem，原封不动）：法力增幅药水的酿造基底。
 */
public class ManaItem extends Item {

    public ManaItem() {
        super(new Item.Properties().stacksTo(64).rarity(Rarity.COMMON));
    }
}
