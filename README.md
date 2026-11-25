# Privacy-safe Friend Recommendations in Scala

一个可运行的“可能认识的人”推荐管道参考实现。它在通讯录、共同好友、地理位置和社交行为四类候选信号之外加入：

1. **关系泄露信号识别**：小号、稀疏通讯录、单一手机号 oracle、低匿名度共同好友/地理桶、敏感二阶关系、匿名账号关联等。
2. **单一信号约束**：同族证据只算一个信号族；绝对上限、相对份额上限和 HHI 集中度检查共同防止通讯录等单一来源主导排序。
3. **用户关系排除**：拉黑、不感兴趣、屏蔽某段关系、按关系类型/敏感类别退出、关闭某个信号源；关系两端的偏好都会生效。
4. **效果保护**：不是“一刀切关闭通讯录”，而是按账号信任度、佐证信号、匿名度和风险分级处理，并用离线回放选择“攻击拦截率 100% 前提下保留有用推荐最多”的策略。
5. **大规模形态**：无外部依赖的 Scala 2.13 核心，按 `UserId` 分片、迭代器读取、每候选有界召回、固定大小 Top-K，可直接嵌入 Spark/Flink `mapPartitions` 或在线服务。

> 这是生产系统的核心规则引擎与数据流参考，不声称单独解决所有隐私问题。真实系统还需要密钥管理、风控、频控、审计、数据保留、隐私评审和在线 A/B。

---

## 快速运行

需要 JDK 17+ 和 sbt 1.10.x。

```bash
sbt test
sbt runMain privacyrec.PrivacyRecommendationDemo
sbt "runMain privacyrec.LoadBenchmark 50000 80 20"

# 若 shell 中的 java 是不可用的 /usr/bin/java 占位程序：
./scripts/sbt.sh test
```

如果 macOS 上没有系统 Java，但已通过 Homebrew 安装 OpenJDK 17：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
sbt test
```

当前包含 37 个 ScalaTest 用例，覆盖攻击复现、关系排除、候选/信号合并、上限、自适应策略、隐私门控、路径 fail-closed、分片故障隔离和离线策略选择。

本机一次冒烟测试（M 系列 Mac，JVM fork 内，2 万用户 × 80 候选）约为：

```text
users=20,000
candidates=1,600,000
elapsed=1,885 ms
userThroughput≈10,610 users/s/core
candidateThroughput≈848,806 candidates/s/core
```

该数字只衡量隐私合并/评分核心，不包含图谱 shuffle、KV 查询和候选召回；不能直接外推为生产集群容量。

---

## 攻击为什么会发生

朴素推荐常常把“我上传了某人的手机号”当成强入口，然后沿通讯录共现、共同好友或关系图做二阶扩展。攻击账号只上传一个手机号，恰好满足：

- 账号新、信任分低、通讯录极小；
- 入口标识符唯一，构成一个明确的 graph oracle；
- 心理医生、前任、匿名小号等关系通常是一阶敏感边；
- 这些边又可能因为“共同联系人/共同好友/附近出现”被再次激活；
- 推荐顺序或推荐理由形成侧信道，使攻击者确认隐藏关系是否存在。

因此，仅在 UI 上隐藏“你们的共同好友是 Alice”不够。只要候选 ID、排序、是否出现或理由仍由这条敏感路径决定，推荐结果本身就会泄露关系。

演示中的朴素打分能把以下候选排到最前：

```text
alice-anon-alt               naive_score=0.99
dr-kim-therapist             naive_score=0.98
jordan-ex-partner            naive_score=0.96
```

经过隐私管道后，这三个候选都因为隐藏/排除关系路径被清洗而无法进入推荐：

```text
- 拦截 dr-kim-therapist: privacy_gate / no_legal_evidence_after_exclusions
- 拦截 jordan-ex-partner: privacy_gate / no_legal_evidence_after_exclusions
- 拦截 alice-anon-alt: privacy_gate / no_legal_evidence_after_exclusions
```

正常高信任用户的多信号好友、普通通讯录好友仍可被推荐，只是单信号分数被限制在 cap 内。

---

## 管道总览

```text
分片用户输入
   │
   ▼
候选召回（各信号源 top-N，热 key 加盐/截断）
   │
   ▼
Hydration：用户画像、隐私设置、关系边、匿名度/队列统计
   │
   ▼
硬过滤：注销、封禁、拉黑、dislike、不可推荐
   │
   ▼
证据路径清洗：删除 hidden/blocked/shielded/opted-out edge
   │
   ▼
信号聚合：多条通讯录/好友证据仍只属于一个 SignalKind
   │
   ├──► 泄露风险识别 LeakDetector
   ├──► 单信号衰减/上限/HHI SignalBalancer
   │
   ▼
隐私门控：关键风险直接拒绝；高风险需独立信号佐证
   │
   ▼
效用评分与约束后降权；固定大小 Top-K
   │
   ▼
公开理由脱敏 + 内部审计码；写入推荐/审计 sink
```

关键代码：

| 文件 | 职责 |
|---|---|
| `Model.scala` | 用户、候选、信号、证据路径、关系边、隐私设置、决策和统计模型 |
| `PrivacyPolicy.scala` | 所有阈值、权重、严格策略和效用指标 |
| `ExclusionResolver.scala` | 拉黑、dislike、关系屏蔽、关系类型/敏感类别退出、信号源关闭 |
| `LeakDetector.scala` | 可解释的关系泄露风险规则和稳定 reason code |
| `PrivacyPolicySelector.scala` | 按正常/新鲜稀疏/探测/设备簇账号状态切换宽严策略 |
| `SignalBalancer.scala` | 信号族聚合、边际衰减、新鲜小号通讯录降权、份额 cap、HHI |
| `PrivacyPipeline.scala` | 过滤、清洗、检测、评分、门控、Top-K 和公开理由 |
| `BatchJob.scala` | `UserId` 哈希分片、迭代器批处理、有界 Top-K、统计 |
| `OfflinePolicyEvaluator.scala` | 用攻击回放和有效推荐标签选择隐私/效用平衡点 |
| `ContactHashes.scala` | 手机号规范化与 HMAC keyed-hash 示例 |
| `SampleScenario.scala` / `PrivacyRecommendationDemo.scala` | 攻击场景和正常用户效用演示 |
| `LoadBenchmark.scala` | 隐私评分核心吞吐冒烟测试 |

---

## 识别哪些泄露信号

`LeakDetector` 输出稳定的风险码和 `[0,1]` 风险分。主要规则：

| 风险码 | 触发信号 | 默认动作 |
|---|---|---|
| `single_identifier_oracle` | 新/低信任/稀疏通讯录账号，且通讯录证据匿名度或 witness 数小于 `k` | 通讯录大幅降权；缺少独立佐证则拒绝 |
| `thin_contact_cohort` | 普通账号的通讯录共现队列小于 k | 降权并标记集中度 |
| `sensitive_second_order_contact_path` | 通讯录入口经 pivot 走到医疗、法律、前任、匿名身份等敏感边 | 高风险；低匿名度时可直接 critical reject |
| `thin_mutual_friend_set` | 只有 1–2 个共同好友，可能确认隐藏边 | 不展示精确共同好友数；要求其他信号 |
| `rare_geolocation_colocation` | 稀有地理桶、小匿名集、过敏感地点或短时间窗共现 | 地理粗化、降权、要求非地理佐证 |
| `behavioral_relationship_side_channel` | 主页访问、停留、互动等行为单独支撑候选 | 聚合行为；禁止单源排到高位 |
| `anonymous_account_deanonymization` | 匿名号与通讯录/精确地理证据关联 | critical reject 或强佐证 + 强 cap |
| `sockpuppet_device_graph` | 同设备/设备图存在大量账号 | 提高信任门槛、降低二阶传播 |
| `identifier_probing_pattern` | 短时间大量上传手机号/邮箱探测 | 风控验证、频控或锁定严格策略 |
| `single_signal_dominance` | 一个信号族份额过高或 HHI 过高 | 触发 cap；高风险上下文要求佐证 |

风险分使用 noisy-OR 合并，便于解释“哪些规则共同导致拦截”，而不是不可审计的黑盒分数。内部审计可以保存 reason code；面向用户的 `PublicReason` 只返回泛化文案，例如“有共同好友”或“为保护关系隐私，不展示推荐理由”，绝不返回敏感关系类型或精确证据路径。

---

## 限制单一信号主导

`SignalBalancer` 有四层约束：

1. **同族聚合**：50 个共同好友路径仍然是一个 `MutualFriends` 信号族，不能伪装成 50 个独立信号。
2. **边际衰减**：`1 - exp(-2.2 * relevance)`，避免大量弱证据线性堆叠成确定性。
3. **绝对上限**：只有一个信号族时，分数不能超过 `singleSignalScoreCap`（默认 `0.58`）。
4. **相对份额 cap + HHI**：多信号时最大份额不能超过 `maxSignalShare`（默认 `0.55`），HHI 超过 `maxHhi` 会产生集中度风险标记。

对新注册、低信任、通讯录极小的账号，通讯录权重再乘 `freshContactWeightMultiplier`（默认 `0.35`；`PrivacyPolicy.lockedDown` 为 `0.15`）。

这不会删除所有单信号推荐。例如一个使用多年、信任度高、通讯录完整的用户，上传了正常联系人的手机号，仍可得到分数为 `0.58` 的 capped contact-only 推荐；但新小号的唯一手机号 oracle 无法单独沿敏感二阶路径产出推荐。

---

## 用户如何排除关系

`UserPrivacySettings` 支持：

```scala
UserPrivacySettings(
  userId = alice,
  blockedUserIds = Set(dangerousPerson),
  dismissedUserIds = Set(annoyingCandidate),
  shieldedUserIds = Set(therapist, ex, anonymousAlt),
  excludedRelationCodes = Set("ex_partner"),
  excludedSensitivityCodes = Set("healthcare", "anonymous_identity"),
  disabledSignalKinds = Set(SignalKind.GeoColocation)
)
```

语义：

- `blockedUserIds`：双向生效，任一端拉黑都不推荐。
- `dismissedUserIds`：只对当前 viewer 生效。
- `shieldedUserIds`：这段关系的 edge/path 完全不得进入推荐计算。
- `excludedRelationCodes`：按关系类型排除，例如所有 `therapist`、`ex_partner`。
- `excludedSensitivityCodes`：按敏感类别排除，例如医疗、法律财务、匿名身份；hydration 出的 viewer-candidate 关系标签可在打分前直接硬过滤。
- `disabledSignalKinds`：用户可关闭通讯录、地理或行为信号。

`ExclusionResolver.filterPath` 会在打分前删除不合法路径，而不是仅在最终结果里隐藏。这样被排除关系不会通过分数、排序、解释或 A/B 指标间接影响结果。关系两端任一方设置的 shield/类别退出都会生效。

生产环境中这些设置应存放在按 `UserId` 分片的强一致 KV / 隐私设置服务中，推荐 worker 本地缓存并通过版本号或 invalidation 保证拉黑/屏蔽在承诺 SLA 内生效。

---

## 每天几亿用户怎么做

核心原则是：**隐私逻辑不能要求扫描全图，也不能在热点 key 上聚合超大列表。**

### 离线/近线召回

- 通讯录：手机号 E.164 规范化后使用 KMS pepper 做 HMAC-SHA256；构建 `contact_hash -> user_id` 的分片倒排。pepper 轮换期间双写新旧 key。
- 共同好友：图邻接按 user 分片，离线预计算高频 top-N mutual friends；在线只做有界交集。
- 地理：使用粗粒度 geohash/S2 cell 和时间窗口，稀有桶合并到更粗桶，避免“某心理诊所同一小时只有两人”。
- 行为：聚合为兴趣/群组/互动强度，不把原始 profile view 事件直接送入排序。
- 所有源都有 per-source top-N 和总候选上限；热点 contact hash/geo cell 使用 salting、截断和匿名度阈值。

### 分布式执行

`ShardedBatchJob` 已实现可嵌入的分区形态：

```scala
val job = new ShardedBatchJob(
  pipeline,
  profileLookup = profiles.get,          // 生产：分片 KV / broadcast + RPC
  recaller = distributedRecaller,        // 生产：各信号倒排/预计算 top-N
  sink = recommendationSink,             // 生产：KV / Kafka / 数仓
  BatchJobConfig(shardCount = 256, shardIndex = partitionId, topK = 30)
)

stats = job.runShard(userIdIterator)
```

在 Spark 中，每个 partition 可直接构造一次 KV-backed lookup/recaller，然后调用相同逻辑：

```scala
userIds
  .repartition(col("user_id")) // 或显式 hash partition
  .rdd
  .mapPartitions { iter =>
    val job = ShardedJobFactory.createPartitionJob(partitionContext)
    job.runShard(iter.map(row => UserId(row.getAs[String]("user_id"))))
    Iterator.empty
  }
```

Flink 中则可放在 `KeyedProcessFunction` / windowed aggregate 后。关键是把设置和边数据按 key co-locate，避免每个候选都发起跨分片 RPC。

### 复杂度与生产依赖

设每用户有界召回候选数为 `C`、Top-K 为 `K`：

- 时间：`O(C log K)`，因为每个候选独立评估，Top-K 使用有界堆。
- 内存：`O(K + per-source recall cap + batch settings cache)`，不依赖总用户数。
- 水平扩展：按 `UserId` hash 分片；增加 executor/partition 即可提高吞吐。
- 热点治理：超大通讯录、热门地理桶、热门 contact hash 必须截断/加盐/粗化；resharding 应使用一致性哈希或预留足够虚拟节点。
- 隐私设置读取：生产实现应在分区内批量 `mget`/pipeline 预取 viewer、candidate 和路径端点设置，并做短 TTL 本地缓存与负缓存；不能在每条 edge 上发起一次强一致 KV RPC。
- 故障隔离：`ShardedBatchJob` 会捕获单 viewer 的 KV/RPC/sink 异常并计入 `failedViewers`；生产 sink/调度器必须重试或写入死信队列，不能静默丢弃。
- 路径 hydration：如果证据只有 `via=Alice` 而没有物化边，核心会以 `unresolved_traversal_path` fail-closed；召回层必须补齐边或提供合法的聚合边视图。
- 同族合并：同一 `SignalKind` 的多条召回行会先 `SignalMerger.mergeByKind`，避免把通讯录拆成多行绕过份额/HHI cap。

### 推荐效果指标

隐私阈值不能只由安全团队拍脑袋。建议至少建立：

- 安全指标：已知攻击语料拦截率、二阶敏感路径使用率、风险码分布、人工红队发现数。
- 效用指标：friend accept、follow-back、有效会话、次留、推荐覆盖率、NDCG/Recall@K、人均刷新结果数。
- 约束指标：score retention、单信号份额、HHI、各 source 出选率。
- 生态指标：不同账号年龄/国家/隐私设置人群的覆盖损失，避免阈值伤害新用户或小众社群。

`OfflinePolicyEvaluator` 演示了选择规则：

```text
先要求 attackBlockRate = 100%
再在候选策略中最大化 usefulRetention / 有效推荐分数
```

上线流程应是：历史图谱离线 replay → 攻击 fixture + 正常转化标签评估 → 小流量 A/B → 分人群 guardrail → 全量。阈值按市场、账号信任等级和数据密度可配置，不应全局硬编码。

---

## 明确的边界和后续增强

这个参考实现刻意保持零外部依赖，便于阅读和测试；生产化还需要：

1. **敏感关系分类器**：结合用户显式标签、私聊/日历/位置类别、联系人 label 等做隐私分级；分类结果本身要最小化存储和访问控制。
2. **更强的风控**：设备/IP 图、验证码、手机号验证、上传频控、批量注册识别；管道内规则只能消费风控结论。
3. **差分隐私/队列匿名**：对“多少共同联系人”等计数加入噪声或只展示粗桶，降低精确 oracle。
4. **在线探测防护**：推荐列表随机化、最小展示延迟、删除/屏蔽设置的传播 SLA、对自动化观察的异常检测。
5. **数据最小化**：通讯录哈希短期保留、定期删除、禁止明文手机号入日志/数仓；审计日志加密且限权。
6. **隐私验收测试**：把安全团队的真实攻击路径沉淀成回归 fixture，任何新信号源接入都必须经过隐私评审和同一套 gate。
