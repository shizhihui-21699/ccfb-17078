package recprivacy

/** 复现并修复"小号泄露敏感关系"的场景。
  *
  * 场景:受害者 V(id=1)。攻击者注册小号 S,只上传了 V 的手机号。
  * 朴素管道会把"与 V 的号码有通讯录关联的人"推给 S:
  *   - V 的心理医生 T(单向边:病人存医生,医生不存病人;T 是隐秘枢纽)
  *   - V 的前任 E(分手后删了共同好友痕迹,只剩单向通讯录边)
  *   - V 的匿名小号 A(只有 V 自己存了这个号码)
  *   - 以及正常候选:V 的同事 C(双向通讯录 + 3 个共同好友 + 行为信号)
  */
object Main:

  private val victim = 1L

  def main(args: Array[String]): Unit =
    val attacker = SourceUserFeatures(
      userId = 100L,
      accountAgeDays = 0,
      uploadedContactCount = 1 // 小号只上传了受害者一个号码
    )

    val candidates = List(
      // 心理医生:单向通讯录边,隐秘关系枢纽(很多"客户"存了他,互不相识)
      Candidate(2L, List(
        SignalEvidence(Signal.Contacts, weight = 0.9, reciprocal = false, bridgingNodes = Set(victim))
      )) -> TargetUserFeatures(2L, contactInDegree = 300, reciprocalInDegree = 12, uploaderMutualConnectivity = 0.01),

      // 前任:单向通讯录边 + 唯一中间人是受害者本人
      Candidate(3L, List(
        SignalEvidence(Signal.Contacts, weight = 0.7, reciprocal = false),
        SignalEvidence(Signal.MutualFriends, weight = 0.2, bridgingNodes = Set(victim))
      )) -> TargetUserFeatures(3L, contactInDegree = 45, reciprocalInDegree = 30, uploaderMutualConnectivity = 0.3),

      // 匿名小号:只有受害者一个人存了它
      Candidate(4L, List(
        SignalEvidence(Signal.Contacts, weight = 0.8, reciprocal = false)
      )) -> TargetUserFeatures(4L, contactInDegree = 1, reciprocalInDegree = 0, uploaderMutualConnectivity = 0.0),

      // 正常同事:双向通讯录 + 3 个共同好友 + 行为信号
      Candidate(5L, List(
        SignalEvidence(Signal.Contacts, weight = 0.4, reciprocal = true),
        SignalEvidence(Signal.MutualFriends, weight = 0.4, bridgingNodes = Set(11L, 12L, 13L)),
        SignalEvidence(Signal.Behavior, weight = 0.2)
      )) -> TargetUserFeatures(5L, contactInDegree = 80, reciprocalInDegree = 60, uploaderMutualConnectivity = 0.4)
    )

    println("=== 朴素管道(无隐私约束)===")
    candidates.sortBy(-_._1.totalScore).foreach { (c, _) =>
      println(f"  候选 ${c.userId}  分数 ${c.totalScore}%.2f  <-- 会被推荐")
    }

    val ranker = PrivacyAwareRanker(
      constraints = List(
        UserExclusionConstraint(),
        MinimumAttributionConstraint(k = 5),
        ContactReciprocityConstraint(),
        SignalDominanceConstraint(),
        BridgeExposureConstraint(),
        SensitiveHubConstraint()
      ),
      policy = EnforcementPolicy(suppressionBudget = 0.75, minListSize = 1)
    )

    println("\n=== 隐私约束管道 ===")
    val (ranked, metrics) = ranker.rank(attacker, candidates, topK = 10)
    println("最终推荐列表:")
    ranked.foreach { sc =>
      println(f"  候选 ${sc.candidate.userId}  基础分 ${sc.baseScore}%.2f  最终分 ${sc.finalScore}%.2f")
    }
    println("\n被约束命中的候选:")
    candidates.map(_._1).filterNot(c => ranked.exists(_.candidate.userId == c.userId)).foreach { c =>
      println(s"  候选 ${c.userId}  已抑制")
    }
    println(s"\n指标: 评估 ${metrics.evaluated}, 放行 ${metrics.allowed}, " +
      s"降权 ${metrics.demoted}, 抑制 ${metrics.suppressed}, 超预算 ${metrics.budgetOverflow}")
    println("按约束分桶: " + metrics.byConstraint.toList.sortBy(-_._2)
      .map((n, c) => s"$n=$c").mkString(", "))
