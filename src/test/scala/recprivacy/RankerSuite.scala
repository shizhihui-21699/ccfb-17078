package recprivacy

class RankerSuite extends munit.FunSuite:

  private val ranker = PrivacyAwareRanker(
    constraints = List(
      UserExclusionConstraint(),
      MinimumAttributionConstraint(k = 5),
      ContactReciprocityConstraint(),
      SignalDominanceConstraint(),
      BridgeExposureConstraint(),
      SensitiveHubConstraint()
    ),
    policy = EnforcementPolicy(suppressionBudget = 1.0, minListSize = 1)
  )

  private val attacker = SourceUserFeatures(100L, accountAgeDays = 0, uploadedContactCount = 1)

  private val therapist = Candidate(2L, List(
    SignalEvidence(Signal.Contacts, 0.9, reciprocal = false)
  )) -> TargetUserFeatures(2L, 300, 12, 0.01)

  private val ex = Candidate(3L, List(
    SignalEvidence(Signal.Contacts, 0.7, reciprocal = false),
    SignalEvidence(Signal.MutualFriends, 0.2, bridgingNodes = Set(1L))
  )) -> TargetUserFeatures(3L, 45, 30, 0.3)

  private val colleague = Candidate(5L, List(
    SignalEvidence(Signal.Contacts, 0.4, reciprocal = true),
    SignalEvidence(Signal.MutualFriends, 0.4, bridgingNodes = Set(11L, 12L, 13L)),
    SignalEvidence(Signal.Behavior, 0.2)
  )) -> TargetUserFeatures(5L, 80, 60, 0.4)

  test("小号攻击场景:敏感关系被抑制,正常候选保留") {
    val (ranked, metrics) = ranker.rank(attacker, List(therapist, ex, colleague), topK = 10)
    val kept = ranked.map(_.candidate.userId).toSet
    assert(kept.contains(5L), s"同事应保留,实际: $kept")
    assert(!kept.contains(2L), "心理医生应被抑制")
    assert(!kept.contains(3L), "前任应被抑制")
    assert(metrics.suppressed >= 2)
  }

  test("抑制预算:超预算的候选转为降权而不是消失") {
    // 预算 0.5 × 4 个候选 = 2 个抑制名额,构造 3 个高风险候选
    val risky = (0 until 3).toList.map { i =>
      Candidate(200L + i, List(SignalEvidence(Signal.Contacts, 0.9, reciprocal = false))) ->
        TargetUserFeatures(200L + i, 10, 1, 0.0)
    }
    val policy = EnforcementPolicy(suppressionBudget = 0.25, overflowDemotion = 0.1, minListSize = 1)
    val r = PrivacyAwareRanker(rankerConstraints, policy)
    val (ranked, metrics) = r.rank(attacker, risky, topK = 10)
    assert(metrics.budgetOverflow > 0, "应有超预算候选")
    // 超预算的候选以重度降权形式留在列表里,而不是被删除
    assert(ranked.exists(sc => sc.finalScore > 0 && sc.finalScore < sc.baseScore * 0.5))
  }

  test("用户排除不受抑制预算限制") {
    val src = attacker.copy(exclusions = Set(5L))
    val (ranked, _) = ranker.rank(src, List(colleague), topK = 10)
    assert(!ranked.exists(_.candidate.userId == 5L))
  }

  test("minListSize 回填:列表不会被打空") {
    val policy = EnforcementPolicy(suppressionBudget = 1.0, minListSize = 2)
    val r = PrivacyAwareRanker(rankerConstraints, policy)
    val many = (0 until 5).toList.map { i =>
      Candidate(300L + i, List(
        SignalEvidence(Signal.Contacts, 0.5, reciprocal = true),
        SignalEvidence(Signal.Behavior, 0.5)
      )) -> TargetUserFeatures(300L + i, 10, 8, 0.5)
    }
    val (ranked, _) = r.rank(attacker, many, topK = 1)
    assert(ranked.size >= 2, s"应回填到 minListSize,实际 ${ranked.size}")
  }

  private def rankerConstraints = List(
    UserExclusionConstraint(),
    MinimumAttributionConstraint(k = 5),
    ContactReciprocityConstraint(),
    SignalDominanceConstraint(),
    BridgeExposureConstraint(),
    SensitiveHubConstraint()
  )
