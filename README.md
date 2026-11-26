# rec-privacy — 好友推荐管道的隐私约束层

针对的攻击场景:注册小号、只上传受害者一个人的手机号,推荐列表就会
返回受害者的心理医生、前任、匿名小号 —— 推荐结果把用户刻意隐藏的
关系暴露了出来。

## 泄露为什么成立

朴素管道里,一条推荐只需要一条信号边就能成立。小号攻击利用了三点:

1. **可归因性**:小号只上传了 1 个号码,任何通讯录来源的推荐都能被
   唯一归因到那个号码的主人 —— 推荐列表 = 受害者的隐藏关系列表。
2. **单向边**:病人存医生的号码,医生不存病人的;单向通讯录边正是
   敏感关系的主要载体,但朴素打分不区分方向。
3. **单信号垄断**:敏感关系往往只有通讯录这一个信号(没有共同好友、
   没有互动),朴素打分不惩罚信号集中度。

## 设计:六个可组合约束 + 预算化执行

约束全部实现 `PrivacyConstraint.evaluate`,对单个候选 O(1) 判定,
返回 `Allow / Demote(factor) / Suppress(reason)`:

| 约束 | 检测的信号 | 作用 |
|---|---|---|
| `MinimumAttributionConstraint` | 接收方通讯录上传数 < k(小号特征) | 纯通讯录候选抑制,有佐证的降权 —— 推荐的"解释 k-匿名" |
| `ContactReciprocityConstraint` | 单向通讯录边 | 无佐证抑制,有佐证降权 |
| `SignalDominanceConstraint` | 主导信号占比(HHI) | 超 softLimit 降权;通讯录主导 ≥95% 抑制 |
| `BridgeExposureConstraint` | 共同好友路径汇聚到 ≤1 个中间人 | 降权;叠加单向通讯录边则抑制 |
| `SensitiveHubConstraint` | 目标是"隐秘关系枢纽"(高入度、低互存率、上传者互不相识 —— 心理医生/律师的图统计形态) | 纯通讯录来源推荐抑制 |
| `UserExclusionConstraint` | 用户主动排除的关系 | 硬抑制,不占预算、不可降级 |

`PrivacyAwareRanker` 合并各约束判定(Suppress 优先,Demote 系数连乘),
然后按 `EnforcementPolicy` 执行。

## 为什么推荐效果不会被打没

推荐效果是核心指标,约束层为此做了三件事:

1. **降权优先于抑制**:有非通讯录佐证的候选一律降权而不是删除;
   多信号的正常关系(双向通讯录 + 共同好友 + 行为)几乎不受影响。
2. **抑制预算**(`suppressionBudget`):每个接收方每轮最多抑制固定
   比例的候选,按风险分(通讯录占比 × 是否无佐证)优先抑制最危险的;
   超预算的候选转为重度降权,列表丰富度有下限。`minListSize` 保证
   最终列表不会被打空。
3. **可观测、可灰度**:`ConstraintMetrics` 按约束分桶统计触发/降权/
   抑制/超预算次数,直接对接监控。触发率突增 = 攻击流量或数据漂移;
   抑制率与 CTR/关注转化率联合看板用来调阈值。所有阈值
   (k、占比上限、预算)都是构造参数,可走配置中心灰度。

## 规模:每天几亿用户怎么算

关键决策是**约束求值不碰图**。所有图结构信息由离线作业预聚合:

- `SourceUserFeatures`(上传数、排除列表)—— 用户表直接读;
- `TargetUserFeatures`(通讯录入度、互存率、上传者互连密度)——
  每日 Spark 图聚合,一次 join 产出;
- `SignalEvidence`(边的方向、中间人集合)—— 候选生成时已有的信息。

约束求值只剩哈希查找和算术,可以原样内联进现有 Spark/Flink 打分
算子,不增加 shuffle、不引入外部存储查询。候选量不变,单候选成本
从"一次打分"变成"一次打分 + 几十次比较",在现有管道的噪声范围内。

## 运行

```bash
sbt test   # 17 个约束与排序器测试
sbt run    # 复现小号攻击场景:朴素管道 vs 隐私约束管道
```

## 结构

```
src/main/scala/recprivacy/
  model.scala       信号、证据、候选(占比/HHI/主导信号)
  features.scala    离线预聚合的接收方/被推荐人特征(含隐秘枢纽判定)
  verdict.scala     Allow / Demote / Suppress 及合并规则
  constraints.scala 六个隐私约束
  policy.scala      执行策略(抑制预算、回填)与分桶指标
  ranker.scala      约束合并 + 预算执行 + 排序的管道
  Main.scala        小号攻击场景演示
src/test/scala/recprivacy/
  ConstraintSuite.scala  逐约束单测
  RankerSuite.scala      端到端:攻击场景、预算、排除、回填
```
