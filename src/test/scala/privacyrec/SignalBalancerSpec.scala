package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SignalBalancerSpec extends AnyFunSuite with Matchers {
  private val policy = PrivacyPolicy()
  private val trustedViewer = ViewerProfile(UserId("trusted"), 500, 0.9, 200, verifiedPhone = true)
  private val freshViewer = ViewerProfile(UserId("fresh"), 0, 0.1, 1)

  private def signal(kind: SignalKind, strength: Double): Signal =
    Signal(kind, Vector(EvidencePath(s"$kind-$strength", rawStrength = strength)))

  test("a single signal cannot exceed the absolute single-source cap") {
    val score = SignalBalancer.balance(
      Vector(signal(SignalKind.ContactBook, 1.0)),
      trustedViewer,
      policy
    )

    score.total shouldBe <=(policy.singleSignalScoreCap + 1e-9)
    score.contributions.head.singleKind shouldBe true
    score.hhi shouldBe 1.0 +- 1e-9
  }

  test("relative cap prevents one family from dominating multi-signal scoring") {
    val score = SignalBalancer.balance(
      Vector(
        signal(SignalKind.ContactBook, 1.0),
        signal(SignalKind.GeoColocation, 0.25),
        signal(SignalKind.SocialBehavior, 0.25)
      ),
      trustedViewer,
      policy
    )

    val maxShare = score.contributions.map(_.share).max
    maxShare shouldBe <=(policy.maxSignalShare + 0.01)
    score.dominanceCapped shouldBe true
  }

  test("fresh sparse accounts receive a strong contact-source discount") {
    val signals = Vector(signal(SignalKind.ContactBook, 0.95))
    val fresh = SignalBalancer.balance(signals, freshViewer, policy)
    val trusted = SignalBalancer.balance(signals, trustedViewer, policy)

    fresh.total should be < trusted.total
    fresh.contributions.head.contribution shouldBe
      SignalBalancer.saturation(0.95) * policy.freshContactWeightMultiplier +- 1e-9
  }

  test("duplicate rows of one kind are merged before share and HHI caps") {
    val merged = SignalMerger.mergeByKind(
      Vector(
        signal(SignalKind.ContactBook, 1.0),
        signal(SignalKind.ContactBook, 1.0),
        signal(SignalKind.GeoColocation, 0.2)
      )
    )

    merged should have size 2

    val score = SignalBalancer.balance(merged, trustedViewer, policy)
    score.contributions should have size 2
    val contactShare = score.contributions.find(_.kind == SignalKind.ContactBook).get.share
    contactShare shouldBe <=(policy.maxSignalShare + 0.02)
  }

  test("witness keys are de-duplicated across evidence paths") {
    val signal = Signal(
      SignalKind.MutualFriends,
      Vector(
        EvidencePath("one", rawStrength = 0.4, independentWitnesses = 9, witnessKeys = Set("w1", "w2")),
        EvidencePath("two", rawStrength = 0.4, independentWitnesses = 9, witnessKeys = Set("w2", "w3"))
      )
    )

    SignalAggregator.aggregate(signal).get.independentWitnesses shouldBe 3
  }

  test("many paths of one kind remain one signal family") {
    val bundled = Signal(
      SignalKind.MutualFriends,
      (1 to 50).map(i => EvidencePath(s"mf-$i", rawStrength = 0.4, independentWitnesses = 1)).toVector
    )
    val score = SignalBalancer.balance(Vector(bundled), trustedViewer, policy)

    score.contributions should have size 1
    score.total shouldBe <=(policy.singleSignalScoreCap + 1e-9)
  }
}
