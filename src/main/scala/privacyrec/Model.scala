package privacyrec

/** Strong identifiers prevent phone hashes, user ids and geo cells being mixed up. */
final case class UserId(value: String) extends AnyVal
final case class PhoneHash(value: String) extends AnyVal
final case class GeoCellId(value: String) extends AnyVal
final case class DeviceId(value: String) extends AnyVal

final case class PolicyVersion(value: String) extends AnyVal

/** The four recommendation families named in the problem plus an optional abuse signal. */
sealed trait SignalKind extends Product with Serializable {
  def code: String
}

object SignalKind {
  case object ContactBook extends SignalKind { val code = "contact_book" }
  case object MutualFriends extends SignalKind { val code = "mutual_friends" }
  case object GeoColocation extends SignalKind { val code = "geo_colocation" }
  case object SocialBehavior extends SignalKind { val code = "social_behavior" }
  case object AccountGraph extends SignalKind { val code = "account_graph" }

  val baseKinds: Set[SignalKind] = Set(ContactBook, MutualFriends, GeoColocation, SocialBehavior)
}

sealed trait Pseudonymity extends Product with Serializable
object Pseudonymity {
  case object RealName extends Pseudonymity
  case object Pseudonymous extends Pseudonymity
  case object Anonymous extends Pseudonymity
}

/**
 * How a relationship edge may be used by recommendation recall/ranking.
 *
 * Open: ordinary feature use.
 * AggregateOnly: must be hidden inside a sufficiently large cohort and corroborated.
 * Forbidden: hidden, blocked or explicitly excluded; it must never enter scoring.
 */
sealed trait EdgeUsage extends Product with Serializable
object EdgeUsage {
  case object Open extends EdgeUsage
  case object AggregateOnly extends EdgeUsage
  case object Forbidden extends EdgeUsage
}

final case class Sensitivity(
    code: String,
    defaultUsage: EdgeUsage,
    riskWeight: Double
) {
  require(riskWeight >= 0.0 && riskWeight <= 1.0, "riskWeight must be in [0,1]")
}

object Sensitivity {
  val None: Sensitivity = Sensitivity("none", EdgeUsage.Open, 0.0)
  val Colleague: Sensitivity = Sensitivity("colleague", EdgeUsage.Open, 0.05)
  val Family: Sensitivity = Sensitivity("family", EdgeUsage.AggregateOnly, 0.35)
  val Romantic: Sensitivity = Sensitivity("romantic", EdgeUsage.AggregateOnly, 0.65)
  val ExPartner: Sensitivity = Sensitivity("ex_partner", EdgeUsage.AggregateOnly, 0.82)
  val Healthcare: Sensitivity = Sensitivity("healthcare", EdgeUsage.AggregateOnly, 0.9)
  val LegalOrFinancial: Sensitivity = Sensitivity("legal_financial", EdgeUsage.AggregateOnly, 0.8)
  val AnonymousIdentity: Sensitivity = Sensitivity("anonymous_identity", EdgeUsage.Forbidden, 0.97)

  def custom(code: String, riskWeight: Double, usage: EdgeUsage = EdgeUsage.AggregateOnly): Sensitivity =
    Sensitivity(code, usage, riskWeight)
}

final case class RelationLabel(code: String, sensitivity: Sensitivity)

object RelationLabel {
  val PhoneContact: RelationLabel = RelationLabel("phone_contact", Sensitivity.None)
  val Friend: RelationLabel = RelationLabel("friend", Sensitivity.None)
  val Coworker: RelationLabel = RelationLabel("coworker", Sensitivity.Colleague)
  val Family: RelationLabel = RelationLabel("family", Sensitivity.Family)
  val Partner: RelationLabel = RelationLabel("partner", Sensitivity.Romantic)
  val ExPartner: RelationLabel = RelationLabel("ex_partner", Sensitivity.ExPartner)
  val Therapist: RelationLabel = RelationLabel("therapist", Sensitivity.Healthcare)
  val Doctor: RelationLabel = RelationLabel("doctor", Sensitivity.Healthcare)
  val Lawyer: RelationLabel = RelationLabel("lawyer", Sensitivity.LegalOrFinancial)
  val AnonymousAlt: RelationLabel = RelationLabel("anonymous_alt", Sensitivity.AnonymousIdentity)
}

/** An undirected edge key used when either endpoint's privacy choice must be honoured. */
final case class EdgeKey(a: UserId, b: UserId)

object EdgeKey {
  def from(x: UserId, y: UserId): EdgeKey =
    if (x.value <= y.value) EdgeKey(x, y) else EdgeKey(y, x)
}

final case class GraphEdge(
    from: UserId,
    to: UserId,
    relation: RelationLabel,
    hidden: Boolean = false,
    usageOverride: Option[EdgeUsage] = None
) {
  def key: EdgeKey = EdgeKey.from(from, to)

  def usage: EdgeUsage =
    usageOverride.getOrElse {
      if (hidden) EdgeUsage.Forbidden else relation.sensitivity.defaultUsage
    }
}

final case class ViewerProfile(
    id: UserId,
    accountAgeDays: Int,
    trustScore: Double,
    contactBookSize: Int,
    verifiedPhone: Boolean = false,
    recentIdentifierProbes: Int = 0,
    linkedAccountsOnDevice: Int = 1
) {
  require(accountAgeDays >= 0)
  require(trustScore >= 0.0 && trustScore <= 1.0)
  require(contactBookSize >= 0)

  def freshSparseAccount(policy: PrivacyPolicy): Boolean =
    trustScore < policy.lowTrustScore ||
      (accountAgeDays <= policy.newAccountAgeDays &&
        contactBookSize <= policy.sparseContactBookSize)
}

final case class CandidateProfile(
    id: UserId,
    pseudonymity: Pseudonymity = Pseudonymity.RealName,
    activeAccount: Boolean = true,
    /**
     * Viewer-specific relationship privacy labels hydrated from the graph,
     * e.g. "therapist" or "healthcare". These must describe the relationship
     * to the viewer/pivot, not a generic public profession.
     */
    relationshipPrivacyTags: Set[String] = Set.empty
)

/**
 * One concrete evidence path. `anonymitySet` is the cohort size in which this
 * path should be hidden: shared contacts, people in the same coarse geo tile,
 * etc. `independentWitnesses` counts distinct evidence items of this path kind.
 * When `witnessKeys` are supplied, aggregation de-duplicates them across paths.
 * Without keys, recall/hydration must provide non-overlapping counts; the core
 * cannot infer that the same mutual friend/contact appears in two path rows.
 */
final case class EvidencePath(
    pathId: String,
    via: Vector[UserId] = Vector.empty,
    edges: Vector[GraphEdge] = Vector.empty,
    rawStrength: Double,
    confidence: Double = 1.0,
    independentWitnesses: Int = 1,
    anonymitySet: Int = 1,
    rareBucket: Boolean = false,
    witnessKeys: Set[String] = Set.empty
) {
  require(rawStrength >= 0.0 && rawStrength <= 1.0)
  require(confidence >= 0.0 && confidence <= 1.0)
  require(independentWitnesses >= 1)
  require(anonymitySet >= 1)

  def effectiveStrength: Double = rawStrength * confidence
}

final case class Signal(kind: SignalKind, paths: Vector[EvidencePath]) {
  def nonEmpty: Boolean = paths.nonEmpty
}

final case class RawCandidate(profile: CandidateProfile, signals: Vector[Signal]) {
  def id: UserId = profile.id
}

/** User privacy controls. Lookup is sharded by [[UserId]] in production. */
final case class UserPrivacySettings(
    userId: UserId,
    blockedUserIds: Set[UserId] = Set.empty,
    dismissedUserIds: Set[UserId] = Set.empty,
    /** Specific people whose relationship edge must not be recommendation evidence. */
    shieldedUserIds: Set[UserId] = Set.empty,
    /** Relationship codes such as "therapist", "ex_partner", "anonymous_alt". */
    excludedRelationCodes: Set[String] = Set.empty,
    excludedSensitivityCodes: Set[String] = Set.empty,
    disabledSignalKinds: Set[SignalKind] = Set.empty
)

sealed trait PublicReason extends Product with Serializable
object PublicReason {
  case object MutualConnections extends PublicReason
  case object SharedContactsOrNetwork extends PublicReason
  case object ActiveInSamePlace extends PublicReason
  case object SharedInterestsOrGroups extends PublicReason
  case object YouMayKnowThem extends PublicReason
  case object NoExplanation extends PublicReason

  def text(reason: PublicReason): String = reason match {
    case MutualConnections => "有共同好友"
    case SharedContactsOrNetwork => "你们可能处于同一个社交网络"
    case ActiveInSamePlace => "你们在相近地区活动"
    case SharedInterestsOrGroups => "你们有相似的公开兴趣或群组活动"
    case YouMayKnowThem => "你可能认识 TA"
    case NoExplanation => "为保护关系隐私，不展示推荐理由"
  }
}

final case class LeakFlag(
    code: String,
    severity: Double,
    internalDetail: String,
    remediation: String
) {
  require(severity >= 0.0 && severity <= 1.0)
}

/**
 * Per-family score after weighting, diminishing returns and dominance caps.
 * It is a breakdown of the constrained balancer output; the privacy gate may
 * still apply a global risk multiplier/cap, so these values need not sum to
 * `AcceptedCandidate.score`.
 */
final case class SignalContribution(
    kind: SignalKind,
    rawRelevance: Double,
    weight: Double,
    contribution: Double,
    share: Double,
    singleKind: Boolean,
    dominanceViolation: Boolean
)

final case class RiskAssessment(
    score: Double,
    flags: Vector[LeakFlag]
) {
  def codes: Vector[String] = flags.map(_.code)
  def hasCriticalFlag: Boolean = flags.exists(_.severity >= 0.8)
}

sealed trait CandidateDecision {
  def candidateId: UserId
}

final case class AcceptedCandidate(
    candidateId: UserId,
    score: Double,
    unconstrainedScore: Double,
    reason: PublicReason,
    contributions: Vector[SignalContribution],
    risk: RiskAssessment,
    policyVersion: PolicyVersion
) extends CandidateDecision

final case class RejectedCandidate(
    candidateId: UserId,
    stage: String,
    reasonCodes: Vector[String],
    risk: Option[RiskAssessment] = None
) extends CandidateDecision

final case class ViewerResult(
    viewerId: UserId,
    recommendations: Vector[AcceptedCandidate],
    decisions: Vector[CandidateDecision],
    recalled: Int,
    policyVersion: PolicyVersion
) {
  def rejected: Vector[RejectedCandidate] = decisions.collect { case r: RejectedCandidate => r }
  def recommendedIds: Set[UserId] = recommendations.map(_.candidateId).toSet
}

final case class BatchStats(
    viewers: Long = 0L,
    candidatesRecalled: Long = 0L,
    accepted: Long = 0L,
    rejected: Long = 0L,
    hardExcluded: Long = 0L,
    suppressedByPrivacy: Long = 0L,
    totalConstrainedScore: Double = 0.0,
    totalUnconstrainedScore: Double = 0.0,
    failedViewers: Long = 0L,
    elapsedMillis: Long = 0L
) {
  def recordDecision(d: CandidateDecision): BatchStats = d match {
    case a: AcceptedCandidate =>
      copy(
        accepted = accepted + 1,
        totalConstrainedScore = totalConstrainedScore + a.score,
        totalUnconstrainedScore = totalUnconstrainedScore + a.unconstrainedScore
      )
    case r: RejectedCandidate =>
      copy(
        rejected = rejected + 1,
        hardExcluded = if (r.stage == "hard_exclusion") hardExcluded + 1 else hardExcluded,
        suppressedByPrivacy =
          if (r.stage == "privacy_gate") suppressedByPrivacy + 1 else suppressedByPrivacy
      )
  }

  /**
   * Score retained after caps/downranking among candidates that remain accepted.
   * This is not end-to-end utility: recall coverage must be measured separately
   * via acceptance rate and offline labelled/A-B conversion metrics.
   */
  def scoreRetentionAmongAccepted: Double =
    if (totalUnconstrainedScore == 0.0) 1.0
    else totalConstrainedScore / totalUnconstrainedScore

  def acceptanceRate: Double =
    if (accepted + rejected == 0L) 1.0 else accepted.toDouble / (accepted + rejected)
}
