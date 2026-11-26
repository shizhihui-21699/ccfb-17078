package recprivacy

/** 约束判定结果。多个约束的判定由执行策略合并:
  * Suppress 优先;否则所有 Demote 的系数连乘。
  */
sealed trait Verdict

case object Allow extends Verdict

/** 降权:保留候选但压低分数。factor ∈ (0, 1]。 */
final case class Demote(factor: Double, reason: String) extends Verdict

/** 抑制:从候选集中移除(仍受每日抑制预算约束,见 EnforcementPolicy)。 */
final case class Suppress(reason: String) extends Verdict

object Verdict:
  def combine(a: Verdict, b: Verdict): Verdict = (a, b) match
    case (s: Suppress, _)                 => s
    case (_, s: Suppress)                 => s
    case (Demote(f1, r1), Demote(f2, r2)) => Demote(f1 * f2, s"$r1; $r2")
    case (d: Demote, Allow)               => d
    case (Allow, d: Demote)               => d
    case _                                => Allow
