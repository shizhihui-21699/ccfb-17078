package privacyrec

object PrivacyRecommendationDemo extends App {
  import SampleScenario._

  println("=" * 88)
  println("隐私感知的“可能认识的人”推荐管道 / Privacy-safe PYMK pipeline")
  println("=" * 88)

  println("\n[1] 朴素综合打分：新小号只上传 Alice 的手机号，却能排序出她隐藏的关系")
  NaiveBaseline.rank(attackCandidates).foreach { case (id, score) =>
    println(f"  ${id.value}%-28s naive_score=$score%.2f")
  }

  val safePipeline = pipeline()
  val attackResult = safePipeline.processViewer(attacker, attackCandidates, topK = 10)

  println("\n[2] 加入关系排除、路径清洗、泄露风险门控和单信号上限后")
  if (attackResult.recommendations.isEmpty) {
    println("  高风险候选均未进入推荐列表。")
  } else {
    attackResult.recommendations.foreach(a => printAccepted("attacker", a))
  }
  attackResult.decisions.foreach {
    case r: RejectedCandidate =>
      println(s"  - 拦截 ${r.candidateId.value}: ${r.stage} / ${r.reasonCodes.mkString(",")}")
    case _ => ()
  }

  val normalResult = safePipeline.processViewer(normalUser, normalCandidates, topK = 10)
  println("\n[3] 正常高信任用户仍保留多信号和普通通讯录推荐")
  normalResult.recommendations.foreach(a => printAccepted(normalUser.id.value, a))
  normalResult.rejected.foreach { r =>
    println(s"  - 不推荐 ${r.candidateId.value}: ${r.stage} / ${r.reasonCodes.take(2).mkString(",")}")
  }

  val allResults = Vector(attackResult, normalResult)
  val metrics = PrivacyUtilityReport.from(allResults)
  println("\n[4] 隐私/效果指标（样例数据，仅说明离线度量方式）")
  println(f"  accepted=${metrics.accepted}%d suppressed=${metrics.suppressed}%d " +
    f"scoreRetention=${metrics.scoreRetention}%.2f avgScore=${metrics.averageConstrainedScore}%.2f")

  val sink = new CollectingRecommendationSink()
  val job = new ShardedBatchJob(
    pipeline = safePipeline,
    profileLookup = profiles.get,
    recaller = recallerFor(
      Map(attacker.id -> attackCandidates, normalUser.id -> normalCandidates)
    ),
    sink = sink,
    config = BatchJobConfig(shardCount = 1, shardIndex = 0, topK = 20)
  )
  val stats = job.runShard(profiles.keys.iterator)
  println("\n[5] 分片批处理统计")
  println(s"  viewers=${stats.viewers}, recalled=${stats.candidatesRecalled}, " +
    s"accepted=${stats.accepted}, rejected=${stats.rejected}, " +
    s"privacySuppressed=${stats.suppressedByPrivacy}, elapsed=${stats.elapsedMillis}ms")
  println(s"  sinkRows=${sink.collected.size}")

  println("\n公开推荐理由示例（不包含敏感关系或精确路径）：")
  normalResult.recommendations.foreach { a =>
    println(s"  ${a.candidateId.value} -> ${PublicReason.text(a.reason)}")
  }

  private def printAccepted(viewer: String, a: AcceptedCandidate): Unit = {
    val kinds = a.contributions.map(c => s"${c.kind.code}:${f"${c.share * 100}%.0f%%"}").mkString(" ")
    println(
      f"  [$viewer] 推荐 ${a.candidateId.value}%-28s score=${a.score}%.2f " +
        f"beforeCaps=${a.unconstrainedScore}%.2f risk=${a.risk.score}%.2f [$kinds]"
    )
  }
}
