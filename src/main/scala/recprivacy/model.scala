package recprivacy

/** 推荐候选所依赖的信号来源。 */
enum Signal:
  case Contacts       // 通讯录匹配
  case MutualFriends  // 共同好友
  case Geo            // 地理位置
  case Behavior       // 社交行为(浏览/互动/群组等)

/** 一条候选证据:某个信号对候选分数的贡献及其结构信息。
  *
  * @param reciprocal    仅对 Contacts 有意义:双方是否互相上传了对方(双向通讯录边)
  * @param bridgingNodes 仅对 MutualFriends 有意义:连接双方的中间人(共同好友)集合
  */
final case class SignalEvidence(
    signal: Signal,
    weight: Double,
    reciprocal: Boolean = true,
    bridgingNodes: Set[Long] = Set.empty
)

/** 一个推荐候选及其全部信号证据。 */
final case class Candidate(userId: Long, evidence: List[SignalEvidence]):

  def totalScore: Double = evidence.map(_.weight).sum

  def weightOf(s: Signal): Double = evidence.filter(_.signal == s).map(_.weight).sum

  /** 各信号占总分的比例。 */
  def shares: Map[Signal, Double] =
    val total = totalScore
    if total <= 0 then Map.empty
    else Signal.values.map(s => s -> weightOf(s) / total).toMap

  /** 主导信号及其占比。 */
  def dominant: (Signal, Double) =
    if shares.isEmpty then (Signal.Behavior, 0.0)
    else shares.maxBy(_._2)

  /** Herfindahl 集中度:越接近 1 说明分数越被单一信号垄断。 */
  def hhi: Double = shares.values.map(x => x * x).sum

  def hasSignal(s: Signal): Boolean = evidence.exists(_.signal == s)

  /** 是否存在通讯录以外的独立佐证信号。 */
  def hasNonContactCorroboration: Boolean =
    evidence.exists(e => e.signal != Signal.Contacts && e.weight > 0)

  /** 通讯录证据是否全部为单向边。 */
  def contactIsOneWayOnly: Boolean =
    val contacts = evidence.filter(_.signal == Signal.Contacts)
    contacts.nonEmpty && contacts.forall(!_.reciprocal)

  /** 共同好友证据汇聚到的全部中间人。 */
  def allBridgingNodes: Set[Long] =
    evidence.filter(_.signal == Signal.MutualFriends).flatMap(_.bridgingNodes).toSet
