package com.fantasyclouds.mage.mixin;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用法弹伤害接管（「通用模块」——不逐类型 mixin）。
 *
 * <p>任何携带 Mage 施法 NBT（{@code spell_damage}）的弹射物（含外部模组法弹，
 * 如 adventuresmod:spell / terramity 火球 / born_in_chaos 法弹等）命中实体时，
 * 改走 {@code SpellDamageHelper} 统一结算（读 NBT ballDamage + ballType 伤害类型），
 * 并 {@code discard + cancel} 掉原生命中处理（原生伤害不再生效）。
 *
 * <p>为什么注入 {@code Projectile.onHit(EntityHitResult)}：这是所有弹射物实体命中的
 * 统一汇流点（子类 onHitEntity 由它分发），一处覆盖全部类型，无需逐个 mod 写 mixin。
 * 没有 {@code spell_damage} NBT 的弹射物（原生弹、story 云法等）不受影响。
 * 原版类，保持 remap=true。
 */
@Mixin(Projectile.class)
public abstract class ProjectileSpellDamageMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void mage$takeOverSpellDamage(HitResult result, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (self.level().isClientSide) return;
        CompoundTag tag = self.getPersistentData();
        if (!tag.contains("spell_damage")) return;

        // 穿透弹不走通用接管：SpellBolt 自身 tick AABB 扫描结算（每实体一次），
        // 若在此 discard/cancel 会杀死穿透弹。识别 spell_pierce NBT 放行。
        if (tag.getInt("spell_pierce") > 0) return;

        // 幂等：同一弹可能被 onHit / onHitEntity 双路径或同 tick 二次命中结算 → 只结算一次
        if (tag.getBoolean("mage$spell_handled")) {
            ci.cancel(); // 已结算：抑制原生二次处理，不再重复伤害
            return;
        }
        tag.putBoolean("mage$spell_handled", true);

        if (!(result instanceof EntityHitResult entityHit)) return; // 只接管命中实体

        Entity owner = self.getOwner();
        SpellDamageHelper.applySpellDamage(self.level(), entityHit.getEntity(), owner != null ? owner : self, self);
        self.discard();
        ci.cancel();
    }
}
