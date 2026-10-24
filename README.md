# Mage —— 数据驱动的法术系统

**Mage** 是一个 Minecraft Forge 1.20.1 模组：整合包作者用 JSON 定义"哪把法杖放什么弹、
耗什么资源、造成什么伤害、怎么飞行怎么命中、附带什么特效"，Mage 负责把 JSON 变成游戏里的法术。

**使用者只需要写 JSON，不需要写任何代码。**

```
modid:     mage
环境:      Forge 47.4.x · Minecraft 1.20.1 · Java 17
依赖:      无（仅 Minecraft + Forge）
```

---

## 快速开始

1. 把 `mage-*.jar` 放进 `mods/`
2. 在数据包（或 `config/openloader/data/…`）里放一个法杖配置：
   `data/<命名空间>/caster_stats/<任意名字>.json`
3. 进游戏，手持配置里 `item` 指定的物品右键施法
4. 改配置后 `/reload` 立即生效，无需重启

> 模组 jar 内不内置任何样例配置——所有法术都来自你写的 JSON。

---

## 配置长什么样

```json
{
  "item": "mage:test_staff",
  "name": "示例法杖",
  "cost": { "amount": 10.0 },
  "fastCooldown": 12,
  "slowCooldown": 20,
  "isOverwrite": true,
  "sequence": [
    { "type": "projectile", "damage": 8.0, "damageType": "magic", "speed": 2.2, "lifetime": 60 },
    { "type": "antigravity" },
    { "type": "split", "count": 3, "spread": 40 },
    { "type": "homing", "range": 16.0, "turn": 12.0, "hostileOnly": true },
    { "type": "area", "radius": 3.0 },
    { "type": "bounce", "count": 3 }
  ]
}
```

`sequence` 是整根法杖的灵魂：**一串按顺序执行的步骤**。第一步是 `projectile`（定义弹本体），
后面的修饰符步骤按顺序改造它——这个顺序有实际意义，就像 Noita 的法术流水线。

---

## 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `item` | ✅ | 法杖物品注册名（`mod:item`） |
| `name` | | 物品显示名（可中文） |
| `cost` | | `{attribute, amount}` 施法消耗；不写 = 免费施法 |
| `fastCooldown` / `slowCooldown` | | 冷却（tick）；附魔「快速施法」切换用 fast |
| `isOverwrite` | | `true`=吞掉物品原右键行为、由 Mage 发内置弹；`false`=保留原模组自己的法术（见下） |
| `sequence` | ✅ | 有序法术步骤；接管模式下没有任何 `projectile` 步骤 → 不发射、不扣消耗 |

**消耗（cost）**：`{ "amount": N }`，**不写 `attribute` 时默认消耗 Brutality 的当前蓝
（魔力，非属性）**——够才放行；蓝不足自动吃背包魔法糖果（+40）再试一次。
要换成别的资源池，显式写 `"attribute": "<属性注册名>"`（如 `"attribute": "mage:mana"`，
按该属性当前值扣减）。缺 `cost` 字段 = 免费施法。

### `isOverwrite`：接管 vs 兼容原模组

- **`true`（接管）**：取消物品原右键行为，Mage 自己发 `mage:spell_bolt`。外观用 `visual` 步骤
  委托任意模组实体模型。`sequence` 没有 `projectile` 步骤 = 不发射、不扣消耗。
- **`false`（兼容原模组法术）**：**不取消**右键、**不发** Mage 弹——让原物品 `use()` 自己施法。
  - `sequence` 配了 `projectile` 步骤：Mage 把该步骤（及 `area` / `applyEffect` / 附魔加成）的
    伤害语义序列化成 NBT，在**原版弹加入世界时**打到它身上（施法后 5 tick 内、同一玩家、`Projectile`
    且 owner 为该玩家）。命中后由通用 mixin 改走 Mage 统一结算——原版弹因此吃到 Mage 的伤害/类型/
    范围/药水效果。**运动方式（速度/追踪/反弹/穿透）仍由原模组控制**，Mage 不覆盖。
  - 没配 `projectile`（原版法术只是加 buff、不发射弹）：纯原版行为，Mage 只负责**消耗与冷却**。
  - 两种情况下蓝不够都会拦下原版施法（Mage 充当资源层）；冷却用 vanilla 物品冷却。

---

## sequence 步骤 —— 每个原语干什么

### 发射物（payload）—— 弹的一切属性

> **弹永远是内置的 `mage:spell_bolt`**（已不再支持别的模组弹体）。要长得像别的法术
> （剑气、火球…），用 `visual` 步骤把它"渲染成"任意模组实体的样子——外观和逻辑完全解耦。

| 参数 | 说明 |
|---|---|
| `damage` | 基础伤害 |
| `damageType` | 伤害类型：`magic` / `fire` / `frost` / `lightning` / `void`（纯伤害，无附加效果） |
| `damageInterval` | 弹级伤害间隔 tick：`0`=每个实体只结算一次；`>0`=每 N tick 重复结算（持续场/帧伤） |
| `speed` | 初速；`0` = 停在原地 |
| `lifetime` | 存活 tick（过了自动消失；毒雾残留时长靠它控制） |

### 修饰符（modifier）—— 叠加改造弹

| type | 参数 | 作用 |
|---|---|---|
| `antigravity` | — | 弹不受重力，直线飞行（默认受重力走抛物线） |
| `split` | `count` `spread` | 每颗弹复制 count 份扇形散开；可叠层（×2 再 ×2 = ×4） |
| `pierce` | `count` | 穿墙/穿过实体不消失，穿够 count 个不同实体后消失 |
| `homing` | `range` `turn` `hostileOnly` | 追踪范围内最近目标转向；`hostileOnly=true` 只锁敌对 |
| `area` | `radius` | 以弹为球心、radius 格半径的**立体球**范围伤害 |
| `ground` | `radius` | 以弹正下方地面为圆心的**贴地平盘**范围伤害（毒雾/灼烧场用这个） |
| `bounce` | `count` | 撞方块反射继续飞，弹跳高度恒定，count 次后消失 |
| `grantEffect` | `effects` 列表 | **给自己**加药水效果（如施法回血） |
| `applyEffect` | `effects` 列表 | **给被打中的敌人**加药水效果（如中毒/减速） |
| `explode` | `power` `destroyBlocks` | 碰撞（方块/实体）时产生爆炸；配合 `bounce` 每次撞墙都炸。`power`=威力格（TNT≈4）；`destroyBlocks=true`（缺省）跟随游戏防爆规则破坏方块（`mobGriefing=false` 时自动不破坏地形），`false`=只爆炸伤害不破坏地形 |
| `beam` | `duration` | **平铺时间**：把弹飞过的路径变成持续 `duration` tick 的射线（激光），沿线伤害与粒子在停留期内持续生效（见下）。碰撞仍由 `pierce`/`bounce` 决定，beam 不干预 |
| `visual` | `entity` `yawOffset` `particles` `onDeath` | 用别的模组实体模型当外观 + 特效粒子（见下）。`entity` 就是原先的"弹射物"——旧配置里写在 projectile 步骤的 `projectileType` 已废弃，外观委托统一移到这里 |

`grantEffect` / `applyEffect` 的效果是**列表**，可同时配多个：

```json
{ "type": "grantEffect", "effects": [
    { "effect": "minecraft:regeneration", "duration": 100, "amplifier": 0 },
    { "effect": "minecraft:absorption", "duration": 200, "amplifier": 1 }
] }
```

```json
{ "type": "applyEffect", "effects": [
    { "effect": "minecraft:poison", "duration": 100, "amplifier": 0 },
    { "effect": "minecraft:slowness", "duration": 60, "amplifier": 1 }
] }
```

> `applyEffect` 的效果跟着伤害走：命中结算、`area` 范围、`ground` 贴地全都会触发，而且和伤害次数一致
> （每实体一次的效果只上一回；持续伤害场每 tick 刷新效果）。

---

## 伤害的三个正交维度

所有伤害相关的原语互相独立、可自由组合：

- **碰撞策略** `pierce` —— 能不能穿过去
- **空间范围** `area`（球体）/ `ground`（贴地平盘）/ `beam`（线·平铺时间）—— 伤害作用在多大范围
- **时间频率** `damageInterval`（弹级全局）—— 多久结算一次

常用组合：

| 想要的效果 | 配置要点 |
|---|---|
| 直线穿人弹 | `projectile + antigravity + pierce` |
| 爆炸球 | `projectile + area` |
| 持续灼烧场 | `projectile + area + damageInterval: 5` |
| 贴地毒雾 | `projectile + ground + damageInterval: 5` |
| 帧伤（穿过的每 tick 都结算） | `projectile + pierce + damageInterval: 1` |
| 弹幕（一次十几颗） | `projectile + split` + `split` |

> **area 还是 ground？** 要立体效果（环绕弹的法阵/爆炸球）用 `area`；要贴地效果
> （毒雾/灼烧/法阵铺地）用 `ground`——ground 的伤害判定在弹正下方的地面上，
> 和贴地粒子的位置严格对齐，不会出现"粒子在地上、伤害在空中"的错位。

---

## beam —— 预演路径的瞬时射线（激光）

`beam` 是第四种**空间形状**（点 / 球 `area` / 平盘 `ground` / **线 `beam`**）。它的实现方式是
**预演缓存**：施法后第一个 tick 把弹将来整段飞行（`speed × lifetime`）以 0.5 格子步做一次
确定性预演，缓存成一条折线路径，**预演完永不改变**——整条激光同 tick 一次展开、停留
`duration` tick 后整体淡出。

- **不靠弹往前飞**：弹本体冻结成"射线持有者"；无论 `speed` 多大、单 tick 跨多少格，路径按 0.5 格
  子步预演，**恒不漏块、不漏怪**。`homing` 只在预演开始时参与**一次**（对最近目标转向一步），
  之后路径固定；`bounce` 反射 / `pierce` 在预演内判定——**同时配 `bounce` 与 `pierce` 时，方块碰撞一律弹跳
  （`pierce` 只对实体生效）；只有「纯 `pierce`、无 `bounce`」才穿墙**。
- **伤害**：以**线段集合**为最小数据单位——每个实体一次评估只结算一次（不会因同一条线上相邻
  子步点被反复结算）：
  - `damageInterval` 不配（0）：每个被射线碰到的实体**整条射线期只吃一次**，即使射线停留很久；
  - 配了 `damageInterval`：停留期内每 interval tick 对线上实体**持续结算**一次（灼烧/持续激光）。
- **与其他原语正交组合**：`area` 叠在射线上 = **管状伤害**（管半径 = `area.radius`，粒子粗度同步）；
  `applyEffect` / `damageInterval` 照常按结算目标生效；`pierce`/`bounce`/`homing`/`explode` 在预演里成型；
  `split` 各叉独立。`ground` 对冻结的弹没有意义（贴地盘只会落在施法点）。
- **粒子**：`visual` 步骤粒子 `mode` 用 `"beam"` → 沿整条缓存路径铺线，与伤害线严格重合。
  ⚠️ 弹冻结在施法点，**别的 mode（spread/sphere/disc…）只会原地凝聚看不到线**——配 beam 必须用
  `mode:"beam"`。一键实装生成器会自动把粒子列的 mode 改写为 `beam`。
- 预算：路径 ≤96 格、子步 ≤4096（防一次施法卡死；split 各叉各自预演）。

常用组合：

| 想要的效果 | 配置要点 |
|---|---|
| 一道穿透激光（沿途全伤、撞墙消失） | `projectile + beam + pierce` |
| 弹跳激光（撞墙反弹，每段都切） | `projectile + beam + bounce` |
| 三叉扇形激光 | `projectile + beam + split×3` |
| 持续灼烧激光（停留期每 5 tick 结算） | `projectile + beam + damageInterval:5` |
| 追踪激光（预演开始时弯向目标一次） | `projectile + beam + homing` |
| 炸射激光（每面反弹墙/终墙处爆炸） | `projectile + beam + explode`（(+ `bounce`)） |

```json
{ "type": "projectile", "damage": 6.0, "damageType": "void", "speed": 4.0, "lifetime": 60 },
{ "type": "antigravity" },
{ "type": "beam", "duration": 20 },
{ "type": "pierce", "count": 5000 },
{ "type": "applyEffect", "effects": [ { "effect": "minecraft:wither", "duration": 60 } ] },
{ "type": "visual", "particles": [ { "particle": "minecraft:end_rod", "count": 16, "mode": "beam" } ] }
```

---

## 特效粒子 —— 五种形状

> **弹本体默认不带任何粒子**（基类的黑烟尾迹已屏蔽）。拖尾/毒雾等特效全靠 `visual` 步骤
> 显式配置 `particles` —— 没配就没有。

`visual` 步骤可配多种粒子叠加，每种粒子一个形状：

| mode | 形状 | 跟随方式 |
|---|---|---|
| `spread`（缺省） | 点扩散，弹位置小抖动 | 随弹 |
| `sphere` | 立体球形 | 随弹 |
| `cube` | 立体正方形 | 随弹 |
| `disc` | 扁平圆盘 | **贴地**：铺在弹正下方地面 |
| `square` | 扁平方盘 | **贴地**：铺在弹正下方地面 |
| `beam` | 线状（沿射线线段铺线，需同时配 `beam` 原语） | 随射线：射线停留则粒子持续，淡出则随之消失 |

**扁平形状（disc/square）会投影到地面**——弹在半空时，粒子不是悬在半空的扁片，
而是铺在弹正下方的完整方块顶面上。弹移动则贴地雾跟着移动，弹停下则雾在原地残留
（残留多久由 `lifetime` 决定）。

```json
{ "type": "visual", "particles": [
    { "particle": "minecraft:campfire_smoke", "count": 12, "mode": "sphere", "radius": 2.0 },
    { "particle": "minecraft:ash", "count": 6, "mode": "disc", "radius": 2.0 }
], "onDeath": [
    { "particle": "minecraft:enchant", "count": 20, "mode": "sphere", "radius": 2.0 }
] }
```

| 参数 | 说明 |
|---|---|
| `entity` | 用别的模组实体的模型渲染弹体；空/查不到 = 内置发光小球。兼容旧 JSON：顶部旧字段 `projectileType` 会被自动当作这里的实体（外观委托） |
| `yawOffset` | 外观水平偏角（度）；目标模型朝向不平时用来转正 |
| `particles` | 粒子数组 `[{particle, count, mode?, radius?}]`；多次 visual 可叠多种 |
| `onDeath` | **消失粒子**（法术消失时一次性爆发的特效，如剑气消散）：同 `particles` 结构。任意消失路径（寿命到/命中/pierce 用完/弹跳耗尽）都会触发，只发一次 |

---

## 完整示例合集

### 贴地移动毒雾（怪踩到就中毒，每 5 tick 结算一次）

```json
"sequence": [
  { "type": "projectile", "damage": 2.0, "damageType": "void", "damageInterval": 5, "speed": 0.4, "lifetime": 120 },
  { "type": "antigravity" },
  { "type": "ground", "radius": 2.0 },
  { "type": "pierce", "count": 5000 },
  { "type": "applyEffect", "effects": [ { "effect": "minecraft:poison", "duration": 100 } ] },
  { "type": "visual", "particles": [ { "particle": "minecraft:flame", "count": 20, "mode": "disc", "radius": 2.0 } ] }
]
```

### 脚下残留毒雾（原地停留 5 秒，施法者顺带回血）

```json
"sequence": [
  { "type": "projectile", "damage": 1.5, "damageType": "void", "damageInterval": 10, "speed": 0, "lifetime": 100 },
  { "type": "antigravity" },
  { "type": "ground", "radius": 3.0 },
  { "type": "grantEffect", "effects": [ { "effect": "minecraft:regeneration", "duration": 100 } ] },
  { "type": "visual", "particles": [
      { "particle": "minecraft:campfire_smoke", "count": 14, "mode": "disc", "radius": 3.0 },
      { "particle": "minecraft:squid_ink", "count": 8, "mode": "square", "radius": 1.5 }
  ] }
]
```

### 穿透闪电帧伤（穿过实体每 tick 结算，穿 5 个消失）

```json
"sequence": [
  { "type": "projectile", "damage": 3.0, "damageType": "lightning", "damageInterval": 1, "speed": 1.2, "lifetime": 40 },
  { "type": "antigravity" },
  { "type": "pierce", "count": 5 },
  { "type": "visual", "particles": [
      { "particle": "minecraft:electric_spark", "count": 4 },
      { "particle": "minecraft:soul", "count": 50, "mode": "sphere", "radius": 0.8 }
  ] }
]
```

### 追踪分裂弹幕（3×3 颗追踪弹，撞墙反弹 3 次）

```json
"sequence": [
  { "type": "projectile", "damage": 8.0, "damageType": "magic", "speed": 2.2, "lifetime": 60 },
  { "type": "antigravity" },
  { "type": "split", "count": 3, "spread": 40 },
  { "type": "split", "count": 3, "spread": 20 },
  { "type": "homing", "range": 16.0, "turn": 12.0, "hostileOnly": true },
  { "type": "bounce", "count": 3 }
]
```

### 爆炸弹跳（撞墙 5 次炸 5 次，不破坏地形，消失时火焰爆开）

```json
"sequence": [
  { "type": "projectile", "damage": 2.0, "damageType": "fire", "speed": 1.2, "lifetime": 60 },
  { "type": "bounce", "count": 5 },
  { "type": "explode", "power": 2.0, "destroyBlocks": false },
  { "type": "visual", "particles": [
      { "particle": "minecraft:flame", "count": 4 },
      { "particle": "minecraft:campfire_smoke", "count": 8, "mode": "sphere", "radius": 1.0 }
  ], "onDeath": [
      { "particle": "minecraft:large_fire", "count": 8, "mode": "sphere", "radius": 1.5 }
  ] }
]
```

---

## 附魔

| 附魔 | 注册名 | 效果 |
|---|---|---|
| 快速施法 | `mage:spell_potency` | 冷却切到 `fastCooldown` |
| 强效法术 | `mage:forceful` | 每级 +1 伤害 |
| 爆裂法术 | `mage:burst` | 命中点燃目标 10 秒 |

只对在 caster_stats 里配置过的法杖生效。

---

## HUD 数值面板

手持法杖时屏幕左侧显示：伤害（单体×数量）、伤害类型、消耗、速度、冷却。
伤害会叠加附魔/药水/法伤加成（显示为预估值，实际结算交给游戏机制）。

---


## 与《遗尘往事》的关系

**Mage 是通用施法内核**，《遗尘往事》的数据包/SOFL 模组是它的宿主与消费者。
宿主消费 Mage、Mage 不复用宿主——具体哪把杖、掉哪、多少耗，都在数据包/KubeJS，不在此模组。

---

## 构建

```
./gradlew.bat build
```

生成的 jar 在 `build/libs/`。若 gradle daemon 被杀，可临时注释 `gradle.properties`
里 `org.gradle.jvmargs=-Xmx3G` 后重试。
