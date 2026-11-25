package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ExclusionResolverSpec extends AnyFunSuite with Matchers {
  test("block is enforced symmetrically even when only one side stored it") {
    val a = UserId("a")
    val b = UserId("b")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(a -> UserPrivacySettings(a, blockedUserIds = Set(b)))
    )
    val resolver = new ExclusionResolver(lookup)

    resolver.candidateHardRejection(a, b).map(_.reasonCodes) shouldBe Some(Vector("blocked"))
    resolver.candidateHardRejection(b, a).map(_.reasonCodes) shouldBe Some(Vector("blocked"))
  }

  test("viewer dismissal filters only that viewer's recommendation") {
    val me = UserId("me")
    val x = UserId("x")
    val other = UserId("other")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(me -> UserPrivacySettings(me, dismissedUserIds = Set(x)))
    )
    val resolver = new ExclusionResolver(lookup)

    resolver.candidateHardRejection(me, x) shouldBe defined
    resolver.candidateHardRejection(other, x) shouldBe empty
  }

  test("shielded relationship hard-filters the two endpoints and removes traversal paths") {
    val alice = UserId("alice")
    val bob = UserId("bob")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(alice -> UserPrivacySettings(alice, shieldedUserIds = Set(bob)))
    )
    val resolver = new ExclusionResolver(lookup)

    resolver.candidateHardRejection(alice, bob).map(_.reasonCodes) shouldBe
      Some(Vector("relationship_excluded"))
    resolver.candidateHardRejection(bob, alice).map(_.reasonCodes) shouldBe
      Some(Vector("relationship_excluded"))

    val path = EvidencePath(
      "p1",
      edges = Vector(GraphEdge(alice, bob, RelationLabel.ExPartner, hidden = false)),
      rawStrength = 0.9
    )

    resolver.filterPath(path).isLeft shouldBe true
  }

  test("relationship code and sensitivity category exclusions are honoured from either endpoint") {
    val patient = UserId("patient")
    val doctor = UserId("doctor")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(doctor -> UserPrivacySettings(doctor, excludedRelationCodes = Set("therapist")))
    )
    val resolver = new ExclusionResolver(lookup)
    val edge = GraphEdge(patient, doctor, RelationLabel.Therapist, hidden = false)

    val reasons = resolver.filterPath(EvidencePath("p", edges = Vector(edge), rawStrength = 1.0))
    reasons.isLeft shouldBe true

    val categoryLookup = new InMemoryPrivacySettingsLookup(
      Map(patient -> UserPrivacySettings(patient, excludedSensitivityCodes = Set("healthcare")))
    )
    val categoryResolver = new ExclusionResolver(categoryLookup)
    categoryResolver
      .filterPath(EvidencePath("p2", edges = Vector(edge), rawStrength = 1.0))
      .isLeft shouldBe true
  }

  test("hydrated relationship privacy tags enforce category opt-outs before scoring") {
    val me = UserId("me-tags")
    val therapist = UserId("tagged-therapist")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(me -> UserPrivacySettings(me, excludedSensitivityCodes = Set("healthcare")))
    )
    val resolver = new ExclusionResolver(lookup)

    val rejection = resolver.candidateHardRejection(
      me,
      therapist,
      relationshipPrivacyTags = Set("therapist", "healthcare")
    )

    rejection.map(_.reasonCodes) shouldBe Some(Vector("relationship_category_excluded"))
  }

  test("disabled signal kind is reported for the requesting viewer") {
    val me = UserId("me")
    val lookup = new InMemoryPrivacySettingsLookup(
      Map(me -> UserPrivacySettings(me, disabledSignalKinds = Set(SignalKind.GeoColocation)))
    )
    val resolver = new ExclusionResolver(lookup)

    resolver.signalDisabledByViewer(me, SignalKind.GeoColocation) shouldBe true
    resolver.signalDisabledByViewer(me, SignalKind.ContactBook) shouldBe false
  }
}
