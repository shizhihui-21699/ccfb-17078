package privacyrec

import scala.util.control.NonFatal

trait CandidateRecaller {
  /** Implementations must return bounded top-K-per-source candidates, not the whole graph. */
  def recall(viewer: ViewerProfile): Iterator[RawCandidate]
}

trait RecommendationSink {
  def write(viewer: ViewerProfile, result: ViewerResult): Unit
  def flush(): Unit = ()
  def close(): Unit = ()
}

final class CollectingRecommendationSink extends RecommendationSink {
  private val rows = Vector.newBuilder[(UserId, Vector[AcceptedCandidate])]

  override def write(viewer: ViewerProfile, result: ViewerResult): Unit = synchronized {
    rows += ((viewer.id, result.recommendations))
  }

  def collected: Vector[(UserId, Vector[AcceptedCandidate])] = synchronized(rows.result())
}

object ShardAssignment {
  def shardOf(userId: UserId, shardCount: Int): Int = {
    require(shardCount > 0)
    Math.floorMod(userId.value.hashCode, shardCount)
  }

  def isOwner(userId: UserId, shardIndex: Int, shardCount: Int): Boolean =
    shardOf(userId, shardCount) == shardIndex
}

final case class BatchJobConfig(
    shardCount: Int = 256,
    shardIndex: Int = 0,
    topK: Int = 30,
    /** Per-source recall cap; prevents a hot contact-hash key from creating a huge candidate set. */
    maxCandidatesPerViewer: Int = 2_000
) {
  require(shardIndex >= 0 && shardIndex < shardCount)
}

/**
 * Partition-local, iterator-based job.
 *
 * One instance processes one assigned shard/partition. At Facebook-like scale,
 * Spark/Flink would schedule thousands of these partitions against co-located
 * graph/KV data. Memory is O(recall cap + topK), not O(global users).
 */
final class ShardedBatchJob(
    pipeline: PrivacyPipeline,
    profileLookup: UserId => Option[ViewerProfile],
    recaller: CandidateRecaller,
    sink: RecommendationSink,
    config: BatchJobConfig = BatchJobConfig(),
    failureHandler: (UserId, Throwable) => Unit = (_, _) => ()
) {
  def runShard(allViewerIds: Iterator[UserId]): BatchStats = {
    val started = System.currentTimeMillis()
    var stats = BatchStats()
    var failedViewers = 0L

    allViewerIds.foreach { id =>
      if (ShardAssignment.isOwner(id, config.shardIndex, config.shardCount)) {
        try {
          profileLookup(id).foreach { viewer =>
            val raw = recaller.recall(viewer).take(config.maxCandidatesPerViewer)
            val result = pipeline.processViewer(viewer, raw, config.topK)
            sink.write(viewer, result)

            stats = stats.copy(
              viewers = stats.viewers + 1,
              candidatesRecalled = stats.candidatesRecalled + result.recalled
            )
            result.decisions.foreach(d => stats = stats.recordDecision(d))
          }
        } catch {
          case NonFatal(error) =>
            // KV/RPC/sink failures must be retried/dead-lettered by the caller.
            // Isolating the viewer prevents one bad row from killing a whole partition.
            failedViewers += 1
            failureHandler(id, error)
        }
      }
    }

    sink.flush()
    stats.copy(failedViewers = failedViewers, elapsedMillis = System.currentTimeMillis() - started)
  }
}
