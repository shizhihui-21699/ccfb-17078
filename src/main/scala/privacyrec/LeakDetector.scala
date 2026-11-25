package privacyrec

/**
 * Interprets graph/account evidence as possible recommendation side channels.
 *
 * The detector is intentionally explainable. Every flag has a stable code so
 * offline replay can measure which rule prevented an attack and auditors can
 * understand a suppression without reading raw address-book contents.
 */
object LeakDetector {
  def assess(
      viewer: ViewerProfile,
      candidate: CandidateProfile,
      aggregated: Vector[AggregatedSignal],
      balanced: BalancedScore,
      policy: PrivacyPolicy
  ): RiskAssessment = {
    val flags = Vector.newBuilder[LeakFlag]
    val freshSparse = viewer.freshSparseAccount(policy)

    if (viewer.recentIdentifierProbes >= policy.maxIdentifierProbesPerDay) {
      flags += LeakFlag(
        "identifier_probing_pattern",
        0.9,
        s"account has ${viewer.recentIdentifierProbes} identifier probes in the observation window",
        "challenge, rate-limit or force stronger account trust before contact recall"
      )
    }

    if (viewer.linkedAccountsOnDevice >= policy.maxLinkedAccountsOnDevice) {
      flags += LeakFlag(
        "sockpuppet_device_graph",
        0.72,
        s"${viewer.linkedAccountsOnDevice} accounts share a device/device graph",
        "increase trust threshold and reduce second-order contact propagation"
      )
    }

    aggregated.foreach { a =>
      val lowAnonymity = a.anonymitySet < policy.kAnonymity ||
        a.independentWitnesses < policy.kAnonymity

      a.kind match {
        case SignalKind.ContactBook =>
          if (freshSparse && lowAnonymity) {
            flags += LeakFlag(
              "single_identifier_oracle",
              0.88,
              "a low-trust sparse account can use one phone/email match as an oracle into a graph",
              "discount contact evidence, require independent corroboration, and block second-order propagation"
            )
          } else if (lowAnonymity) {
            flags += LeakFlag(
              "thin_contact_cohort",
              0.28,
              s"contact evidence cohort/witness count is below k=${policy.kAnonymity}",
              "cap the contribution and add corroboration when account context is risky"
            )
          }

          if (a.hasSensitiveEdge && a.hasSecondOrderPath) {
            flags += LeakFlag(
              "sensitive_second_order_contact_path",
              if (lowAnonymity) 0.94 else 0.72,
              "contact recall traverses a sensitive hidden relationship to reach the candidate",
              "remove/shield the edge or require strong independent non-contact evidence"
            )
          }

        case SignalKind.MutualFriends =>
          if (lowAnonymity) {
            flags += LeakFlag(
              "thin_mutual_friend_set",
              0.3,
              "one or two mutual friends can confirm an otherwise hidden edge",
              "do not expose exact mutual-friend count and require another signal family"
            )
          }

        case SignalKind.GeoColocation =>
          if (a.rareBucket || lowAnonymity) {
            flags += LeakFlag(
              "rare_geolocation_colocation",
              if (a.rareBucket) 0.78 else 0.62,
              "rare or precise co-location can reveal attendance at a sensitive place/relationship",
              "coarsen geo tiles, increase time windows, and require non-geo corroboration"
            )
          }

        case SignalKind.SocialBehavior =>
          if (lowAnonymity || aggregated.size == 1) {
            flags += LeakFlag(
              "behavioral_relationship_side_channel",
              0.58,
              "profile views, interactions or dwell patterns can expose a hidden relationship",
              "aggregate behavior and prevent this family from ranking alone"
            )
          }

        case SignalKind.AccountGraph =>
        // Device/account graph is an abuse-control signal, not a social proof.
      }

      if (a.restricted && lowAnonymity) {
        flags += LeakFlag(
          "thin_aggregate_only_cohort",
          0.62,
          "an AggregateOnly relationship path is not hidden in a k-sized cohort",
          "require stronger independent corroboration and cap or suppress the candidate"
        )
      }

      if (a.hasSensitiveEdge && a.kind != SignalKind.ContactBook) {
        flags += LeakFlag(
          "sensitive_relationship_path",
          math.max(0.68, a.maxSensitivity),
          "evidence path touches a healthcare, legal, ex-partner or identity-concealing edge",
          "do not use the sensitive edge as an explanation and apply a stricter score cap"
        )
      }
    }

    if (candidate.pseudonymity == Pseudonymity.Anonymous &&
        aggregated.exists(a => a.kind == SignalKind.ContactBook || a.kind == SignalKind.GeoColocation)) {
      flags += LeakFlag(
        "anonymous_account_deanonymization",
        0.9,
        "contact or precise geo evidence could link an anonymous account to a real identity graph",
        "suppress unless evidence is broad, aggregated and independently corroborated"
      )
    }

    if (balanced.hhiViolation || balanced.contributions.exists(_.dominanceViolation)) {
      flags += LeakFlag(
        "single_signal_dominance",
        if (aggregated.size == 1) 0.18 else 0.24,
        f"recommendation score is concentrated in one signal family; hhi=${balanced.hhi}%.2f",
        "cap the dominant family and require corroboration in high-risk contexts"
      )
    }

    val distinctFlags = flags.result().groupBy(_.code).map { case (_, fs) => fs.maxBy(_.severity) }.toVector
    RiskAssessment(noisyOr(distinctFlags.map(_.severity)), distinctFlags.sortBy(-_.severity))
  }

  private def noisyOr(severities: Vector[Double]): Double =
    math.min(1.0, severities.foldLeft(0.0) { case (acc, s) => 1.0 - (1.0 - acc) * (1.0 - s) })
}
