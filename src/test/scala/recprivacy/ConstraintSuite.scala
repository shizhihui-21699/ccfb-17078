package recprivacy

class ConstraintSuite extends munit.FunSuite:

  private val normalSource = SourceUserFeatures(
    userId = 1L, accountAgeDays = 365, uploadedContactCount = 200
  )
  private val freshAltAccount = SourceUserFeatures(
    userId = 2L, accountAgeDays = 0, uploadedContactCount = 1
  )
  private val normalTarget = TargetUserFeatures(
    userId = 50L, contactInDegree = 60, reciprocalInDegree = 45, uploaderMutualConnectivity = 0.4
  )
  private val therapistTarget = TargetUserFeatures(
    userId = 51L, contactInDegree = 500, reciprocalInDegree = 10, uploaderMutualConnectivity = 0.005
  )

  private def contactOnly(reciprocal: Boolean, w: Double = 0.9) =
    Candidate(99L, List(SignalEvidence(Signal.Contacts, w, reciprocal)))

  private val multiSignal = Candidate(98L, List(
    SignalEvidence(Signal.Contacts, 0.3, reciprocal = true),
    SignalEvidence(Signal.MutualFriends, 0.4, bridgingNodes = Set(7L, 8L, 9L)),
    SignalEvidence(Signal.Behavior, 0.3)
  ))

  // ---- MinimumAttributionConstraint ----

  test("小号(上传数 < k)的纯通讯录候选被抑制") {
    val v = MinimumAttributionConstraint(k = 5).evaluate(freshAltAccount, contactOnly(true), normalTarget)
    assert(v.isInstanceOf[Suppress], v)
  }

  test("小号的多信号候选只降权不抑制") {
    val v = MinimumAttributionConstraint(k = 5).evaluate(freshAltAccount, multiSignal, normalTarget)
    assert(v.isInstanceOf[Demote], v)
  }

  test("正常大号(上传数 >= k)不受归因约束影响") {
    val v = MinimumAttributionConstraint(k = 5).evaluate(normalSource, contactOnly(true), normalTarget)
    assertEquals(v, Allow)
  }

  // ---- ContactReciprocityConstraint ----

  test("单向通讯录边无佐证被抑制") {
    val v = ContactReciprocityConstraint().evaluate(normalSource, contactOnly(false), normalTarget)
    assert(v.isInstanceOf[Suppress], v)
  }

  test("双向通讯录边放行") {
    val v = ContactReciprocityConstraint().evaluate(normalSource, contactOnly(true), normalTarget)
    assertEquals(v, Allow)
  }

  // ---- SignalDominanceConstraint ----

  test("通讯录占比 >= 95% 的候选被抑制") {
    val v = SignalDominanceConstraint().evaluate(normalSource, contactOnly(true, 1.0), normalTarget)
    assert(v.isInstanceOf[Suppress], v)
  }

  test("多信号候选不受主导约束影响") {
    val v = SignalDominanceConstraint().evaluate(normalSource, multiSignal, normalTarget)
    assertEquals(v, Allow)
  }

  // ---- BridgeExposureConstraint ----

  test("全部路径汇聚到单一中间人且通讯录单向时被抑制") {
    val c = Candidate(97L, List(
      SignalEvidence(Signal.Contacts, 0.5, reciprocal = false),
      SignalEvidence(Signal.MutualFriends, 0.5, bridgingNodes = Set(1L))
    ))
    val v = BridgeExposureConstraint().evaluate(normalSource, c, normalTarget)
    assert(v.isInstanceOf[Suppress], v)
  }

  test("多个独立中间人放行") {
    val v = BridgeExposureConstraint().evaluate(normalSource, multiSignal, normalTarget)
    assertEquals(v, Allow)
  }

  // ---- SensitiveHubConstraint ----

  test("隐秘枢纽的纯通讯录候选被抑制(心理医生场景)") {
    val v = SensitiveHubConstraint().evaluate(normalSource, contactOnly(false), therapistTarget)
    assert(v.isInstanceOf[Suppress], v)
  }

  test("隐秘枢纽自己的真实朋友(有非通讯录佐证)不受影响") {
    val v = SensitiveHubConstraint().evaluate(normalSource, multiSignal, therapistTarget)
    assertEquals(v, Allow)
  }

  test("普通目标不触发枢纽判定") {
    assert(!normalTarget.isHiddenTieHub())
    assert(therapistTarget.isHiddenTieHub())
  }

  // ---- UserExclusionConstraint ----

  test("用户排除的关系被无条件抑制") {
    val src = normalSource.copy(exclusions = Set(99L))
    val v = UserExclusionConstraint().evaluate(src, contactOnly(true), normalTarget)
    assert(v.isInstanceOf[Suppress], v)
  }
