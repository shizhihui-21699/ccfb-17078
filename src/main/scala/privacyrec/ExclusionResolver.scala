package privacyrec

/**
 * Distributed privacy-preference lookup.
 *
 * In production this interface is backed by sharded KV/ABT storage and cached
 * near recommendation workers. Reads must be strongly consistent enough for
 * block/exclusion changes to propagate within the product's stated SLA.
 */
trait PrivacySettingsLookup {
  def get(userId: UserId): Option[UserPrivacySettings]
}

final class InMemoryPrivacySettingsLookup(initial: Map[UserId, UserPrivacySettings] = Map.empty)
    extends PrivacySettingsLookup {
  @volatile private var settings: Map[UserId, UserPrivacySettings] = initial

  def update(next: UserPrivacySettings): Unit = synchronized {
    settings = settings.updated(next.userId, next)
  }

  override def get(userId: UserId): Option[UserPrivacySettings] = settings.get(userId)
}

final class ExclusionResolver(lookup: PrivacySettingsLookup) {
  def settingsFor(user: UserId): UserPrivacySettings =
    lookup.get(user).getOrElse(UserPrivacySettings(user))

  /** Block and explicit "don't suggest X to me" are hard candidate filters. */
  def candidateHardRejection(
      viewer: UserId,
      candidate: UserId,
      relationshipPrivacyTags: Set[String] = Set.empty
  ): Option[RejectedCandidate] = {
    val viewerSettings = settingsFor(viewer)
    val candidateSettings = settingsFor(candidate)

    if (viewerSettings.blockedUserIds.contains(candidate) ||
        candidateSettings.blockedUserIds.contains(viewer)) {
      Some(RejectedCandidate(candidate, "hard_exclusion", Vector("blocked")))
    } else if (viewerSettings.dismissedUserIds.contains(candidate)) {
      Some(RejectedCandidate(candidate, "hard_exclusion", Vector("viewer_dismissed")))
    } else if (viewerSettings.shieldedUserIds.contains(candidate) ||
               candidateSettings.shieldedUserIds.contains(viewer)) {
      Some(RejectedCandidate(candidate, "hard_exclusion", Vector("relationship_excluded")))
    } else if (relationshipPrivacyTags.exists { tag =>
                 viewerSettings.excludedRelationCodes.contains(tag) ||
                 viewerSettings.excludedSensitivityCodes.contains(tag)
               }) {
      Some(RejectedCandidate(candidate, "hard_exclusion", Vector("relationship_category_excluded")))
    } else {
      None
    }
  }

  def signalDisabledByViewer(viewer: UserId, kind: SignalKind): Boolean =
    settingsFor(viewer).disabledSignalKinds.contains(kind)

  /**
   * Removes evidence paths that either endpoint asked not to use.
   *
   * Honouring both endpoints is important: a patient should be able to shield
   * the therapist edge, and the therapist should also be able to shield it.
   */
  def filterPath(path: EvidencePath): Either[Vector[String], EvidencePath] = {
    // Fail closed for an unresolved traversal. Production recall must hydrate
    // every intermediary edge (or an explicitly legal aggregated edge view);
    // checking only viewer/candidate would let an intermediary's shield fail open.
    if (path.via.nonEmpty && path.edges.isEmpty) {
      Left(Vector("unresolved_traversal_path"))
    } else {
      val reasons = path.edges.flatMap(edgeDisallowedReasons)
      if (reasons.nonEmpty) Left(reasons.distinct) else Right(path)
    }
  }

  def scrubSignal(signal: Signal): Signal =
    signal.copy(paths = signal.paths.flatMap(p => filterPath(p).toOption))

  private def edgeDisallowedReasons(edge: GraphEdge): Vector[String] = {
    val a = settingsFor(edge.from)
    val b = settingsFor(edge.to)
    val reasons = Vector.newBuilder[String]

    if (edge.usage == EdgeUsage.Forbidden) reasons += "forbidden_edge_usage"

    if (a.blockedUserIds.contains(edge.to) || b.blockedUserIds.contains(edge.from))
      reasons += "blocked_relationship"

    if (a.shieldedUserIds.contains(edge.to) || b.shieldedUserIds.contains(edge.from))
      reasons += "relationship_excluded"

    if (a.excludedRelationCodes.contains(edge.relation.code) ||
        b.excludedRelationCodes.contains(edge.relation.code))
      reasons += "relation_type_excluded"

    if (a.excludedSensitivityCodes.contains(edge.relation.sensitivity.code) ||
        b.excludedSensitivityCodes.contains(edge.relation.sensitivity.code))
      reasons += "sensitive_category_excluded"

    reasons.result()
  }
}
