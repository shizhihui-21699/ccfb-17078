package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OfflinePolicyEvaluatorSpec extends AnyFunSuite with Matchers {
  import SampleScenario._

  test("policy replay reports complete attack blocking while retaining useful matches") {
    val sensitive = attackCandidates.filter(c =>
      c.id == therapist || c.id == ex || c.id == anonymousAlt
    )
    val useful = normalCandidates.filter(c =>
      c.id == oldFriend || c.id == strongMatch || c.id == twoSignalFriend
    )

    val cases =
      sensitive.map(EvaluationCase(attacker, _, useful = false, attackProbe = true)) ++
        useful.map(EvaluationCase(normalUser, _, useful = true))

    val evaluation = OfflinePolicyEvaluator.evaluate(PrivacyPolicy(), exclusionResolver(), cases)

    evaluation.attackBlockRate shouldBe 1.0
    evaluation.attackBlocked shouldBe 3
    evaluation.usefulRetention shouldBe 1.0
    evaluation.usefulAccepted shouldBe 3
    evaluation.privacySafe shouldBe true
  }

  test("policy selection only chooses among corpora that block all labelled attacks") {
    val cases = Vector(
      EvaluationCase(
        attacker,
        attackCandidates.find(_.id == therapist).get,
        useful = false,
        attackProbe = true
      ),
      EvaluationCase(
        normalUser,
        normalCandidates.find(_.id == strongMatch).get,
        useful = true
      )
    )

    val selected = OfflinePolicyEvaluator.selectBest(
      Vector(PrivacyPolicy(), PrivacyPolicy.lockedDown),
      exclusionResolver(),
      cases
    )

    selected shouldBe defined
    selected.get.privacySafe shouldBe true
    selected.get.usefulRetention shouldBe 1.0
  }
}
