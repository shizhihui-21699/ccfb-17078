package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AttackScenarioSpec extends AnyFunSuite with Matchers {
  import SampleScenario._

  test("naive ranking reproduces the leak: hidden therapist, ex and anonymous alt rank highly") {
    val ranked = NaiveBaseline.rank(attackCandidates).map(_._1)
    ranked.take(3) should contain allOf (therapist, ex, anonymousAlt)
  }

  test("privacy pipeline never recommends sensitive relationships from the one-contact sockpuppet") {
    val result = pipeline().processViewer(attacker, attackCandidates, topK = 20)

    result.recommendedIds should not contain therapist
    result.recommendedIds should not contain ex
    result.recommendedIds should not contain anonymousAlt

    val rejectedIds = result.rejected.map(_.candidateId).toSet
    rejectedIds should contain allOf (therapist, ex, anonymousAlt)

    result.decisions.collect {
      case r @ RejectedCandidate(`therapist`, _, _, _) => r
    }.head.reasonCodes should contain("no_legal_evidence_after_exclusions")
  }

  test("non-sensitive corroborated candidates can survive under a strict score cap") {
    val result = pipeline().processViewer(attacker, attackCandidates, topK = 20)
    val casey = result.recommendations.find(_.candidateId == colleague)

    casey shouldBe defined
    casey.get.score should be <= PrivacyPolicy().sensitiveScoreCap + 1e-9
    casey.get.reason shouldBe PublicReason.NoExplanation
  }

  test("a normal high-trust user retains useful contact and multi-signal recommendations") {
    val result = pipeline().processViewer(normalUser, normalCandidates, topK = 20)

    result.recommendedIds should contain allOf (oldFriend, strongMatch, twoSignalFriend)
    result.recommendedIds should not contain behaviorOnly

    val leo = result.recommendations.find(_.candidateId == oldFriend).get
    leo.score should be <= PrivacyPolicy().singleSignalScoreCap + 1e-9
    leo.contributions should have size 1
  }
}
