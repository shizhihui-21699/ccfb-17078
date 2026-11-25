package privacyrec

import scala.collection.mutable

final class BoundedTopK[A](k: Int)(implicit ord: Ordering[A]) {
  require(k > 0, "topK must be positive")
  private val heap = new mutable.PriorityQueue[A]()(ord.reverse)

  def add(value: A): Unit = {
    if (heap.size < k) heap.enqueue(value)
    else if (ord.gt(value, heap.head)) {
      heap.dequeue()
      heap.enqueue(value)
    }
  }

  /** Highest scores first; ties are ordered by the ordering's own tie-break. */
  def descending: Vector[A] = heap.clone().dequeueAll.reverse.toVector
}

object BoundedTopK {
  def apply[A: Ordering](k: Int): BoundedTopK[A] = new BoundedTopK[A](k)
}

object CandidateMerger {
  /** Source-specific recall rows for the same user must not become duplicate recommendations. */
  def merge(a: RawCandidate, b: RawCandidate): RawCandidate = {
    val pseudonymity =
      if (a.profile.pseudonymity == Pseudonymity.Anonymous ||
          b.profile.pseudonymity == Pseudonymity.Anonymous) Pseudonymity.Anonymous
      else if (a.profile.pseudonymity == Pseudonymity.Pseudonymous ||
               b.profile.pseudonymity == Pseudonymity.Pseudonymous) Pseudonymity.Pseudonymous
      else Pseudonymity.RealName

    RawCandidate(
      profile = a.profile.copy(
        pseudonymity = pseudonymity,
        activeAccount = a.profile.activeAccount && b.profile.activeAccount,
        relationshipPrivacyTags =
          a.profile.relationshipPrivacyTags ++ b.profile.relationshipPrivacyTags
      ),
      signals = a.signals ++ b.signals
    )
  }
}

final class PrivacyPipeline(
    val policy: PrivacyPolicy,
    exclusions: ExclusionResolver,
    policySelector: ViewerProfile => PrivacyPolicy
) {
  def this(policy: PrivacyPolicy, exclusions: ExclusionResolver) =
    this(policy, exclusions, PrivacyPolicySelector.fixed(policy))

  def processViewer(
      viewer: ViewerProfile,
      candidates: Iterator[RawCandidate],
      topK: Int = 12
  ): ViewerResult = {
    val effectivePolicy = policySelector(viewer)
    val decisionBuffer = Vector.newBuilder[CandidateDecision]
    val ranking = BoundedTopK[AcceptedCandidate](topK)(PrivacyPipeline.AcceptedOrdering)
    val uniqueCandidates = mutable.LinkedHashMap.empty[UserId, RawCandidate]
    var recalled = 0

    candidates.foreach { candidate =>
      recalled += 1
      uniqueCandidates.updateWith(candidate.id) {
        case Some(existing) => Some(CandidateMerger.merge(existing, candidate))
        case None => Some(candidate)
      }
    }

    uniqueCandidates.values.foreach { candidate =>
      val decision = evaluate(viewer, candidate, effectivePolicy)
      decisionBuffer += decision
      decision match {
        case accepted: AcceptedCandidate => ranking.add(accepted)
        case _: RejectedCandidate => ()
      }
    }

    ViewerResult(
      viewerId = viewer.id,
      recommendations = ranking.descending,
      decisions = decisionBuffer.result(),
      recalled = recalled,
      policyVersion = effectivePolicy.version
    )
  }

  def processViewer(
      viewer: ViewerProfile,
      candidates: Iterable[RawCandidate],
      topK: Int
  ): ViewerResult = processViewer(viewer, candidates.iterator, topK)

  def evaluate(viewer: ViewerProfile, raw: RawCandidate): CandidateDecision =
    evaluate(viewer, raw, policySelector(viewer))

  private def evaluate(
      viewer: ViewerProfile,
      raw: RawCandidate,
      policy: PrivacyPolicy
  ): CandidateDecision = {
    exclusions
      .candidateHardRejection(viewer.id, raw.id, raw.profile.relationshipPrivacyTags)
      .foreach { rejection =>
        return rejection
      }

    if (!raw.profile.activeAccount) {
      return RejectedCandidate(raw.id, "quality_gate", Vector("inactive_account"))
    }

    val enabledSignals = raw.signals.filterNot { signal =>
      exclusions.signalDisabledByViewer(viewer.id, signal.kind)
    }

    val scrubbedSignals = enabledSignals
      .map(exclusions.scrubSignal)
      .filter(_.nonEmpty)

    if (scrubbedSignals.isEmpty) {
      return RejectedCandidate(
        raw.id,
        "privacy_gate",
        Vector("no_legal_evidence_after_exclusions")
      )
    }

    val mergedSignals = SignalMerger.mergeByKind(scrubbedSignals)

    val aggregated = mergedSignals.flatMap(SignalAggregator.aggregate)
    if (aggregated.isEmpty) {
      return RejectedCandidate(raw.id, "privacy_gate", Vector("no_aggregatable_evidence"))
    }

    val balanced = SignalBalancer.balance(mergedSignals, viewer, policy)
    val risk = LeakDetector.assess(viewer, raw.profile, aggregated, balanced, policy)
    val independentKinds = aggregated.map(_.kind).toSet.size
    val thinRestrictedCohort = aggregated.exists { a =>
      a.restricted && (a.anonymitySet < policy.kAnonymity ||
        a.independentWitnesses < policy.kAnonymity)
    }
    val requiredIndependentKinds =
      if (thinRestrictedCohort) policy.minIndependentSignals + 1
      else policy.minIndependentSignals
    val requiresCorroboration =
      risk.score >= policy.highRiskScore ||
        risk.hasCriticalFlag ||
        aggregated.exists(_.restricted)

    if (risk.score >= policy.criticalRiskScore) {
      return RejectedCandidate(
        raw.id,
        "privacy_gate",
        Vector("critical_leakage_risk") ++ risk.codes,
        Some(risk)
      )
    }

    if (requiresCorroboration && independentKinds < requiredIndependentKinds) {
      return RejectedCandidate(
        raw.id,
        "privacy_gate",
        Vector("insufficient_independent_corroboration") ++
          (if (thinRestrictedCohort) Vector("thin_aggregate_only_cohort") else Vector.empty) ++
          risk.codes,
        Some(risk)
      )
    }

    if (risk.score >= policy.suppressRiskScore &&
        independentKinds < policy.minIndependentSignals + 1) {
      return RejectedCandidate(
        raw.id,
        "privacy_gate",
        Vector("leakage_risk_above_threshold") ++ risk.codes,
        Some(risk)
      )
    }

    var score = balanced.total
    if (risk.score >= policy.highRiskScore) {
      score *= 1.0 - policy.riskDownrankStrength * risk.score
      score = math.min(score, policy.sensitiveScoreCap)
    }
    if (aggregated.exists(_.restricted)) {
      score = math.min(score, policy.sensitiveScoreCap)
    }
    if (independentKinds == 1) {
      score = math.min(score, policy.singleSignalScoreCap)
    }
    score = math.max(0.0, math.min(1.0, score))

    if (score < policy.minRecommendationScore) {
      return RejectedCandidate(
        raw.id,
        "quality_gate",
        Vector("score_below_threshold"),
        Some(risk)
      )
    }

    AcceptedCandidate(
      candidateId = raw.id,
      score = score,
      unconstrainedScore = balanced.unconstrainedTotal,
      reason = publicReason(risk, balanced, policy, aggregated.exists(_.restricted)),
      contributions = balanced.contributions,
      risk = risk,
      policyVersion = policy.version
    )
  }

  private def publicReason(
      risk: RiskAssessment,
      balanced: BalancedScore,
      policy: PrivacyPolicy,
      restrictedPath: Boolean
  ): PublicReason = {
    if (risk.score >= policy.highRiskScore || restrictedPath) PublicReason.NoExplanation
    else {
      balanced.contributions.maxByOption(_.contribution).map(_.kind) match {
        case Some(SignalKind.MutualFriends) => PublicReason.MutualConnections
        case Some(SignalKind.ContactBook) => PublicReason.SharedContactsOrNetwork
        case Some(SignalKind.GeoColocation) => PublicReason.ActiveInSamePlace
        case Some(SignalKind.SocialBehavior) => PublicReason.SharedInterestsOrGroups
        case _ => PublicReason.YouMayKnowThem
      }
    }
  }
}

object PrivacyPipeline {
  val AcceptedOrdering: Ordering[AcceptedCandidate] =
    Ordering.by(a => (a.score, a.candidateId.value))
}
