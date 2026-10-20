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
  "projectileType": "",
  "ballDamage": 8.0,
  "ballType": 3,
  "projectileSpeed": 2.0,
  "lifetime": 40,
  "cost": { "attribute": "brutality:max_mana", "amount": 10.0 },
  "fastCooldown": 12,
  "slowCooldown": 20,
  "isOverwrite": true,
  "sequence": [
    { "type": "antigravity" },
    { "type": "visual", "entity": "lethality:acidic_slash" },
    { "type": "split", "count": 3, "spread": 40 },
    { "type": "pierce", "count": 5 }
  ]
}
```

`/reload` 即可热重载。

---

## 配置字段（顶层）

| 字段 | 必填 | 说明 |
|---|---|---|
| `item` | ✅ | 法杖物品注册名（`mod:item`） |
| `name` | - | 物品显示名（可中文） |
| `projectileType` | - | 外部法弹实体 id；空/缺 = 内置弹 `mage:spell_bolt` |
| `ballDamage` | - | 法术基础伤害（顶层默认；sequence 内 projectile 步骤也可自己写） |
| `ballType` | - | 伤害类型 0-5（见下） |
| `projectileSpeed` | - | 初速 |
| `lifetime` | - | 存活 ticks |
| `cost` | - | `{attribute, amount}` 施法消耗；缺 = 不消耗 |
| `fastCooldown` / `slowCooldown` | - | 冷却（tick）；`spell_potency` 附魔切 fast |
| `isOverwrite` | - | 是否吞掉物品原右键行为 |
| `sequence` | - | **有序法术步骤**（见下）；缺省 = 旧扁平字段折叠成单个 projectile |

**ballType 伤害类型映射**：`0=普通魔法 · 1=火焰 · 2=冰霜 · 3=闪电 · 4=虚空 · 5=奥术(目标减速+施法者再生)`

**消耗模型**：`cost.attribute` 指定消耗哪个属性，按**当前值**扣减、够才放行。
`brutality:max_mana` 有特例——经反射桥接读 Brutality 的**当前蓝条**（不是上限），
无 Brutality 时放行不扣。想用别的资源当蓝（饥饿值/自定义属性）直接把 attribute 换掉即可。

---

## sequence —— 有序法术步骤（Noita 式流水线）

法术不再是"一个固定弹"，而是**一串按顺序执行的步骤**：第一步通常是 payload（发射物），
后面的修饰符按序改造它。**顺序有语义**（修饰符只作用于它之前收集的弹）。

| type | 分类 | 参数 | 说明 |
|---|---|---|---|
| `projectile` | payload | `damage` `ballType` `speed` `lifetime` `projectileType` | 发射受重力弹（抛物线） |
| `antigravity` | payload | 同上 | 发射无重力弹（直线，当前主流） |
| `visual` | modifier | `entity` | **外观修饰**：把弹渲染委托给任意模组实体模型 |
| `split` | modifier | `count`（倍率） `spread`（总角度·度） | 每颗弹复制 count 份扇形分布；**可叠层**（×2 再 ×2 = ×4） |
| `pierce` | modifier | `count`（可穿透实体数） | 穿墙不消失、每个实体只结算一次、穿够 count 个消失 |
| _homing/area/bounce…_ | 规划中 | — | 后续原语，走同一模式 |

示例：`antigravity → visual → split×3 → pierce×5` = 3 颗直线弹（全用目标实体外观），每颗穿透 5 个实体。

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