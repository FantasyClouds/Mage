package com.fantasyclouds.mage.spell;

/**
 * 单个拖尾粒子配置：注册名 + 每次发射数。
 * 由 VisualStep 叠加进 SpellProjectile.particles（可多种粒子共存）。
 */
public record ParticleSpec(String id, int count) {
    public ParticleSpec {
        count = Math.max(1, count);
    }
}