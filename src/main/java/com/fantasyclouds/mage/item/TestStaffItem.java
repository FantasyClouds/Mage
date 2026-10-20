package com.fantasyclouds.mage.item;

import net.minecraft.world.item.Item;

/**
 * 测试法杖：普通物品，右键施法由 {@code SpellShootHandler} 按数据包 caster_stats 配置驱动。
 * 数值全部来自 JSON，这里不需要任何逻辑。
 */
public class TestStaffItem extends Item {

    public TestStaffItem() {
        super(new Item.Properties().stacksTo(1));
    }
}
