package privacyrec

/** Minimal in-memory graph used by the runnable demo and tests. */
object SampleScenario {
  val attacker: ViewerProfile = ViewerProfile(
    id = UserId("sockpuppet-001"),
    accountAgeDays = 0,
    trustScore = 0.08,
    contactBookSize = 1,
    verifiedPhone = false,
    recentIdentifierProbes = 1,
    linkedAccountsOnDevice = 2
  )

  val alice: UserId = UserId("alice")
  val therapist: UserId = UserId("dr-kim-therapist")
  val ex: UserId = UserId("jordan-ex-partner")
  val anonymousAlt: UserId = UserId("alice-anon-alt")
  val colleague: UserId = UserId("casey-public-colleague")

  val normalUser: ViewerProfile = ViewerProfile(
    id = UserId("maya-normal-user"),
    accountAgeDays = 920,
    trustScore = 0.93,
    contactBookSize = 418,
    verifiedPhone = true,
    recentIdentifierProbes = 0,
    linkedAccountsOnDevice = 1
  )

  val oldFriend: UserId = UserId("leo-old-friend")
  val strongMatch: UserId = UserId("priya-multi-signal")
  val twoSignalFriend: UserId = UserId("noah-contact-mutual")
  val behaviorOnly: UserId = UserId("zara-behavior-only")

  def settingsLookup(): InMemoryPrivacySettingsLookup =
    new InMemoryPrivacySettingsLookup(
      Map(
        alice -> UserPrivacySettings(
          userId = alice,
          shieldedUserIds = Set(therapist, ex, anonymousAlt),
          excludedRelationCodes = Set(RelationLabel.ExPartner.code),
          excludedSensitivityCodes = Set(
            Sensitivity.Healthcare.code,
            Sensitivity.AnonymousIdentity.code
          )
        )
      )
    )

  def exclusionResolver(): ExclusionResolver = new ExclusionResolver(settingsLookup())

  def pipeline(policy: PrivacyPolicy = PrivacyPolicy()): PrivacyPipeline = {
    val resolver = exclusionResolver()
    new PrivacyPipeline(policy, resolver, PrivacyPolicySelector.adaptive(policy))
  }

  private def contactThroughAlice(
      candidate: UserId,
      relation: RelationLabel,
      hidden: Boolean,
      strength: Double
  ): Signal =
    Signal(
      SignalKind.ContactBook,
      Vector(
        EvidencePath(
          pathId = s"uploaded-alice-phone:${alice.value}->${candidate.value}",
          via = Vector(alice),
          edges = Vector(
            GraphEdge(attacker.id, alice, RelationLabel.PhoneContact),
            GraphEdge(alice, candidate, relation, hidden = hidden)
          ),
          rawStrength = strength,
          confidence = 1.0,
          independentWitnesses = 1,
          anonymitySet = 1
        )
      )
    )

  val attackCandidates: Vector[RawCandidate] = Vector(
    RawCandidate(
      CandidateProfile(therapist),
      Vector(contactThroughAlice(therapist, RelationLabel.Therapist, hidden = true, 0.98))
    ),
    RawCandidate(
      CandidateProfile(ex),
      Vector(contactThroughAlice(ex, RelationLabel.ExPartner, hidden = true, 0.96))
    ),
    RawCandidate(
      CandidateProfile(anonymousAlt, Pseudonymity.Anonymous),
      Vector(contactThroughAlice(anonymousAlt, RelationLabel.AnonymousAlt, hidden = true, 0.99))
    ),
    RawCandidate(
      CandidateProfile(colleague),
      Vector(
        contactThroughAlice(colleague, RelationLabel.Coworker, hidden = false, 0.72),
        Signal(
          SignalKind.GeoColocation,
          Vector(
            EvidencePath(
              "coarse-city-tile",
              rawStrength = 0.68,
              independentWitnesses = 5,
              anonymitySet = 240
            )
          )
        ),
        Signal(
          SignalKind.SocialBehavior,
          Vector(
            EvidencePath(
              "public-industry-group",
              rawStrength = 0.62,
              independentWitnesses = 18,
              anonymitySet = 86
            )
          )
        )
      )
    )
  )

  val normalCandidates: Vector[RawCandidate] = Vector(
    RawCandidate(
      CandidateProfile(oldFriend),
      Vector(
        Signal(
          SignalKind.ContactBook,
          Vector(
            EvidencePath(
              "direct-address-book-match",
              edges = Vector(GraphEdge(normalUser.id, oldFriend, RelationLabel.PhoneContact)),
              rawStrength = 0.91,
              independentWitnesses = 1,
              anonymitySet = 1
            )
          )
        )
      )
    ),
    RawCandidate(
      CandidateProfile(strongMatch),
      Vector(
        Signal(
          SignalKind.MutualFriends,
          Vector(
            EvidencePath(
              "fourteen-mutual-friends",
              rawStrength = 0.9,
              independentWitnesses = 14,
              anonymitySet = 14
            )
          )
        ),
        Signal(
          SignalKind.GeoColocation,
          Vector(
            EvidencePath(
              "coarse-metro-tile",
              rawStrength = 0.72,
              independentWitnesses = 6,
              anonymitySet = 260
            )
          )
        ),
        Signal(
          SignalKind.SocialBehavior,
          Vector(
            EvidencePath(
              "shared-public-group-and-events",
              rawStrength = 0.68,
              independentWitnesses = 9,
              anonymitySet = 41
            )
          )
        )
      )
    ),
    RawCandidate(
      CandidateProfile(twoSignalFriend),
      Vector(
        Signal(
          SignalKind.ContactBook,
          Vector(
            EvidencePath(
              "contact-match",
              rawStrength = 0.84,
              independentWitnesses = 2,
              anonymitySet = 2
            )
          )
        ),
        Signal(
          SignalKind.MutualFriends,
          Vector(
            EvidencePath(
              "two-mutual-friends",
              rawStrength = 0.7,
              independentWitnesses = 2,
              anonymitySet = 2
            )
          )
        )
      )
    ),
    RawCandidate(
      CandidateProfile(behaviorOnly),
      Vector(
        Signal(
          SignalKind.SocialBehavior,
          Vector(
            EvidencePath(
              "repeated-profile-views",
              rawStrength = 0.86,
              independentWitnesses = 1,
              anonymitySet = 1
            )
          )
        )
      )
    )
  )

  val profiles: Map[UserId, ViewerProfile] =
    Map(attacker.id -> attacker, normalUser.id -> normalUser)

  def recallerFor(candidatesByViewer: Map[UserId, Vector[RawCandidate]]): CandidateRecaller =
    (viewer: ViewerProfile) =>
      candidatesByViewer.getOrElse(viewer.id, Vector.empty).iterator
}

object NaiveBaseline {
  def score(candidate: RawCandidate): Double = {
    val strengths = candidate.signals.flatMap(_.paths.map(_.effectiveStrength))
    if (strengths.isEmpty) 0.0
    else math.min(1.0, strengths.max + 0.04 * (strengths.size - 1))
  }

  def rank(candidates: Vector[RawCandidate]): Vector[(UserId, Double)] =
    candidates
      .map(c => c.id -> score(c))
      .sortBy { case (_, score) => -score }
}
