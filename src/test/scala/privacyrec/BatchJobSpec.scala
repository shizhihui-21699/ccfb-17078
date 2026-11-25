package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BatchJobSpec extends AnyFunSuite with Matchers {
  test("shard assignment is stable and covers every user exactly once across shards") {
    val users = (1 to 1000).map(i => UserId(s"user-$i")).toVector
    val shardCount = 12

    users.foreach { user =>
      val owners = (0 until shardCount).count(i => ShardAssignment.isOwner(user, i, shardCount))
      owners shouldBe 1
    }
  }

  test("bounded topK never retains more than K recommendations") {
    val viewer = ViewerProfile(UserId("v"), 500, 0.9, 200)
    val candidates = (1 to 100).map { i =>
      RawCandidate(
        CandidateProfile(UserId(s"c-$i")),
        Vector(
          Signal(
            SignalKind.MutualFriends,
            Vector(EvidencePath(s"p-$i", rawStrength = math.min(1.0, i / 100.0), independentWitnesses = 10, anonymitySet = 10))
          ),
          Signal(
            SignalKind.GeoColocation,
            Vector(EvidencePath(s"g-$i", rawStrength = 0.5, anonymitySet = 100))
          )
        )
      )
    }

    val result = SampleScenario.pipeline().processViewer(viewer, candidates, topK = 7)
    result.recommendations should have size 7
    result.recommendations.map(_.score).sliding(2).forall(p => p.head >= p(1)) shouldBe true
  }

  test("duplicate candidate ids from different recall sources are merged and returned once") {
    val viewer = ViewerProfile(UserId("dup-viewer"), 500, 0.9, 200)
    val id = UserId("dup-candidate")
    def row(kind: SignalKind) = RawCandidate(
      CandidateProfile(id),
      Vector(Signal(kind, Vector(EvidencePath(s"$kind-path", rawStrength = 0.7, independentWitnesses = 8, anonymitySet = 20))))
    )

    val result = SampleScenario.pipeline().processViewer(
      viewer,
      Iterator(row(SignalKind.MutualFriends), row(SignalKind.GeoColocation)),
      topK = 10
    )

    result.recalled shouldBe 2
    result.decisions should have size 1
    result.recommendations should have size 1
    result.recommendations.head.contributions.map(_.kind).toSet shouldBe
      Set(SignalKind.MutualFriends, SignalKind.GeoColocation)
  }

  test("one failing viewer lookup is isolated and recorded as a failed shard row") {
    val bad = UserId("bad-viewer")
    val good = UserId("good-viewer")
    val pipeline = SampleScenario.pipeline()
    val recaller = new CandidateRecaller {
      override def recall(viewer: ViewerProfile): Iterator[RawCandidate] = Iterator.empty
    }
    val job = new ShardedBatchJob(
      pipeline,
      id =>
        if (id == bad) throw new RuntimeException("KV unavailable")
        else Some(ViewerProfile(good, 100, 0.8, 50)),
      recaller,
      new CollectingRecommendationSink(),
      BatchJobConfig(shardCount = 1, shardIndex = 0, topK = 5)
    )

    val stats = job.runShard(Iterator(bad, good))
    stats.failedViewers shouldBe 1L
    stats.viewers shouldBe 1L
  }

  test("iterator-based shard job emits rows and aggregates stats without global candidate storage") {
    val n = 500
    val profiles = (1 to n).map { i =>
      UserId(s"batch-user-$i") -> ViewerProfile(UserId(s"batch-user-$i"), 200 + i, 0.8, 100 + i)
    }.toMap

    val recaller = new CandidateRecaller {
      override def recall(viewer: ViewerProfile): Iterator[RawCandidate] =
        (1 to 20).iterator.map { j =>
          RawCandidate(
            CandidateProfile(UserId(s"${viewer.id.value}-cand-$j")),
            Vector(
              Signal(
                SignalKind.MutualFriends,
                Vector(EvidencePath(s"p-$j", rawStrength = 0.7, independentWitnesses = 6, anonymitySet = 6))
              ),
              Signal(
                SignalKind.SocialBehavior,
                Vector(EvidencePath(s"b-$j", rawStrength = 0.55, independentWitnesses = 5, anonymitySet = 20))
              )
            )
          )
        }
    }

    val sink = new CollectingRecommendationSink()
    val pipeline = new PrivacyPipeline(
      PrivacyPolicy(),
      new ExclusionResolver(new InMemoryPrivacySettingsLookup())
    )
    val job = new ShardedBatchJob(
      pipeline,
      profiles.get,
      recaller,
      sink,
      BatchJobConfig(shardCount = 4, shardIndex = 0, topK = 5)
    )

    val stats = job.runShard(profiles.keys.iterator)
    stats.viewers should be > 0L
    stats.candidatesRecalled should be > stats.viewers
    stats.accepted should be > 0L
    sink.collected.size.toLong shouldBe stats.viewers
    sink.collected.foreach { case (_, recs) => recs.size should be <= 5 }
  }
}
