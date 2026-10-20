# Mage —— 零依赖数据驱动施法框架（从《遗尘往事》抽取的可独立发布模组）

## 定位（先读这句）
Mage 是从遗尘往事整合包的 SOFL 模组里**抽取出来的通用施法内核**：modid `mage`，只依赖 minecraft+forge（**零第三方编译依赖**，可单独发布给任意整合包）。它是宿主（整合包）的**消费对象**——Mage 不复用宿主、宿主经数据包 + kubejs + story 模组来消费 Mage。施法逻辑由 JSON 驱动，不写死在代码里。

## 线头 · 去哪看项目细节
- **源码**：`src/main/java/com/fantasyclouds/mage/`——按职责分包：
  - `data/` 数据契约（CasterStats record / CasterRegistry / CasterStatsLoader 读 `caster_stats/*.json`）
  - `cost/` 消耗（SpellCostManager：`cost:{attribute,amount}` 配"耗哪个属性"，当前值扣减）
  - `cast/` 施放 + 伤害（SpellShootHandler / SpellDamageHelper）
  - `entity/` `item/` `init/` 内置弹 spell_bolt / 糖果·魔力·药水 / 注册（效果·附魔·属性·实体）
  - `client/` `network/` HUD 数值面板 + 同步包
  - `mixin/` 通用弹射物伤害接管（ProjectileSpellDamageMixin）
- **整合包消费侧**（caster_stats JSON 契约、掉落/酿造接线）：`story-of-forgotten-land-openloader-data`（caster_stats 章节）+ `story-of-forgotten-land-startup-scripts`（potionRecipe → mage 物品）
- **story 模组侧**（特殊法杖 mixin 委托 Mage 接口）：`mcreator-mixin-procedure` §3H
- **进度/状态**：`story-of-forgotten-land-progress` §1.5（迁移群组·待验证项）
- **跨会话记忆**：agent FACT「法术/Caster 体系已迁 SOFL → 独立 Mage 模组」
- 资源包/数据包/整合包路径均见上述三个整合包技能，别在此重复登记。

## 坑（非代码可推断的）
- **L2 法伤双倍（已修·勿复发）**：`l2damagetracker:magic_damage` 由 L2DamageTracker **原生施加**到魔法伤害上，Mage 伤害结算**绝不手动乘**该属性（曾乘两次→任意法伤增幅变 2 倍）；HUD 面板只显示预估。
- **消耗**：`cost.attribute` 配"耗哪个属性"，按**当前值**扣减、够才放行；`brutality:max_mana` = 反射桥接 `ManaHelper` 吃**当前蓝**（非扣 max 上限）。
- **构建**：本环境 gradle daemon 会被杀 → 临时注释 `gradle.properties` 的 `org.gradle.jvmargs=-Xmx3G` 可进程内编译；**用户已令"不要再编译了"**——改源码提交即可，jar 由用户自己编。
- **外部弹**：projectileType 空 = 内置弹 `mage:spell_bolt`；填外部实体 id = 用外部弹（adventuresmod:spell 等 13 处），命中统一走 ProjectileSpellDamageMixin 接管。

## 红线 / 禁止
1. **零依赖铁律**：编译依赖只许 minecraft + forge；引用任何第三方 mod 私包 = 违规（特殊 mod 需反射 `Class.forName` 桥接）。
