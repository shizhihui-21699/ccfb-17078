package privacyrec

/**
 * In-JVM throughput smoke test for the per-user merge/score path.
 *
 * This is not a substitute for cluster sizing: the production cost is dominated
 * by shuffled graph joins and KV lookups. It demonstrates that the privacy layer
 * itself is linear in bounded recalled candidates and uses a fixed-size top-K.
 */
object LoadBenchmark extends App {
  val users = args.lift(0).flatMap(_.toIntOption).getOrElse(50_000)
  val candidatesPerUser = args.lift(1).flatMap(_.toIntOption).getOrElse(80)
  val topK = args.lift(2).flatMap(_.toIntOption).getOrElse(20)

  val viewer = ViewerProfile(
    id = UserId("benchmark-viewer"),
    accountAgeDays = 500,
    trustScore = 0.88,
    contactBookSize = 260,
    verifiedPhone = true
  )

  val pipeline = new PrivacyPipeline(
    PrivacyPolicy(),
    new ExclusionResolver(new InMemoryPrivacySettingsLookup())
  )

  val recaller = new CandidateRecaller {
    override def recall(v: ViewerProfile): Iterator[RawCandidate] =
      (1 to candidatesPerUser).iterator.map { i =>
        RawCandidate(
          CandidateProfile(UserId(s"candidate-$i")),
          Vector(
            Signal(
              SignalKind.MutualFriends,
              Vector(
                EvidencePath(
                  s"mutual-$i",
                  rawStrength = 0.55 + (i % 40) / 100.0,
                  independentWitnesses = 4 + i % 12,
                  anonymitySet = 4 + i % 12
                )
              )
            ),
            Signal(
              SignalKind.GeoColocation,
              Vector(
                EvidencePath(
                  s"geo-$i",
                  rawStrength = 0.45 + (i % 30) / 100.0,
                  independentWitnesses = 5 + i % 20,
                  anonymitySet = 80 + i % 500
                )
              )
            ),
            Signal(
              SignalKind.SocialBehavior,
              Vector(
                EvidencePath(
                  s"behavior-$i",
                  rawStrength = 0.4 + (i % 40) / 100.0,
                  independentWitnesses = 3 + i % 15,
                  anonymitySet = 30 + i % 200
                )
              )
            )
          )
        )
      }
  }

  val noopSink = new RecommendationSink {
    override def write(viewer: ViewerProfile, result: ViewerResult): Unit = ()
  }

  val job = new ShardedBatchJob(
    pipeline,
    _ => Some(viewer),
    recaller,
    noopSink,
    BatchJobConfig(shardCount = 1, shardIndex = 0, topK = topK)
  )

  val ids = Iterator.from(1).take(users).map(i => UserId(s"viewer-$i"))
  val stats = job.runShard(ids)
  val candidateThroughput = stats.candidatesRecalled * 1000.0 / math.max(1, stats.elapsedMillis)
  val userThroughput = stats.viewers * 1000.0 / math.max(1, stats.elapsedMillis)

  println(f"users=${stats.viewers}%,d")
  println(f"candidates=${stats.candidatesRecalled}%,d")
  println(f"accepted=${stats.accepted}%,d rejected=${stats.rejected}%,d")
  println(f"elapsed=${stats.elapsedMillis}%,d ms")
  println(f"userThroughput=${userThroughput}%,.0f users/s/core")
  println(f"candidateThroughput=${candidateThroughput}%,.0f candidates/s/core")
}
