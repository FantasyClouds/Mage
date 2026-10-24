# Mage —— 零依赖数据驱动施法框架（从《遗尘往事》抽取的可独立发布模组）

## 定位（先读这句）
Mage 是从遗尘往事整合包的 SOFL 模组里**抽取出来的通用施法内核**：modid `mage`，只依赖 minecraft+forge（**零第三方编译依赖**，可单独发布给任意整合包）。它是宿主（整合包）的**消费对象**——Mage 不复用宿主、宿主经数据包 + kubejs + story 模组来消费 Mage。施法逻辑由 JSON（`caster_stats/*.json`）驱动，不写死在代码里。

## 线头 · 去哪看项目细节
- **架构·为什么（首选）**：`docs/architecture.md`——业务效果→代码→技术 一条索引 + 设计决策（零依赖反射桥、当前值扣减、L2 不双乘、与 SOFL/L2 边界）。改契约/理解链路先读它。
- **源码**：`src/main/java/com/fantasyclouds/mage/`——按职责分包：
  - `data/` 数据契约（CasterStats record / CasterRegistry / CasterStatsLoader 读 `caster_stats/*.json`）
  - `spell/` 施法流水线（SpellPipeline / SpellSteps / 各原语 Step / SpellProjectile）——法术=有序步骤
  - `cost/` 消耗（SpellCostManager：`cost:{attribute,amount}`；无 attribute 默认吃 Brutality 当前蓝）
  - `cast/` 施放 + 伤害（SpellShootHandler / SpellDamageHelper / ExternalProjectileTagging）
  - `entity/` 内置弹 `mage:spell_bolt`（SpellBolt + 各 Behavior：beam/bounce/pierce/area/explode…）
  - `item/` `init/` 糖果·魔力·药水 / 注册（效果·附魔·属性·实体）
  - `client/` `network/` HUD 数值面板 + 同步包
  - `mixin/` 伤害接管：ProjectileSpellDamageMixin（Projectile 系）+ LivingEntitySpellDamageMixin（非 Projectile 自定义法术实体）
- **整合包消费侧**（caster_stats JSON 契约、掉落/酿造接线）：`story-of-forgotten-land-openloader-data`（caster_stats 章节）+ `story-of-forgotten-land-startup-scripts`（potionRecipe → mage 物品）
- **story 模组侧**（特殊法杖 mixin 委托 Mage 接口）：`mcreator-mixin-procedure` §3H
- **进度/状态**：`story-of-forgotten-land-progress` §1.5
- **跨会话记忆**：agent FACT「法术/Caster 体系已迁 SOFL → 独立 Mage 模组」
- 资源包/数据包/整合包路径均见上述三个整合包技能，别在此重复登记。

## 坑（非代码可推断的）
- **构建（Mage 本身）**：`gradle.properties` 的 `org.gradle.jvmargs=-Xmx3G` 会占 3G 被本环境杀进程 → 编译前临时注释、编译后还原；`./gradlew jar --offline` 秒编，产物 `build/libs/mage-1.0-SNAPSHOT.jar`，装进整合包 `versions/遗尘往事/mods/` 替换。**SOFL 工程别用 Gradle 编**（用户用 MCR 构建，Gradle 解析 47.2.0 会卡半天）。构建细节见 `BUILD.md`。
- **消耗**：`cost` 无 `attribute` = 默认池 `brutality:mana`（反射桥吃 Brutality **当前蓝**，非上限）；显式 `attribute` = 按该属性当前值扣减；旧写法 `brutality:max_mana` 兼容同一池。
- **伤害类型是字符串**（2026-10-22 定案）：`damageType` = "magic/fire/frost/lightning/void"；旧 int `ballType`(0-4) 仅向后兼容。**SOFL 已编译 jar 调用的 `applySpellDamageFromValues(...,int)` 与 `CasterStats.ballType()` 是兼容重载/方法——别删**（删了 SOFL 命中结算直接 NoSuchMethodError）。
- **L2 法伤倍率**：`l2damagetracker:magic_damage` 由 L2 **原生施加**，Mage **绝不手动乘**（曾双乘→2 倍）；面板只显示预估。当前"配置值×magic_damage"是包内缩放口径，用户认可（曾提议抵消、用户叫停）。
- **弹不命中弹**：`SpellBolt.canHitEntity` 排除所有 Projectile——分裂弹同点生成不会互相消耗 pierce（神话法杖 split=5 只剩 2 的根因）。
- **Mixin 兼容等级**：`mage.mixins.json` 必须 `compatibilityLevel: JAVA_17`（配 JAVA_8 会让 JDK17 编的 mixin 类被静默跳过，伤害接管全失效）。
- **爆炸防爆**：破坏方块用 `ExplosionInteraction.MOB`（而非 `BLOCK`）——`BLOCK` 无视 `mobGriefing`，防爆开了照样炸。
- **beam 碰撞优先级**：同时配 bounce+pierce 时方块**一律弹跳**，pierce 只对实体生效；纯 pierce 才穿墙。

## 红线 / 禁止
1. **零依赖铁律**：编译依赖只许 minecraft + forge；引用任何第三方 mod 私包 = 违规（特殊 mod 需反射 `Class.forName` 桥接）。
