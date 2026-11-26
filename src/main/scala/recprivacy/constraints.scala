package recprivacy

/** 隐私约束:对单个候选做 O(1) 判定。
  * 所有需要的图结构信息都必须预先聚合进 SourceUserFeatures /
  * TargetUserFeatures / SignalEvidence,保证在每天几亿用户 × 数百候选的
  * 规模下,约束求值只是几次哈希查找和算术运算,可以内联进现有的
  * 候选打分管道(Spark/Flink map 算子)而不增加 shuffle。
  */
trait PrivacyConstraint:
  def name: String
  def evaluate(
      source: SourceUserFeatures,
      candidate: Candidate,
      target: TargetUserFeatures
  ): Verdict

/** 约束 1 —— 最小归因约束(解释 k-匿名)。
  *
  * 威胁模型:攻击者注册小号,只上传受害者一个人的号码。此时任何
  * "通讯录来源"的推荐都能被唯一归因到受害者身上 —— 推荐列表本身就
  * 泄露了"这个号码的主人认识谁"。
  *
  * 规则:当接收方的通讯录上传数 < k 时,纯通讯录来源、且无其他信号
  * 佐证的候选被抑制;有佐证的降权。这样一条推荐至少需要 k 个独立
  * 上传记录或一个非通讯录信号才能"解释",攻击者无法反推出具体关系。
  */
final case class MinimumAttributionConstraint(
    k: Int = 5,
    corroboratedDemotion: Double = 0.5
) extends PrivacyConstraint:
  val name = "minimum-attribution"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    if !candidate.hasSignal(Signal.Contacts) then Allow
    else if source.uploadedContactCount >= k then Allow
    else if candidate.hasNonContactCorroboration then
      Demote(corroboratedDemotion,
        s"contact list too small (${source.uploadedContactCount} < $k), corroborated candidate demoted")
    else
      Suppress(s"contact list too small (${source.uploadedContactCount} < $k): recommendation would be uniquely attributable")

/** 约束 2 —— 单信号主导约束。
  *
  * 正常社交关系通常是多信号的(共同好友 + 行为 + 地理)。分数被单一
  * 信号垄断(尤其通讯录)的候选,正是"刻意隐藏的关系"的典型形态:
  * 病人与医生没有共同好友、没有互动,只有一条通讯录边。
  *
  * 规则:主导信号占比超过 softLimit 降权;超过 hardLimit 且为通讯录
  * 主导时抑制。多信号候选天然免疫,因此对正常推荐影响很小。
  */
final case class SignalDominanceConstraint(
    softLimit: Double = 0.8,
    hardLimit: Double = 0.95,
    demotion: Double = 0.4
) extends PrivacyConstraint:
  val name = "signal-dominance"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    val (dom, share) = candidate.dominant
    if share < softLimit then Allow
    else if dom == Signal.Contacts && share >= hardLimit then
      Suppress(f"contact signal dominates ${share * 100}%.0f%% of score (>= ${hardLimit * 100}%.0f%%)")
    else
      Demote(demotion, f"signal $dom dominates ${share * 100}%.0f%% of score")

/** 约束 3 —— 通讯录边互惠性约束。
  *
  * 真实好友关系里双方互存号码的比例很高;病人存了医生的号码、
  * 医生却不会存病人的。单向通讯录边是敏感关系泄露的主要载体。
  *
  * 规则:纯单向通讯录边且无其他佐证 → 抑制;有佐证 → 降权。
  */
final case class ContactReciprocityConstraint(
    demotion: Double = 0.5
) extends PrivacyConstraint:
  val name = "contact-reciprocity"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    if !candidate.contactIsOneWayOnly then Allow
    else if candidate.hasNonContactCorroboration then
      Demote(demotion, "one-way contact edge, corroborated by other signals")
    else
      Suppress("one-way contact edge with no corroborating signal")

/** 约束 4 —— 单桥暴露约束。
  *
  * 如果接收方与候选之间的全部共同好友路径都汇聚到同一个中间人
  * (比如都只能通过受害者本人连通),那么这条推荐会把"你们共同认识
  * 某一个人"这一事实暴露出来 —— 这正是小号攻击里"前任/匿名小号"
  * 被暴露的机制。
  *
  * 规则:共同好友证据的全部中间人少于 minBridges 个、且没有其他
  * 独立信号时降权;中间人为 1 且该候选同时是单向通讯录边时抑制。
  */
final case class BridgeExposureConstraint(
    minBridges: Int = 2,
    demotion: Double = 0.5
) extends PrivacyConstraint:
  val name = "bridge-exposure"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    val bridges = candidate.allBridgingNodes
    if !candidate.hasSignal(Signal.MutualFriends) then Allow
    else if bridges.size >= minBridges then Allow
    else if bridges.size <= 1 && candidate.contactIsOneWayOnly then
      Suppress(s"all paths funnel through ${bridges.size} bridging node(s) and contact edge is one-way")
    else
      Demote(demotion, s"only ${bridges.size} independent bridging node(s)")

/** 约束 5 —— 隐秘关系枢纽约束。
  *
  * 心理医生、律师、社工等职业节点在图上有可识别的统计形态:
  * 通讯录入度高、互存率极低、上传者彼此互不相连。对这些节点,
  * 任何通讯录来源的推荐都可能暴露"某人是其客户/病人"。
  *
  * 规则:目标是隐秘枢纽、且候选证据仅为通讯录来源 → 抑制。
  * 有真实社交信号佐证的(医生自己的朋友)不受影响。
  */
final case class SensitiveHubConstraint() extends PrivacyConstraint:
  val name = "sensitive-hub"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    if !target.isHiddenTieHub() then Allow
    else if candidate.hasNonContactCorroboration then Allow
    else Suppress(s"target ${target.userId} matches hidden-tie hub pattern (in-degree ${target.contactInDegree}, " +
      f"reciprocal ${target.reciprocalRatio * 100}%.0f%%)")

/** 约束 6 —— 用户排除约束(用户控制权)。
  *
  * 用户可以把某些关系(前任、小号、特定联系人)加入排除列表,
  * 双方都不再出现在对方的推荐里。这是硬约束,不受抑制预算限制,
  * 也不参与降权 —— 用户明确表达过的意愿必须无条件执行。
  */
final case class UserExclusionConstraint() extends PrivacyConstraint:
  val name = "user-exclusion"

  def evaluate(source: SourceUserFeatures, candidate: Candidate, target: TargetUserFeatures): Verdict =
    if source.exclusions.contains(candidate.userId) then
      Suppress(s"user ${source.userId} excluded relationship with ${candidate.userId}")
    else Allow
