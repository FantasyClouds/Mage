package com.fantasyclouds.mage.potion;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 法力增幅（迁移自 SOFL 的 MagicBoostMobEffect，原封不动）：
 * 施法伤害 +（amplifier+1），由 {@code SpellDamageHelper} 结算时读取。
 */
public class MagicBoostMobEffect extends MobEffect {

    public MagicBoostMobEffect() {
        super(MobEffectCategory.BENEFICIAL, -65281);
    }

    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return true;
    }
}
