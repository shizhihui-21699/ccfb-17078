package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PrivacyPolicySelectorSpec extends AnyFunSuite with Matchers {
  private val base = PrivacyPolicy()
  private val select = PrivacyPolicySelector.adaptive(base)

  test("trusted established users keep the utility-preserving baseline") {
    val trusted = ViewerProfile(UserId("trusted"), 800, 0.9, 400, verifiedPhone = true)
    select(trusted) shouldBe base
  }

  test("fresh sparse accounts receive tighter privacy caps") {
    val fresh = ViewerProfile(UserId("fresh"), 0, 0.1, 1)
    val selected = select(fresh)

    selected.version.value should include("fresh-sparse")
    selected.kAnonymity should be >= base.kAnonymity
    selected.singleSignalScoreCap should be < base.singleSignalScoreCap
    selected.minRecommendationScore shouldBe base.minRecommendationScore
  }

  test("established privacy-minimal users are not treated as sockpuppets solely for few contacts") {
    val privacyMinimal = ViewerProfile(UserId("minimal"), 900, 0.86, 1)
    select(privacyMinimal) shouldBe base
  }

  test("identifier probing switches to locked-down policy") {
    val prober = ViewerProfile(
      UserId("prober"),
      accountAgeDays = 3,
      trustScore = 0.2,
      contactBookSize = 2,
      recentIdentifierProbes = base.maxIdentifierProbesPerDay
    )

    select(prober) shouldBe PrivacyPolicy.lockedDown
  }
}
