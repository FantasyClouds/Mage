# Mage 架构·为什么（业务 → 代码 → 技术）

> 本文件回答："这个模组做出了什么效果、对应代码在哪、大致用什么技术，以及为什么这么设计"。
> 它是按需深文档——改契约/理解链路时读它，平时不占上下文。代码层面的事（类名、签名）以源码为准，这里只写代码推不出的。

## 一句话架构

**数据驱动施法内核**：整合包用 JSON（`caster_stats/*.json`）声明"哪把法杖放什么弹、耗什么资源、造成什么伤害类型"，Mage 提供运行时（加载/扣耗/施放/伤害/数值显示）。没有任何施法逻辑写死在代码里。

## 业务效果 → 代码 → 技术 索引

| 业务效果（玩家/包作者看到） | 对应代码 | 大致技术 |
|---|---|---|
| 法杖配置从数据包热加载、可 `/reload` | `data/CasterStatsLoader` + `data/CasterRegistry` + `data/CasterStats` | `SimpleJsonResourceReloadListener`（`AddReloadListenerEvent` 注册）；服务端权威、`OnDatapackSyncEvent` 推全量给客户端 |
| 消耗任意属性当资源池 | `cost/SpellCostManager` | JSON `cost:{attribute,amount}`；**纯属性扣减**=记账（`persistentData` `mage_cost` 标签）+ 固定 UUID 永久修饰符（幂等不叠加）+ 每 tick 回复；属性不足拒绝施放 |
| 消耗 Brutality **当前蓝条**（而非上限） | `cost/SpellCostManager#tryConsumeBrutalityMana` | **反射桥** `Class.forName("net.goo.brutality.util.magic.ManaHelper")` 调 `getMana`/`modifyManaValue`（零编译依赖；运行时无 Brutality 则放行不扣） |
| 右键施法、按住连发 | `cast/SpellShootHandler` | `PlayerInteractEvent.RightClickItem`；冷却门控（spell_potency 切 fast/slow）+ **0.5s 保底冷却**防机枪；每次发射扣资源，蓝空自断；发射走 `SpellPipeline`（2026-10-20 起） |
| **法术 = 有序步骤流水线**（Noita 式 payload+修饰符） | `spell/SpellPipeline` + `spell/SpellStep` + `spell/SpellSteps` | JSON `sequence:[{type,...},...]`；`SpellPipeline.cast` 依次 apply（projectile 推弹、split/homing 等改造 `CastContext.pending`），最后统一 `launchAll`；未知步骤跳过记日志不拖垮链 |
| 法术弹命中结算统一魔法伤害 | `cast/SpellDamageHelper` + `mixin/ProjectileSpellDamageMixin` | 弹射物带 `spell_damage/spell_type/spell_enchant_*` NBT；`Projectile.onHit` HEAD 注入通用接管（`mage$spell_handled` 幂等）→ `SpellDamageHelper` 按 ballType 映射 DamageType（0=INDIRECT_MAGIC/1=FIREBALL/2=FREEZE/3=LIGHTNING_BOLT/4=FELL_OUT_OF_WORLD/5=INDIRECT_MAGIC+减速再生）+ 叠加 `magic_boost` |
| 外部法弹视觉保留、内部接管伤害 | `spell/SpellProjectile#launch`（自 `SpellShootHandler#summonSpellEntity` 迁入，2026-10-20） | `projectileType` 空 → 内置弹 `entity/SpellBolt`（`mage:spell_bolt`）；填外部实体 id → 用外部弹（adventuresmod:spell 等），NBT 触发通用接管 |
| 附魔影响法术 | `init/MageEnchantments`（spell_potency 快冷却/forceful 加伤/burst 点燃） | 附魔等级读进 NBT（`spell_enchant_bonus`/`spell_enchant_burst`），结算时累加 |
| 右手数值面板 | `client/hud/CasterHudOverlay` + `client/CasterStatsClientCache` | Forge `IGuiOverlay`；显示 `(ballDamage+forceful+magic_boost) × magic_damage` **预估**（只显示不乘，见红线） |
| 消耗回复/魔法糖果 +40 | `cost/SpellCostManager#restore` + `item/MagicCandyItem` | 记账减回 / 反射桥加蓝 |
| 网络同步配置 | `network/SyncCasterStatsPacket` | Forge `SimpleChannel`（`Mage#addNetworkMessage`，协议版本 "1"） |
| 注册资源 | `Mage`（block/item/tab）+ `init/MageAttributes`（mage:mana RangedAttribute 挂 Player + Clone 持久化） | `DeferredRegister`；MOD bus 注册、FORGE bus 事件（CloneHandler） |

## 为什么（设计决策·代码推不出的部分）

### 1. 零依赖是定位，不是技术限制
Mage 是**从遗尘往事 SOFL 抽取的通用施法框架，可单独发布**。编译依赖只许 minecraft+forge。要消费其他模组的资源（如 Brutality 蓝）只能：
- **反射桥接**（`Class.forName` + `Method.invoke`，运行时探测，缺了放行不崩）——见 SpellCostManager 的 brutality 路径；
- **按注册名查**（`ForgeRegistries.ATTRIBUTES/ENTITY_TYPES.getValue(ResourceLocation)`，查不到返回 null 静默降级）——见 cost 属性查询、外部弹类型解析。

### 2. 为什么消耗用"当前值扣减 + 修饰符记账"而不是直接改属性
- 直接 `getAttribute().setBaseValue()` 会把玩家的永久属性改了，且无法区分"是我扣的"还是"玩家装备给的"；
- 记账（`persistentData` 累计 consumed）+ 固定 UUID 修饰符 = **幂等**（重复施法不叠加修饰符，改一处统一更新）；
- 回复 = 每 tick 从记账减 `regenPerSecond/20`，减到 ≤0 摘修饰符。属性恢复轨迹与消耗轨迹同一来源，不会漂移。

### 3. 为什么 brutality 反射而非 `brutality:max_mana` 上限属性
用户裁决："谁家好人扣蓝扣蓝条上限的"。`max_mana` 是容量，扣它等于永久砍玩家上限；`ManaHelper` 的 capability 才是当前值。所以 JSON 里配 `brutality:max_mana` 只是**指向这一资源池的命名空间**，实际扣的是反射桥的当前蓝。（命名易误导，但这是既有整合包数据格式，保持兼容。）

### 4. 为什么法术伤害由通用 Mixin 接管而不是每个弹自己结算
- 外部法弹（adventuresmod:spell 等 13 处）各自有原生命中逻辑，逐 mod 写 mixin = 无止境；
- `Projectile.onHit(HitResult)` 是所有弹射物命中的**统一汇流点**（子类 `onHitEntity/onHitBlock` 由它分发），注入 HEAD 一处覆盖全部；
- 只有带 `spell_damage` NBT 的弹才被接管，原生弹不受影响；`mage$spell_handled` 幂等标记防双路径重复结算。

### 5. ⚠️ L2 法伤双倍（已修·勿复发）
`l2damagetracker:magic_damage` 由 **L2DamageTracker 原生施加**在魔法伤害上（伤害类型是 INDIRECT_MAGIC 等），Mage 若再手动乘 = 双乘 → 任意法伤增幅变 2 倍（2026-10-19 实证）。**伤害结算绝不手动乘 magic_damage**；HUD 面板只做显示预估值。口诀：L2 原生施加、Mage 只显示不乘。

### 6. 为什么 `mage:mana` 默认属性还在但配置不依赖它
`init/MageAttributes` 注册 `mage:mana`（默认 100 的 RangedAttribute 挂 Player）是**零依赖兜底资源池**——别的整合包没装 Brutality 也能用纯属性法力。整合包实际用 `brutality:max_mana` 走反射桥，`mage:mana` 是给"无宿主环境"的保底。

## 与宿主的边界（谁在哪做什么）

- **Mage**：运行时 + 数据契约 + 通用接管 + 数值显示（本模组）。
- **整合包数据包**：`caster_stats/*.json` 定义法术（耗什么/放什么弹/伤害类型）——消费侧契约细节见技能 `story-of-forgotten-land-openloader-data`。
- **SOFL 模组**：仅剩特殊法杖 mixin（风雪/云法杖等需特殊注入的实体）委托 Mage 接口；通用法术 procedures 已注释/no-op。见技能 `mcreator-mixin-procedure` §3H。
- **L2DamageTracker**：魔法伤害倍率的原生施加方。

## 维护约定
- 改数据契约（JSON schema / NBT 协议 / DamageType 映射）→ 同步本项目 `docs/architecture.md` + 整合包技能 `story-of-forgotten-land-openloader-data` 的 caster_stats 章节（消费侧）——两处口径必须一致。
- 本项目有 git → 改动前状态 `git show HEAD:` 取回，**不落文件级 bak**。
