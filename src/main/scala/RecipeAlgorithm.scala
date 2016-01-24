package com.recipe

import io.prediction.controller.{P2LAlgorithm, Params}
import io.prediction.data.storage.{BiMap, Event}
import io.prediction.data.store.LEventStore

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.{Rating => MLlibRating}
import org.apache.spark.mllib.feature.{Normalizer, StandardScaler}
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.mllib.linalg._
import org.apache.spark.rdd.RDD

import grizzled.slf4j.Logger

import scala.reflect.ClassTag
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * --알고리즘 초기설정 파라미터--
 * appName: predictionIO 앱 이름
 * unseenOnly: unseen 이벤트만 보여줌
 * seenEvents: 유저가 본 이벤트의 user-to-item 리스트, unseenOnly가 true일 때 쓰임
 * similarEvents: 비슷한 이벤트의 user-item-item 리스트, 유저가 최근에 본 item과 비슷한 item을 찾을 때 쓰임
 * rank: MLlib ALS 알고리즘의 파라미터. Number of latent feature.
 * numIterations: MLlib ALS 알고리즘의 파라미터. Number of iterations.
 * lambda: MLlib ALS 알고리즘의 정규화 파라미터
 * seed: MLlib ALS 알고리즘의 random seed. (Optional)
 * dimension: 벡터화 된 아이템의 차원 수
 * cooktimeWeight: 조리시간의 가중치
 * caloriesWeight: 칼로리의 가중치
 * expireWeight: 보관기간의 가중치
 * normalizeProjection: projection 표준화
 */
case class RecipeAlgorithmParams(
  appName: String,
  unseenOnly: Boolean,
  seenEvents: List[String],
  similarEvents: List[String],
  rank: Int,
  numIterations: Int,
  lambda: Double,
  seed: Option[Long],
  dimensions: Int,
  cooktimeWeight: Double,
  caloriesWeight: Double,
  expireWeight: Double,
  normalizeProjection: Boolean
) extends Params

/**
 * --레시피 모델--
 * item: 레시피
 * features: ALS 알고리즘으로 계산된 score
 * count: similar product가 없을 때 trainDefault()에 의해 반환된 popular count score
 */
case class RecipeModel(
  item: Item,
  features: Option[Array[Double]], // features by ALS
  count: Int // popular count for default score
)

/**
 * --레시피 알고리즘 모델--
 * rank: MLlib ALS 알고리즘의 파라미터. Number of latent feature.
 * userFreatures: 유저의 최근 행동 기록
 * recipeModels: 레시피 모델(item, features, count)
 * userStringIntMap: 유저String을 Int로 Mapping
 * itemStringIntMap: 아이템String을 Int로 Mapping
 * itemIds: 아이템id
 * projection: projection 매트릭스
 */
class RecipeAlgorithmModel(
  val rank: Int,
  val userFeatures: Map[Int, Array[Double]],
  val recipeModels: Map[Int, RecipeModel],
  val userStringIntMap: BiMap[String, Int],
  val itemStringIntMap: BiMap[String, Int],
  val itemIds: BiMap[String, Int], 
  val projection: DenseMatrix
) extends Serializable {

  @transient lazy val itemIntStringMap = itemStringIntMap.inverse

  override def toString = {
    s" rank: ${rank}" +
    s" userFeatures: [${userFeatures.size}]" +
    s"(${userFeatures.take(2).toList}...)" +
    s" recipeModels: [${recipeModels.size}]" +
    s"(${recipeModels.take(2).toList}...)" +
    s" userStringIntMap: [${userStringIntMap.size}]" +
    s"(${userStringIntMap.take(2).toString}...)]" +
    s" itemStringIntMap: [${itemStringIntMap.size}]" +
    s"(${itemStringIntMap.take(2).toString}...)]" +
    s"Items: ${itemIds.size}"
  }
}

/**
 * --레시피 알고리즘--
 * Collaborative Filtering과 Content Based Filtering방식을 독립적으로 시행한 뒤 score를 합산함
 */
class RecipeAlgorithm(val ap: RecipeAlgorithmParams)
  extends P2LAlgorithm[PreparedData, RecipeAlgorithmModel, Query, PredictedResult] {

  @transient lazy val logger = Logger[this.type]

  def train(sc: SparkContext, data: PreparedData): RecipeAlgorithmModel = {
    /* Collaborative Filtering */
    require(!data.viewEvents.take(1).isEmpty,
      s"viewEvents in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    require(!data.likeEvents.take(1).isEmpty,
      s"likeEvents in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    require(!data.users.take(1).isEmpty,
      s"users in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    require(!data.items.take(1).isEmpty,
      s"items in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")

    // User와 Item의 String ID를 integer index BiMap으로 생성
    val userStringIntMap = BiMap.stringInt(data.users.keys)
    val itemStringIntMap = BiMap.stringInt(data.items.keys)

    val mllibRatings: RDD[MLlibRating] = genMLlibRating(
      userStringIntMap = userStringIntMap,
      itemStringIntMap = itemStringIntMap,
      data = data
    )

    // 만약 training data가 없으면 MLLib ALS 알고리즘을 수행할 수 없음
    require(!mllibRatings.take(1).isEmpty,
      s"mllibRatings cannot be empty." +
      " Please check if your events contain valid user and item ID.")

    // MLlib ALS 알고리즘의 seed
    val seed = ap.seed.getOrElse(System.nanoTime)

    // featrure vector들을 training 하기 위해 ALS의 암묵적선호도 알고리즘을 사용함
    val m = ALS.trainImplicit(
      ratings = mllibRatings,
      rank = ap.rank,
      iterations = ap.numIterations,
      lambda = ap.lambda,
      blocks = -1,
      alpha = 1.0,
      seed = seed)

    val userFeatures = m.userFeatures.collectAsMap.toMap

    // item String을 Int로 변환
    val items = data.items.map { case (id, item) =>
      (itemStringIntMap(id), item)
    }

    // item과 trained productFeature를 Mapping
    val productFeatures: Map[Int, (Item, Option[Array[Double]])] =
      items.leftOuterJoin(m.productFeatures).collectAsMap.toMap

    // 유저 정보가 없을시 다른 유저의 view 이벤트와 like 이벤트를 기준으로 합산한 popular count를 training에 이용
    val popularCount = trainDefault(
      userStringIntMap = userStringIntMap,
      itemStringIntMap = itemStringIntMap,
      data = data
    )

    val recipeModels: Map[Int, RecipeModel] = productFeatures
      .map { case (index, (item, features)) =>
        val pm = RecipeModel(
          item = item,
          features = features,
          // NOTE: popularCount는 모든 아이템을 포함하지 않을 수 있으니 getOrElse를 사용함
          count = popularCount.getOrElse(index, 0)
        )
        (index, pm)
      }




    /* Content Based Filtering */
    val itemIds = BiMap.stringInt(data.items.map(_._1))

    // categorical var을 one-hot encoding을 이용해 binary로 변환함
    val categorical = Seq(encode(data.items.map(_._2.categories)),
      encode(data.items.map(_._2.feelings)))

    /**
     * numeric var를 변환함.
     * categorical attribute들이 binary로 encoding되었기 때문에 numeric var를 
     * binary로 scaling한 후 미리 설정한 가중치가 주어짐.
     * 이 가중치는 feature별로 중요도에 따라 다르게 설정 가능함
     */
    val numericRow = data.items.map(x => Vectors.dense(x._2.cooktime, x._2
      .calories,x._2.expire))
    val weights = Array(ap.cooktimeWeight, ap.caloriesWeight, ap.expireWeight)
    val scaler = new StandardScaler(withMean = true,
      withStd = true).fit(numericRow)
    val numeric = numericRow.map(x => Vectors.dense(scaler.transform(x).
      toArray.zip(weights).map { case (x, w) => x * w }))

    /**
     * 모든 data를 병합한 후 표준화 함.
     * 이를 통해 벡터간의 코사인 값을 구할 수 있음
     */
    val normalizer = new Normalizer(p = 2.0)
    val allData = normalizer.transform((categorical ++ Seq(numeric)).reduce(merge))

    /**
     * SVD 는 row의 갯수가 많을 때 더 효율적이므로 RDD를 transpose 할 필요가 있음.
     * 일반적으로 attribute의 갯수가 itme의 갯수보다 많으므로 transpose 하는 것이며, 
     * 반대의 경우에는 transpose하지 않아도 됌.
     */
    val transposed = transposeRDD(allData)

    val mat: RowMatrix = new RowMatrix(transposed)

    // Make SVD to reduce data dimensionality
    val svd: SingularValueDecomposition[RowMatrix, Matrix] = mat.computeSVD(
      ap.dimensions, computeU = false)

    val V: DenseMatrix = new DenseMatrix(svd.V.numRows, svd.V.numCols,
      svd.V.toArray)

    val projection = Matrices.diag(svd.s).multiply(V.transpose)

/*
    
    /**
     * 다음은 RDD를 transpose 하지 않은 코드.
     * item의 갯수가 attribute의 갯수보다 많을 때 사용함.
     */
    val mat: RowMatrix = new RowMatrix(allData)

    val svd: SingularValueDecomposition[RowMatrix, Matrix] = mat.computeSVD(
      ap.dimensions, computeU = true)

    val U: DenseMatrix = new DenseMatrix(svd.U.numRows.toInt, svd.U.numCols
      .toInt, svd.U.rows.flatMap(_.toArray).collect(), isTransposed = true)

    val projection = Matrices.diag(svd.s).multiply(U.transpose)
*/

    svd.s.toArray.zipWithIndex.foreach { case (x, y) =>
      logger.info(s"Singular value #$y = $x") }

    val maxRank = Seq(mat.numCols(), mat.numRows()).min
    val total = svd.s.toArray.map(x => x * x).reduce(_ + _)
    val worstLeft = svd.s.toArray.last * svd.s.toArray.last * (maxRank - svd.s.size)
    val variabilityGrasped = 100 * total / (total + worstLeft)

    logger.info(s"Worst case variability grasped: $variabilityGrasped%")

    val res = if(ap.normalizeProjection) {
      val sequentionalizedProjection = for (j <- 0 until projection.numCols)
        yield Vectors.dense((for (i <- 0 until projection.numRows) yield
        projection(i, j)).toArray)

      val normalizedProjectionSeq = sequentionalizedProjection.map(x =>
        normalizer.transform(x))

      val normalizedProjection = new DenseMatrix(projection.numRows, projection
        .numCols, normalizedProjectionSeq.flatMap(x => x.toArray).toArray)

      new RecipeAlgorithmModel(
      rank = m.rank,
      userFeatures = userFeatures,
      recipeModels = recipeModels,
      userStringIntMap = userStringIntMap,
      itemStringIntMap = itemStringIntMap,
      itemIds = itemIds,
      projection = normalizedProjection
      )
    } else {
      new RecipeAlgorithmModel(
      rank = m.rank,
      userFeatures = userFeatures,
      recipeModels = recipeModels,
      userStringIntMap = userStringIntMap,
      itemStringIntMap = itemStringIntMap,
      itemIds = itemIds,
      projection = projection
      )
    }

    res
  }

  /* PreparedData로부터 MLlibRating을 생성 */
  def genMLlibRating(
    userStringIntMap: BiMap[String, Int],
    itemStringIntMap: BiMap[String, Int],
    data: PreparedData): RDD[MLlibRating] = {

    // 'view' 이벤트에 대한 rating 생성
    val mllibRatings1 = data.viewEvents
      .map { r =>
        // user와 item String ID를 Int로 변환
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        ((uindex, iindex), 1)
      }
      .filter { case ((u, i), v) =>
        // 유효한 user와 item index에 해당하는 event를 keep
        (u != -1) && (i != -1)
      }
      .reduceByKey(_ + _) // 같은 user-item 이벤트 pair를 합침

    // 'like' 이벤트에 대한 rating 생성
    val mllibRatings2 = data.likeEvents
      .map { r =>
        // user와 item String ID를 Int로 변환
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        // key 는 (uindex, iindex) tuple, value 는 (like, t) tuple
        ((uindex, iindex), (r.like, r.t))
      }.filter { case ((u, i), v) =>
        // val  = d
        // 유효한 user와 item index에 해당하는 event를 keep
        (u != -1) && (i != -1)
      }.reduceByKey { case (v1, v2) => 
        // User가 like를 했다가 cancel_like를 나중에 하게 되면, 또는 그 반대인 경우,
        // 가장 최근의 이벤트를 적용함.
        val (like1, t1) = v1
        val (like2, t2) = v2
        // keep the latest value
        if (t1 > t2) v1 else v2
      }.map { case ((u, i), (like, t)) => 
        // ALS.trainImplicit() 사용
        val r = if (like) 5 else 0  // 'like' 이벤트의 rating 은 5, 'cancel_like' 이벤트의 rating은 0
        ((u, i), r)
      }


      val sumOfmllibRatings = mllibRatings1.union(mllibRatings2).reduceByKey(_ + _)
        .map { case ((u, i), v) =>
        // MLlibRating은 user와 item에 대한 integer index를 필요로 함
        MLlibRating(u, i, v)
      }.cache()

      sumOfmllibRatings
  }

  /**
   * 모든 'view' 이벤트와 'like' 이벤트들을 가중치 1:5의 비율로 점수화해서 더함.
   * 이는 User에 대해 아무런 정보가 없을 때 대중적으로 선호하는 아이템을 추천하기 위함임.
   */
  def trainDefault(
    userStringIntMap: BiMap[String, Int],
    itemStringIntMap: BiMap[String, Int],
    data: PreparedData): Map[Int, Int] = {
    
    // 'like', 'cancel_like' 의 갯수를 count
    // (item index, count)
    val likeCountsRDD: RDD[(Int, Int)] = data.likeEvents
      .map { r =>
        // user와 item String ID를 Int로 변환
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        // key 는 (uindex, iindex) tuple, value 는 (like, t) tuple
        ((uindex, iindex), (r.like, r.t))
      }
      .filter { case ((u, i), v) =>
        // 유효한 user와 item index에 해당하는 event를 keep
        (u != -1) && (i != -1)
      }
      .map { case ((u, i), (like, t)) => 
        if (like) (i, 5) else (i, -5) // like: 5, cancel_like: -5, cancel_like의 경우 -5를 count하여 like를 상쇄함.
      } // key is item
      .reduceByKey(_ + _) // 같은 user_item pair에 대해 모든 'like', 'cancel_like' 이벤트를 합침


    // 'view' 이벤트를 count
    // (item index, count)
    val viewCountsRDD: RDD[(Int, Int)] = data.viewEvents
      .map { r =>
        // user와 item String ID를 Int로 변환
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        ((uindex, iindex), 1) // view: 1, 반면 like의 경우는 5를 count하여 결과적으로 1:5의 가중치를 가짐
      }
      .filter { case ((u, i), v) =>
        // 유효한 user와 item index에 해당하는 event를 keep
        (u != -1) && (i != -1)
      }
      .map { case ((u, i), v) => 
        (i, v);
      } // key is item
      .reduceByKey(_ + _) // 같은 user_item pair에 대해 모든 'view' 이벤트를 합침
    
    // 같은 user_item pair에 대해 모든 'view', 'like', 'cancel_like' 이벤트를 합침
    val sumOfAll: RDD[(Int, Int)] = likeCountsRDD.union(viewCountsRDD).reduceByKey(_ + _)

    sumOfAll.collectAsMap.toMap
  }

  def predict(model: RecipeAlgorithmModel, query: Query): PredictedResult = {
    logger.info(s"User ${query.user} requires ${query.limit} recommendation recipes.")

    /* Collaborative Filtering */
    val userFeatures = model.userFeatures
    val recipeModels = model.recipeModels

    val recentItems: Set[String] = getRecentItems(query) 

    // whiteList의 String ID를 integer index로 변환 
    val whiteList: Option[Set[Int]] = query.whiteList.map( set =>
      set.flatMap(model.itemStringIntMap.get(_))
    )

    val finalBlackList: Set[Int] = genBlackList(query = query)
      // seen Items List를 String ID에서 integer index로 변환
      .flatMap(x => model.itemStringIntMap.get(x))

    val userFeature: Option[Array[Double]] =
      model.userStringIntMap.get(query.user).flatMap { userIndex =>
        userFeatures.get(userIndex)
      }

    val topScores: Array[(Int, Double)] = if (userFeature.isDefined) {
      logger.info(s"User ${query.user} recently viewed or liked ${recentItems}.")
      // 유저가 feature vector를 갖고있음
      predictKnownUser(
        userFeature = userFeature.get,
        recipeModels = recipeModels,
        query = query,
        whiteList = whiteList,
        blackList = finalBlackList
      )
    } else {
      // 유저가 feature vector를 갖고있지 않음
      // 예를들면, 모델이 train된 이후 새로운 유저가 set된 경우 등등
      logger.info(s"No userFeature found for user ${query.user}.")

      // 유저가 최근에 item을 'view' 또는 'like' 했는지 체크
      val recentItems: Set[String] = getRecentItems(query)
      val recentList: Set[Int] = recentItems.flatMap (x =>
        model.itemStringIntMap.get(x))

      val recentFeatures: Set[Array[Double]] = recentList
        // 최근에 'view' 또는 'like'한 item은 recipeModel에 포함되지 않음
        .map { i =>
          recipeModels.get(i).flatMap { pm => pm.features }
        }.flatten

      if (recentFeatures.isEmpty) {
        logger.info(s"No features set for recent items ${recentItems}.")
        predictDefault(
          recipeModels = recipeModels,
          query = query,
          whiteList = whiteList,
          blackList = finalBlackList
        )
      } else {
        logger.info(s"User ${query.user} recently viewed or liked ${recentItems}.")
        predictSimilar(
          recentFeatures = recentFeatures,
          recipeModels = recipeModels,
          query = query,
          whiteList = whiteList,
          blackList = finalBlackList
        )
      }
    }

    val itemScores = topScores.map { case (i, s) =>
      new ItemScore(
        // item int index를 다시 String ID로 변환
        item = model.itemIntStringMap(i),
        score = s
      )
    }



    /* Content Based Filtering */
    /**
     * 모든 item에 대해 유저의 recentItems와의 유사도를 측정하여
     * 높은 score를 가진 item순으로 나열함
     */
    val result = predictContentBased(
      recentItems = recentItems,
      model = model,
      recipeModels = recipeModels,
      query = query,
      whiteList = whiteList,
      blackList = finalBlackList)

    if(result.isEmpty) logger.info(s"User ${query.user} has no recent action.")


    // Collaborative Filtering 결과값과 Content Based 결과값을 합침
    val combinedResult = itemScores.union(result) 

    PredictedResult(combinedResult)
  }

  /* 최종 Blacklist 생성 */
  def genBlackList(query: Query): Set[String] = {
    // unseenOnly를 true로 설정한 경우, 모든 seen item을 받아옴
    val seenItems: Set[String] = if (ap.unseenOnly) {

      // 'seen' event라고 간주되는 모든 item 이벤트를 가져옴 (ex: view, like, cancel_like)
      val seenEvents: Iterator[Event] = try {
        LEventStore.findByEntity(
          appName = ap.appName,
          entityType = "user",
          entityId = query.user,
          eventNames = Some(ap.seenEvents),
          targetEntityType = Some(Some("item")),
          // 너무 오랜시간 DB access를 방지하도록 time limit 설정
          timeout = Duration(200, "millis")
        )
      } catch {
        case e: scala.concurrent.TimeoutException =>
          logger.error(s"Timeout when read seen events." +
            s" Empty list is used. ${e}")
          Iterator[Event]()
        case e: Exception =>
          logger.error(s"Error when read seen events: ${e}")
          throw e
      }

      seenEvents.map { event =>
        try {
          event.targetEntityId.get
        } catch {
          case e => {
            logger.error(s"Can't get targetEntityId of event ${event}.")
            throw e
          }
        }
      }.toSet
    } else {
      Set[String]()
    }

    // 가장 최근의 constraint unavailableItems $set event를 받아옴
    val unavailableItems: Set[String] = try {
      val constr = LEventStore.findByEntity(
        appName = ap.appName,
        entityType = "constraint",
        entityId = "unavailableItems",
        eventNames = Some(Seq("$set")),
        limit = Some(1),
        latest = true,
        timeout = Duration(200, "millis")
      )
      if (constr.hasNext) {
        constr.next.properties.get[Set[String]]("items")
      } else {
        Set[String]()
      }
    } catch {
      case e: scala.concurrent.TimeoutException =>
        logger.error(s"Timeout when read set unavailableItems event." +
          s" Empty list is used. ${e}")
        Set[String]()
      case e: Exception =>
        logger.error(s"Error when read set unavailableItems event: ${e}")
        throw e
    }

    // 쿼리의 blacklist, seenItems, unavailableItems를 모두 합쳐서 최종 blacklist를 생성
    query.blackList.getOrElse(Set[String]()) ++ seenItems ++ unavailableItems
  }

  /* Similar item을 추천하기 위해 User가 action을 취한 최근 item 목록을 가져옴 */
  def getRecentItems(query: Query): Set[String] = {
    // 최근 10개의 'view' 또는 'like' 이벤트를 불러옴
    val recentEvents = try {
      LEventStore.findByEntity(
        appName = ap.appName,
        // 빠른 탐색을 위해 entityType과 entityId를 지정함
        entityType = "user",
        entityId = query.user,
        eventNames = Some(ap.similarEvents),
        targetEntityType = Some(Some("item")),
        limit = Some(10), // Default: 10 items
        latest = true,
        // 너무 오랜시간 DB access를 방지하도록 time limit 설정
        timeout = Duration(200, "millis")
      )
    } catch {
      case e: scala.concurrent.TimeoutException =>
        logger.error(s"Timeout when read recent events." +
          s" Empty list is used. ${e}")
        Iterator[Event]()
      case e: Exception =>
        logger.error(s"Error when read recent events: ${e}")
        throw e
    }

    val recentItems: Set[String] = recentEvents.map { event =>
      try {
        event.targetEntityId.get
      } catch {
        case e => {
          logger.error("Can't get targetEntityId of event ${event}.")
          throw e
        }
      }
    }.toSet

    recentItems
  }

  /* Feature vector가 있는 user를 위한 prediction */
  def predictKnownUser(
    userFeature: Array[Double],
    recipeModels: Map[Int, RecipeModel],
    query: Query,
    whiteList: Option[Set[Int]],
    blackList: Set[Int]
  ): Array[(Int, Double)] = {
    val indexScores: Map[Int, Double] = recipeModels.par // parallel collection 으로 변환
      .filter { case (i, pm) =>
        pm.features.isDefined &&
        isCandidateItem(
          i = i,
          item = pm.item,
          categories = query.categories,
          feelings = query.feelings,
          whiteList = whiteList,
          blackList = blackList
        )
      }
      .map { case (i, pm) =>
        // NOTE: .get을 호출하기 위해 features가 정의되어 있어야함
        val s = dotProduct(userFeature, pm.features.get)
  
        (i, s)
      }
      .filter(_._2 > 0) // score > 0 인 item들만 keep
      .seq // sequential collection으로 다시 변환

    val ord = Ordering.by[(Int, Double), Double](_._2).reverse
    val topScores = getTopN(indexScores, query.limit)(ord).toArray

    topScores
  }

  /* User에 대한 정보가 없을때, 대중적으로 인기있는 아이템을 추천하는 기본 prediction */
  def predictDefault(
    recipeModels: Map[Int, RecipeModel],
    query: Query,
    whiteList: Option[Set[Int]],
    blackList: Set[Int]
  ): Array[(Int, Double)] = {
    val indexScores: Map[Int, Double] = recipeModels.par // sequential collection으로 다시 변환
      .filter { case (i, pm) =>
        isCandidateItem(
          i = i,
          item = pm.item,
          categories = query.categories,
          feelings = query.feelings,
          whiteList = whiteList,
          blackList = blackList
        )
      }
      .map { case (i, pm) =>

        (i, pm.count.toDouble)
      }
      .seq

    val ord = Ordering.by[(Int, Double), Double](_._2).reverse
    val topScores = getTopN(indexScores, query.limit)(ord).toArray

    topScores
  }

  /* User가 최근 action을 취한 top similar item을 prediction. (Default: 10 recent action)*/
  def predictSimilar(
    recentFeatures: Set[Array[Double]],
    recipeModels: Map[Int, RecipeModel],
    query: Query,
    whiteList: Option[Set[Int]],
    blackList: Set[Int]
  ): Array[(Int, Double)] = {
    val indexScores: Map[Int, Double] = recipeModels.par // parallel collection 으로 변환
      .filter { case (i, pm) =>
        pm.features.isDefined &&
        isCandidateItem(
          i = i,
          item = pm.item,
          categories = query.categories,
          feelings = query.feelings,
          whiteList = whiteList,
          blackList = blackList
        )
      }
      .map { case (i, pm) =>
        val s = recentFeatures.map{ rf =>
          // 위의 filter logic을 위해 pm.features가 정의되어 있어야 함
          cosine(rf, pm.features.get)
        }.reduce(_ + _)

        (i, s)
      }
      .filter(_._2 > 0) // score > 0 인 item들만 keep
      .seq // sequential collection으로 다시 변환

    val ord = Ordering.by[(Int, Double), Double](_._2).reverse
    val topScores = getTopN(indexScores, query.limit)(ord).toArray

    topScores
  }

  /* Content Based Filtering */
  /**
   * 모든 item에 대해 유저의 recentItems와의 유사도를 측정하여
   * 높은 score를 가진 item순으로 나열함
   */
  def predictContentBased(
    recentItems: Set[String],
    model: RecipeAlgorithmModel,
    recipeModels: Map[Int, RecipeModel],
    query: Query,
    whiteList: Option[Set[Int]],
    blackList: Set[Int]
  ): Array[ItemScore] = {
    val result = recentItems.flatMap { itemId =>
      model.itemIds.get(itemId).map { j =>
        val d = for(i <- 0 until model.projection.numRows) yield model.projection(i, j)
        val col = model.projection.transpose.multiply(new DenseVector(d.toArray))
        for(k <- 0 until col.size) yield new ItemScore(model.itemIds.inverse
          .getOrElse(k, default="NA"), col(k))
      }.getOrElse(Seq())
    }.groupBy {
      case(ItemScore(itemId, _)) => itemId
    }.map(_._2.max).filter {
      case(ItemScore(itemId, _)) => !recentItems.contains(itemId)
    }.filter { case(ItemScore(itemId, _)) =>
      isCandidateItem(
        i = model.itemStringIntMap(itemId),
        item = recipeModels.get(model.itemStringIntMap(itemId)).get.item,
        categories = query.categories,
        feelings = query.feelings,
        whiteList = whiteList,
        blackList = blackList)
    }
    .toArray.sorted.reverse.take(query.limit)

    result
  }

  /* score가 가장 높은 top n을 반환 */
  private def getTopN[T](s: Iterable[T], n: Int)(implicit ord: Ordering[T]): Seq[T] = {

    val q = PriorityQueue()

    for (x <- s) {
      if (q.size < n)
        q.enqueue(x)
      else {
        // q is full
        if (ord.compare(x, q.head) < 0) {
          q.dequeue()
          q.enqueue(x)
        }
      }
    }

    q.dequeueAll.toSeq.reverse
  }

  /* 벡터의 내적 */
  private def dotProduct(v1: Array[Double], v2: Array[Double]): Double = {
    val size = v1.size
    var i = 0
    var d: Double = 0
    while (i < size) {
      d += v1(i) * v2(i)
      i += 1
    }
    d
  }

  /* 코사인 유사도 */
  private def cosine(v1: Array[Double], v2: Array[Double]): Double = {
    val size = v1.size
    var i = 0
    var n1: Double = 0
    var n2: Double = 0
    var d: Double = 0
    while (i < size) {
      n1 += v1(i) * v1(i)
      n2 += v2(i) * v2(i)
      d += v1(i) * v2(i)
      i += 1
    }
    val n1n2 = (math.sqrt(n1) * math.sqrt(n2))
    if (n1n2 == 0) 0 else (d / n1n2)
  }

  /* 최종 필터링(categories, whitelist, blacklist ...) */
  private def isCandidateItem(
    i: Int,
    item: Item,
    categories: Option[Set[String]],
    feelings: Option[Set[String]],
    whiteList: Option[Set[Int]],
    blackList: Set[Int]
  ): Boolean = {
    // whiteList와 blackList 필터링
    whiteList.map(_.contains(i)).getOrElse(true) &&
    !blackList.contains(i) &&
    // categories 필터링
    categories.map { cat =>
      item.categories2.map { itemCat =>
        // 쿼리의 categories와 겹치면 이 item을 keep
        !(itemCat.toSet.intersect(cat).isEmpty)
      }.getOrElse(false) // 만약 category가 없으면 item을 버림
    }.getOrElse(true) &&
    // feelings 필터링
    feelings.map { cat =>
      item.feelings2.map { itemCat =>
        // 쿼리의 categories와 겹치면 이 item을 keep
        !(itemCat.toSet.intersect(cat).isEmpty)
      }.getOrElse(false) // 만약 category가 없으면 item을 버림
    }.getOrElse(true)    

  }

  /* String Array를 one-hot encoding을 이용해 binary로 변환 */
  private def encode(data: RDD[Array[String]]): RDD[Vector] = {
    val dict = BiMap.stringLong(data.flatMap(x => x))
    val len = dict.size

    data.map { sample =>
      val indexes = sample.map(dict(_).toInt).sorted
      Vectors.sparse(len, indexes, Array.fill[Double](indexes.length)(1.0))
    }
  }

  /* String을 one-hot encoding을 이용해 binary로 변환 */
  // [X: ClassTag] - 다른 input의 encode 정의를 위한 trick
  private def encode[X: ClassTag](data: RDD[String]): RDD[Vector] = {
    val dict = BiMap.stringLong(data)
    val len = dict.size

    data.map { sample =>
      val index = dict(sample).toInt
      Vectors.sparse(len, Array(index), Array(1.0))
    }
  }

  /* 두 개의 RDD Vector를 변환 */
  private def merge(v1: RDD[Vector], v2: RDD[Vector]): RDD[Vector] = {
    v1.zip(v2) map {
      case (SparseVector(leftSz, leftInd, leftVals), SparseVector(rightSz,
      rightInd, rightVals)) =>
        Vectors.sparse(leftSz + rightSz, leftInd ++ rightInd.map(_ + leftSz),
          leftVals ++ rightVals)
      case (SparseVector(leftSz, leftInd, leftVals), DenseVector(rightVals)) =>
        Vectors.sparse(leftSz + rightVals.length, leftInd ++ (0 until rightVals
          .length).map(_ + leftSz), leftVals ++ rightVals)
    }
  }

  /* RDD Transpose(행과 열을 바꿈) */
  private def transposeRDD(data: RDD[Vector]) = {
    val len = data.count().toInt

    val byColumnAndRow = data.zipWithIndex().flatMap {
      case (rowVector, rowIndex) => { rowVector match {
        case SparseVector(_, columnIndices, values) =>
          values.zip(columnIndices)
        case DenseVector(values) =>
          values.zipWithIndex
      }} map {
        case(v, columnIndex) => columnIndex -> (rowIndex, v)
      }
    }

    val byColumn = byColumnAndRow.groupByKey().sortByKey().values

    val transposed = byColumn.map {
      indexedRow =>
        val all = indexedRow.toArray.sortBy(_._1)
        val significant = all.filter(_._2 != 0)
        Vectors.sparse(len, significant.map(_._1.toInt), significant.map(_._2))
    }

    transposed
  }
}