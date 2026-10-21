# Mage —— 零依赖·数据驱动施法框架

**Mage** 是一个独立的 Minecraft Forge 1.20.1 模组，提供一套**完全数据驱动的法术系统**：
整合包作者用 JSON 定义"哪把法杖放什么弹、耗什么资源、造成什么伤害类型、怎么飞行怎么命中"，
Mage 负责把 JSON 变成游戏里的法术。**零第三方编译依赖**（只依赖 Minecraft + Forge），
可单独发布、可被任何整合包使用——它最初从《遗尘往事》整合包抽取而来，但不止服务于它。

```
modid:     mage
映射:      Official (Mojang)
环境:      Forge 47.4.x · Minecraft 1.20.1 · Java 17
依赖:      无（仅 minecraft + forge）
```

---

## 快速开始

1. 把 `mage-*.jar` 放进 `mods/`
2. 数据包（或 `config/openloader/data/…`）里给对应目录放一个法杖配置：`data/<ns>/caster_stats/<任意名>.json`
3. 进游戏，手持配置中 `item` 指定的物品右键施法

配置示例（整合包数据包 `data/<ns>/caster_stats/<任意名>.json`，**模组 jar 内不内置任何样例**）：

```json
{
  "item": "mage:test_staff",
  "cost": { "attribute": "brutality:max_mana", "amount": 10.0 },
  "fastCooldown": 12,
  "slowCooldown": 20,
  "isOverwrite": true,
  "sequence": [
    { "type": "projectile", "damage": 8.0, "ballType": 0, "speed": 2.2, "lifetime": 60 },
    { "type": "antigravity" },
    { "type": "split", "count": 3, "spread": 40 },
    { "type": "homing", "range": 16.0, "turn": 12.0, "hostileOnly": true },
    { "type": "area", "radius": 3.0, "interval": 5 },
    { "type": "bounce", "count": 3 }
  ]
}
```

`/reload` 即可热重载。

> **顶层扁平字段（projectileType/ballDamage/projectileSpeed/lifetime）已废弃**——新配置一律
> 把弹的属性写进 sequence 里的 `projectile` 步骤。loader 仅对**旧格式 JSON（无 sequence）**
> 向后兼容折叠成单个 projectile 步骤。

---

## 配置字段（顶层）

| 字段 | 必填 | 说明 |
|---|---|---|
| `item` | ✅ | 法杖物品注册名（`mod:item`） |
| `name` | - | 物品显示名（可中文） |
| `cost` | - | `{attribute, amount}` 施法消耗；缺 = 不消耗 |
| `fastCooldown` / `slowCooldown` | - | 冷却（tick）；`spell_potency` 附魔切 fast |
| `isOverwrite` | - | 是否吞掉物品原右键行为 |
| `sequence` | ✅ | **有序法术步骤**（见下）；无 projectile 步骤 → 不发射、不扣蓝 |

**ballType 伤害类型映射**：`0=普通魔法 · 1=火焰 · 2=冰霜 · 3=闪电 · 4=虚空 · 5=奥术(目标减速+施法者再生)`

**消耗模型**：`cost.attribute` 指定消耗哪个属性，按**当前值**扣减、够才放行。
`brutality:max_mana` 有特例——经反射桥接读 Brutality 的**当前蓝条**（不是上限），
无 Brutality 时放行不扣。**蓝不足时自动吃背包魔法糖果（+40）兜底重判**（`mage:magic_candy`
与 `story_of_forgotten_land:magic_candy` 都认）。想用别的资源当蓝直接把 attribute 换掉即可。

> 旧格式兼容：只有 `manaCost` 视为消耗 `mage:mana`；无 sequence 的旧扁平字段折叠成单 projectile 步骤。

---

## sequence —— 有序法术步骤（Noita 式流水线）

法术不再是"一个固定弹"，而是**一串按顺序执行的步骤**：第一步必须是 payload（发射物），
后面的修饰符按序改造它。**顺序有语义**（修饰符只作用于它之前收集的弹）。

| type | 分类 | 参数 | 说明 |
|---|---|---|---|
| `projectile` | payload | `damage` `ballType` `speed` `lifetime` `projectileType` | **唯一**发射步骤：受重力弹（抛物线），弹的一切属性在此定义 |
| `antigravity` | modifier | 无 | 把已收集的弹标记为无重力（直线）——**修饰符**，不带任何 payload 属性 |
| `visual` | modifier | `entity` `yawOffset` `particles` | **外观修饰**：把弹渲染委托给任意模组实体模型 + 拖尾粒子 |
| `split` | modifier | `count`（倍率） `spread`（总角度·度） | 每颗弹复制 count 份扇形分布；**可叠层**（×2 再 ×2 = ×4） |
| `pierce` | modifier | `count`（可穿透实体数） | 穿墙不消失、每个实体只结算一次、穿够 count 个消失 |
| `homing` | modifier | `range` `turn`（度/tick） `hostileOnly` | 追踪：范围内最近可锁目标转向；hostileOnly=true 只锁敌对（Enemy ∪ 对施法者仇恨），驯养生物恒排除 |
| `area` | modifier | `radius` `interval` | **持续范围伤害场**（lethality AcidicSlash 式）：飞行期间每 interval tick 对半径内实体结算范围伤害，碰撞瞬间额外结算一次 |
| `bounce` | modifier | `count` | 撞方块反射速度继续飞（严格对齐 conjurer BouncingBall：反射后速度大小不变、弹跳高度恒定），count 耗尽才消失 |

示例：`projectile → antigravity → visual → split×3 → homing → area → bounce×3`
= 3 颗无重力直线弹（全用目标实体外观 + 拖尾），追踪敌对目标、每 5 tick 范围伤害、撞墙反弹 3 次。

> **弹跳行为**（2026-10-22 对齐 conjurer）：反射后速度**大小不变**、无能量衰减，反弹多少次速度都恒定、
> 弹跳高度恒定——只靠 `bounce.count` 兜底。受重力弹落地时保底起跳 vy（0.15）防贴地板逐 tick 反复撞。
> **pierce 与 bounce 互斥**：穿透弹撞墙不反射（onHitBlock 早退），一把杖别同时堆两者。

---

## 视觉委托渲染（用任意模组实体的模型当法术弹）

外观是**修饰属性**，独立成 `visual` 步骤，作用于它之前收集的所有弹；可附加拖尾粒子（**可多种叠加**）：

```json
{
  "type": "visual",
  "entity": "lethality:acidic_slash",
  "yawOffset": 90,
  "particles": [
    { "particle": "lethality:acidic_impact", "count": 3 },
    { "particle": "lethality:acidic_bubble", "count": 3 }
  ]
}
```

| 参数 | 说明 |
|---|---|
| `entity` | 目标实体注册名；空/查不到 → 回落内置 billboard |
| `yawOffset` | 视觉水平偏角（度，0 = 不偏）。目标模型自带朝向基准时用来校准（如酸性斩击需 ±90°） |
| `particles` | 拖尾粒子数组 `[{particle, count}...]`，**追加不覆盖**——多次 visual 步骤可叠多种粒子 |

- 原理：客户端渲染器发现 `spell_visual` 后，创建目标实体的**代理实例**（仅渲染用，不进
  世界、不同步），用目标实体自己的 Renderer 渲染——任意模组的 GeoModel/模型都能显示。
- 拖尾粒子由服务端每 tick 在弹位置发送（`ServerLevel.sendParticles`），走 `spell_particles` NBT 列表。
- 目标实体查不到 / 创建失败 / 粒子查不到 → 优雅回落到内置 billboard / 跳过该粒子，不会崩渲染。
- 语义：视觉与发射解耦——split 复制的每一颗都继承同一外观与拖尾；pierce 不改外观。
- 多次 visual：`entity` 后写覆盖前写（外观唯一），`particles` 追加（可叠加多种）。

---

## 附魔

| 附魔 | 注册名 | 效果 |
|---|---|---|
| 快速施法 | `mage:spell_potency` | 切换到 fast 冷却 |
| 强效法术 | `mage:forceful` | 每级 +1 伤害 |
| 爆裂法术 | `mage:burst` | 命中点燃目标 10 秒 |

附魔只作用于在 caster_stats 里有配置的法杖。

---

## HUD 数值面板

手持法杖时屏幕左侧显示：**伤害（单体伤害×数量，如 `8x3`）**、类型、消耗、速度、冷却。
伤害会叠加附魔/药水/莱特兰法伤加成（显示预估，结算交给游戏机制）。

---

## 与《遗尘往事》的关系

- **本模组 = 通用施法内核**；《遗尘往事》的数据包 / SOFL 模组是它的**宿主与消费者**。
- 宿主消费 Mage，Mage 不复用宿主：特殊法杖（风雪/云法杖，需要独特注入）留在 SOFL 模组里，
  它们通过 Mage 的 `SpellCostManager` / `SpellDamageHelper` 委托通用逻辑。
- 整合包的具体 config（哪把杖、掉哪、多少耗）都在数据包 / KubeJS，不在此模组。

---

## 构建

```
./gradlew.bat build
```

生成的 jar 在 `build/libs/`。编译时临时注释 `gradle.properties` 里
`org.gradle.jvmargs=-Xmx3G` 可规避部分环境 gradle daemon 被杀的问题。