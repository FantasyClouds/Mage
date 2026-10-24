package com.fantasyclouds.mage.mixin;

import com.fantasyclouds.mage.cast.SpellDamageHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用法弹伤害接管（「通用模块」——不逐类型 mixin）。
 *
 * <p>任何携带 Mage 施法 NBT（{@code spell_damage}）的弹射物（含外部模组法弹，
 * 如 adventuresmod:spell / terramity 火球 / born_in_chaos 法弹等）命中实体时，
 * 改走 {@code SpellDamageHelper} 统一结算（读 NBT {@code spell_damage} + {@code spell_type} 伤害类型字符串），
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

        // 方块命中：完全不干预（不设幂等 flag、不 cancel）——让 SpellBolt.onHitBlock 的
        // bounce 反弹每次都能自由执行。若在此设 flag，第二次撞墙就会被幂等分支 cancel 掉
        // onHitBlock，弹直接穿墙/结束（2026-10-22 修复：bounce「弹一次就结束」真根因）。
        if (!(result instanceof EntityHitResult entityHit)) return;

        // 内置弹 SpellBolt 全自管：实体命中由 onHitEntity 自己结算（damage+area+burst+discard），
        // 方块命中由 onHitBlock 自己反弹。mixin 只接管<b>外部弹射物</b>（adventuresmod 等）。
        if (self instanceof com.fantasyclouds.mage.entity.SpellBolt) return;

        // 穿透弹不走通用接管：SpellBolt 自身 tick AABB 扫描结算（每实体一次），
        // 若在此 discard/cancel 会杀死穿透弹。识别 spell_pierce NBT 放行。
        if (tag.getInt("spell_pierce") > 0) return;

        // 幂等：同一外部弹可能被 onHit / onHitEntity 双路径或同 tick 二次命中结算 → 只结算一次
        if (tag.getBoolean("mage$spell_handled")) {
            ci.cancel(); // 已结算：抑制原生二次处理，不再重复伤害
            return;
        }
        tag.putBoolean("mage$spell_handled", true);

        Entity owner = self.getOwner();
        Entity source = owner != null ? owner : self;
        Entity target = entityHit.getEntity();
        SpellDamageHelper.applySpellDamage(self.level(), target, source, self);
        // 爆裂附魔（mage:burst）：与内置弹一致，命中点燃目标
        if (tag.getBoolean("spell_enchant_burst")) {
            target.setSecondsOnFire(10);
        }
        // applyEffect 原语：外部弹同样对命中目标施加药水效果列表
        applyEffects(tag, target);
        // 范围爆裂（AreaStep）：外部弹同样支持——命中点半径内范围伤害
        if (tag.getDouble("spell_area") > 0) {
            Vec3 at = target.position();
            SpellDamageHelper.applyAreaDamage(self.level(), at, source, tag);
        }
        self.discard();
        ci.cancel();
    }

    /** 读 {@code spell_apply_effects} 复合列表，逐个施加到目标（异常单项跳过）。 */
    private static void applyEffects(CompoundTag tag, Entity target) {
        if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) return;
        net.minecraft.nbt.ListTag list = tag.getList("spell_apply_effects", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (list.isEmpty()) return;
        for (net.minecraft.nbt.Tag raw : list) {
            if (!(raw instanceof CompoundTag pt)) continue;
            try {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(pt.getString("effect"));
                net.minecraft.world.effect.MobEffect effect =
                        net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.getValue(id);
                if (effect != null) {
                    living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                            effect, pt.getInt("duration"), pt.getInt("amplifier")));
                }
            } catch (Throwable ignored) {
                // 单个效果异常：跳过，不影响伤害
            }
        }
    }
}
