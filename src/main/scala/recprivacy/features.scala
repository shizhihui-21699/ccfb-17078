package recprivacy

/** 批量预计算的"推荐接收方"特征(每天离线作业产出,候选打分时 O(1) 读取)。
  *
  * @param uploadedContactCount 该用户上传的通讯录条目数。
  *        这是"小号攻击"的关键可观测特征:只上传了 1 个号码的新号,
  *        任何通讯录来源的推荐都能被唯一归因到那个号码的主人身上。
  * @param exclusions           用户主动排除的关系(不想再被推荐、也不想被推荐给的人)。
  */
final case class SourceUserFeatures(
    userId: Long,
    accountAgeDays: Int,
    uploadedContactCount: Int,
    exclusions: Set[Long] = Set.empty
)

/** 批量预计算的"被推荐人"特征(图聚合作业产出)。
  *
  * @param contactInDegree             有多少人的通讯录里存在该用户
  * @param reciprocalInDegree          其中双向互存的数量
  * @param uploaderMutualConnectivity  上传者之间的互连密度(0..1)。
  *        心理医生/咨询师这类"隐秘关系枢纽"的典型特征:
  *        入度很高、互存率极低、上传者彼此几乎不认识。
  */
final case class TargetUserFeatures(
    userId: Long,
    contactInDegree: Int,
    reciprocalInDegree: Int,
    uploaderMutualConnectivity: Double
):
  def reciprocalRatio: Double =
    if contactInDegree <= 0 then 1.0 else reciprocalInDegree.toDouble / contactInDegree

  /** 隐秘关系枢纽判定:大量单向通讯录入边 + 上传者互不相识。 */
  def isHiddenTieHub(
      minInDegree: Int = 20,
      maxReciprocalRatio: Double = 0.2,
      maxUploaderConnectivity: Double = 0.05
  ): Boolean =
    contactInDegree >= minInDegree &&
      reciprocalRatio < maxReciprocalRatio &&
      uploaderMutualConnectivity < maxUploaderConnectivity
