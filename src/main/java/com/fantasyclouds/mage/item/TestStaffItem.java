package com.fantasyclouds.mage.item;

import net.minecraft.world.item.Item;

/**
 * 测试法杖：普通物品，右键施法由 {@code SpellShootHandler} 按数据包 caster_stats 配置驱动。
 * 数值全部来自 JSON，这里不需要任何逻辑。
 *
 * <p>2026-10-22 多根测试法杖：同一 {@link TestStaffItem} 注册多个名字（test_staff / test_staff_pierce /
 * test_staff_poison / test_staff_ground / test_staff_effects / test_staff_type），各配独立 caster_stats JSON，
 * 分别测三正交维度（pierce=飞行 / area=范围 / damageInterval=时间）与粒子五约定（spread/sphere/cube/disc/square）。
 */
public class TestStaffItem extends Item {

    public TestStaffItem() {
        super(new Item.Properties().stacksTo(1));
    }
}
