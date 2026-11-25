package privacyrec

/**
 * Chooses stricter constraints for abuse-prone account states.
 *
 * The baseline policy protects ordinary users without sacrificing too much
 * utility. Fresh sparse address books, probe bursts and device clusters receive
 * smaller cohorts/caps. This function must stay a pure configuration decision;
 * heavy abuse classification belongs in a dedicated risk service.
 */
object PrivacyPolicySelector {
  def fixed(policy: PrivacyPolicy): ViewerProfile => PrivacyPolicy = _ => policy

  def adaptive(base: PrivacyPolicy): ViewerProfile => PrivacyPolicy = { viewer =>
    if (viewer.recentIdentifierProbes >= base.maxIdentifierProbesPerDay) {
      PrivacyPolicy.lockedDown
    } else if (isSuspiciousSparse(viewer, base)) {
      base.copy(
        version = PolicyVersion(base.version.value + "-fresh-sparse"),
        kAnonymity = math.max(base.kAnonymity, 4),
        maxSignalShare = math.min(base.maxSignalShare, 0.5),
        maxHhi = math.min(base.maxHhi, 0.48),
        singleSignalScoreCap = math.min(base.singleSignalScoreCap, 0.42),
        sensitiveScoreCap = math.min(base.sensitiveScoreCap, 0.34),
        suppressRiskScore = math.min(base.suppressRiskScore, 0.7),
        criticalRiskScore = math.min(base.criticalRiskScore, 0.9)
      )
    } else {
      base
    }
  }

  private def isSuspiciousSparse(viewer: ViewerProfile, policy: PrivacyPolicy): Boolean =
    viewer.freshSparseAccount(policy) ||
      viewer.linkedAccountsOnDevice >= policy.maxLinkedAccountsOnDevice
}
