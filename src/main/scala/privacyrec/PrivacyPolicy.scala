package privacyrec

/**
 * Tunable privacy/utility controls.
 *
 * Thresholds here are conservative, readable defaults rather than universal
 * truths. Production values should be chosen from offline replay and holdout
 * metrics; see [[PrivacyUtilityReport]].
 */
final case class PrivacyPolicy(
    version: PolicyVersion = PolicyVersion("privacy-v1"),
    /** Minimum anonymity-set / witness cohort for a sensitive signal. */
    kAnonymity: Int = 3,
    /** Independent signal families required for high-risk candidates. */
    minIndependentSignals: Int = 2,
    /** No signal family should exceed this share after relative capping. */
    maxSignalShare: Double = 0.55,
    /** Herfindahl-Hirschman index alarm for source concentration. Two equal sources give 0.5. */
    maxHhi: Double = 0.58,
    /** Absolute cap when there is only one positive family. */
    singleSignalScoreCap: Double = 0.58,
    /** Absolute cap for corroborated but still privacy-sensitive candidates. */
    sensitiveScoreCap: Double = 0.46,
    /** Candidates below this score are not worth showing. */
    minRecommendationScore: Double = 0.22,
    newAccountAgeDays: Int = 30,
    lowTrustScore: Double = 0.35,
    sparseContactBookSize: Int = 3,
    highRiskScore: Double = 0.55,
    suppressRiskScore: Double = 0.78,
    criticalRiskScore: Double = 0.94,
    freshContactWeightMultiplier: Double = 0.35,
    riskDownrankStrength: Double = 0.45,
    maxIdentifierProbesPerDay: Int = 8,
    maxLinkedAccountsOnDevice: Int = 3,
    signalWeights: Map[SignalKind, Double] = PrivacyPolicy.defaultWeights
) {
  require(kAnonymity >= 1)
  require(minIndependentSignals >= 1)
  require(maxSignalShare > 0.0 && maxSignalShare <= 1.0)
  require(maxHhi > 0.0 && maxHhi <= 1.0)
  require(singleSignalScoreCap >= 0.0 && singleSignalScoreCap <= 1.0)
  require(sensitiveScoreCap >= 0.0 && sensitiveScoreCap <= 1.0)
  require(minRecommendationScore >= 0.0 && minRecommendationScore <= 1.0)
  require(highRiskScore <= suppressRiskScore)
  require(suppressRiskScore <= criticalRiskScore)

  def weight(kind: SignalKind): Double = signalWeights.getOrElse(kind, 0.5)
}

object PrivacyPolicy {
  val defaultWeights: Map[SignalKind, Double] = Map(
    SignalKind.ContactBook -> 1.0,
    SignalKind.MutualFriends -> 0.9,
    SignalKind.GeoColocation -> 0.48,
    SignalKind.SocialBehavior -> 0.55,
    SignalKind.AccountGraph -> 0.4
  )

  /** A stricter profile for newly registered, low-trust or probing accounts. */
  val lockedDown: PrivacyPolicy = PrivacyPolicy(
    version = PolicyVersion("privacy-locked-v1"),
    kAnonymity = 5,
    minIndependentSignals = 3,
    maxSignalShare = 0.45,
    maxHhi = 0.34,
    singleSignalScoreCap = 0.35,
    sensitiveScoreCap = 0.25,
    minRecommendationScore = 0.3,
    suppressRiskScore = 0.65,
    criticalRiskScore = 0.85,
    freshContactWeightMultiplier = 0.15
  )
}

final case class UtilityMetrics(
    accepted: Int,
    suppressed: Int,
    averageConstrainedScore: Double,
    averageUnconstrainedScore: Double,
    scoreRetention: Double
)

object PrivacyUtilityReport {
  def from(results: Iterable[ViewerResult]): UtilityMetrics = {
    val accepted = results.flatMap(_.recommendations).toVector
    val decisions = results.flatMap(_.decisions).toVector
    val suppressed = decisions.count {
      case r: RejectedCandidate if r.stage == "privacy_gate" => true
      case _ => false
    }
    val constrained = accepted.map(_.score)
    val before = accepted.map(_.unconstrainedScore)
    val avgC = if (constrained.nonEmpty) constrained.sum / constrained.size else 0.0
    val avgU = if (before.nonEmpty) before.sum / before.size else 0.0
    val retention = if (avgU == 0.0) 1.0 else avgC / avgU
    UtilityMetrics(accepted.size, suppressed, avgC, avgU, retention)
  }
}
