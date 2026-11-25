package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PrivacyGateSpec extends AnyFunSuite with Matchers {
  private val pipeline = SampleScenario.pipeline()
  private val openPipeline = new PrivacyPipeline(
    PrivacyPolicy(),
    new ExclusionResolver(new InMemoryPrivacySettingsLookup())
  )

  private def oneSignalCandidate(
      id: String,
      kind: SignalKind,
      strength: Double = 0.95,
      anonymitySet: Int = 1,
      independentWitnesses: Int = 1,
      edges: Vector[GraphEdge] = Vector.empty,
      via: Vector[UserId] = Vector.empty,
      pseudonymity: Pseudonymity = Pseudonymity.RealName
  ): RawCandidate =
    RawCandidate(
      CandidateProfile(UserId(id), pseudonymity),
      Vector(
        Signal(
          kind,
          Vector(
            EvidencePath(
              s"path-$id",
              via = via,
              edges = edges,
              rawStrength = strength,
              independentWitnesses = independentWitnesses,
              anonymitySet = anonymitySet
            )
          )
        )
      )
    )

  test("fresh sparse account with one contact oracle cannot rank a candidate alone") {
    val fresh = ViewerProfile(UserId("probe"), 0, 0.1, 1)
    val candidate = oneSignalCandidate("target", SignalKind.ContactBook)

    pipeline.evaluate(fresh, candidate) match {
      case r: RejectedCandidate =>
        r.stage shouldBe "privacy_gate"
        r.reasonCodes.exists(code =>
          code == "critical_leakage_risk" || code == "insufficient_independent_corroboration"
        ) shouldBe true
        r.risk.get.codes should contain("single_identifier_oracle")
      case other => fail(s"expected rejection, got $other")
    }
  }

  test("established user can still receive a capped contact-only recommendation") {
    val trusted = ViewerProfile(UserId("trusted"), 600, 0.9, 300, verifiedPhone = true)
    val candidate = oneSignalCandidate("old-phone-friend", SignalKind.ContactBook, 0.9)

    pipeline.evaluate(trusted, candidate) match {
      case a: AcceptedCandidate =>
        a.score shouldBe <=(PrivacyPolicy().singleSignalScoreCap + 1e-9)
      case other => fail(s"expected acceptance, got $other")
    }
  }

  test("aggregate-only relationship evidence requires another independent signal family") {
    val alice = UserId("alice")
    val cousin = UserId("cousin")
    val trusted = ViewerProfile(UserId("trusted2"), 400, 0.8, 180)
    val edge = GraphEdge(alice, cousin, RelationLabel.Family, hidden = false)
    val candidate = oneSignalCandidate(
      "cousin",
      SignalKind.MutualFriends,
      strength = 0.7,
      anonymitySet = 40,
      independentWitnesses = 8,
      edges = Vector(edge),
      via = Vector(alice)
    )

    openPipeline.evaluate(trusted, candidate) match {
      case r: RejectedCandidate =>
        r.reasonCodes should contain("insufficient_independent_corroboration")
      case other => fail(s"expected aggregate-only path to require corroboration, got $other")
    }
  }

  test("a traversal path without hydrated edges fails closed instead of bypassing exclusions") {
    val alice = UserId("alice-via-only")
    val therapist = UserId("therapist-via-only")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(
        alice -> UserPrivacySettings(
          alice,
          shieldedUserIds = Set(therapist),
          excludedSensitivityCodes = Set("healthcare")
        )
      )
    )
    val localPipeline = new PrivacyPipeline(PrivacyPolicy(), new ExclusionResolver(lookup))
    val probe = ViewerProfile(UserId("probe-via"), 0, 0.1, 1)
    val candidate = RawCandidate(
      CandidateProfile(therapist),
      Vector(
        Signal(
          SignalKind.ContactBook,
          Vector(EvidencePath("via-only", via = Vector(alice), rawStrength = 0.99))
        )
      )
    )

    localPipeline.evaluate(probe, candidate) match {
      case r: RejectedCandidate =>
        r.reasonCodes should contain("no_legal_evidence_after_exclusions")
      case other => fail(s"expected unresolved traversal to be dropped, got $other")
    }
  }

  test("thin AggregateOnly relationship evidence needs stronger corroboration even with Geo") {
    val alice = UserId("alice-family")
    val cousin = UserId("cousin-thin")
    val trusted = ViewerProfile(UserId("trusted-family"), 500, 0.9, 220, verifiedPhone = true)
    val familyEdge = GraphEdge(alice, cousin, RelationLabel.Family)
    val candidate = RawCandidate(
      CandidateProfile(cousin),
      Vector(
        Signal(
          SignalKind.MutualFriends,
          Vector(
            EvidencePath(
              "thin-family-path",
              via = Vector(alice),
              edges = Vector(familyEdge),
              rawStrength = 0.75,
              independentWitnesses = 2,
              anonymitySet = 2
            )
          )
        ),
        Signal(
          SignalKind.GeoColocation,
          Vector(EvidencePath("broad-geo", rawStrength = 0.55, independentWitnesses = 20, anonymitySet = 300))
        )
      )
    )

    openPipeline.evaluate(trusted, candidate) match {
      case r: RejectedCandidate =>
        r.reasonCodes should contain("thin_aggregate_only_cohort")
      case other => fail(s"expected thin aggregate-only gate, got $other")
    }
  }

  test("anonymous account linked by thin contact evidence is critically risky") {
    val trusted = ViewerProfile(UserId("trusted3"), 300, 0.8, 120)
    val candidate = oneSignalCandidate(
      "secret-alt",
      SignalKind.ContactBook,
      pseudonymity = Pseudonymity.Anonymous
    )

    openPipeline.evaluate(trusted, candidate) match {
      case r: RejectedCandidate =>
        r.stage shouldBe "privacy_gate"
        r.reasonCodes should contain("critical_leakage_risk")
        r.risk.get.codes should contain("anonymous_account_deanonymization")
      case other => fail(s"expected critical rejection, got $other")
    }
  }

  test("hard block wins before scoring") {
    val me = UserId("me-block")
    val viewer = ViewerProfile(me, 200, 0.8, 80)
    val blocked = UserId("blocked-person")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(me -> UserPrivacySettings(me, blockedUserIds = Set(blocked)))
    )
    val localPipeline = new PrivacyPipeline(PrivacyPolicy(), new ExclusionResolver(lookup))
    val candidate = oneSignalCandidate(blocked.value, SignalKind.MutualFriends, independentWitnesses = 20, anonymitySet = 20)

    localPipeline.evaluate(viewer, candidate) match {
      case r: RejectedCandidate => r.stage shouldBe "hard_exclusion"
      case other => fail(s"expected hard exclusion, got $other")
    }
  }
}
