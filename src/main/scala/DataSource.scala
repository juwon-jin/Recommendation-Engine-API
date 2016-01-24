package com.recipe

import io.prediction.controller.PDataSource
import io.prediction.controller.EmptyEvaluationInfo
import io.prediction.controller.EmptyActualResult
import io.prediction.controller.Params
import io.prediction.data.storage.Event
import io.prediction.data.store.PEventStore

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import grizzled.slf4j.Logger

case class DataSourceParams(appName: String) extends Params

class DataSource(val dsp: DataSourceParams)
  extends PDataSource[TrainingData,
      EmptyEvaluationInfo, Query, EmptyActualResult] {

  @transient lazy val logger = Logger[this.type]

  override
  def readTraining(sc: SparkContext): TrainingData = {

    // (entityID, User) RDD 생성
    val usersRDD: RDD[(String, User)] = PEventStore.aggregateProperties(
      appName = dsp.appName,
      entityType = "user"
    )(sc).map { case (entityId, properties) =>
      val user = try {
        User()
      } catch {
        case e: Exception => {
          logger.error(s"Failed to get properties ${properties} of" +
            s" user ${entityId}. Exception: ${e}.")
          throw e
        }
      }
      (entityId, user)
    }.cache()

    // (entityID, Item) RDD 생성
    val itemsRDD: RDD[(String, Item)] = PEventStore.aggregateProperties(
      appName = dsp.appName,
      entityType = "item"
    )(sc).map { case (entityId, properties) =>
      val item = try {
        /**
         *  title: 레시피이름
         *  categories: 음식카테고리
         *  feelings: 맛, 식감
         *  cooktime: 조리시간
         *  calories: 칼로리/1인분
         *  expire: 보관기간
         */
        val title: String = properties.get[String]("title") 
        val categories: Array[String] = properties.get[Array[String]]("categories")
        val categories2: Option[List[String]] = properties.getOpt[List[String]]("categories")
        val feelings: Array[String] = properties.get[Array[String]]("feelings")
        val feelings2: Option[List[String]] = properties.getOpt[List[String]]("feelings")
        val cooktime: Int = properties.get[Int]("cooktime")
        val calories: Int = properties.get[Int]("calories")
        val expire: Int = properties.get[Int]("expire")

        // 다음 feature들을 이용해 ContentBasedFiltering을 적용할 예정
        Item(entityId, title, categories, categories2, feelings, feelings2, cooktime, calories, expire)
      } catch {
        case e: Exception => {
          logger.error(s"Failed to get properties ${properties} of" +
            s" item ${entityId}. Exception: ${e}.")
          throw e
        }
      }
      (entityId, item)
    }.cache()

    // eventRDD 생성: 유저별 like, view, cancel_like 이벤트를 이용해 CollaborativeFiltering을 적용할 예정
    val eventsRDD: RDD[Event] = PEventStore.find(
      appName = dsp.appName,
      entityType = Some("user"),
      eventNames = Some(List("view", "like", "cancel_like")), 
      targetEntityType = Some(Some("item")))(sc)
      .cache()

    // view이벤트 필터: 레시피 클릭
    val viewEventsRDD: RDD[ViewEvent] = eventsRDD
      .filter { event => event.event == "view" }
      .map { event =>
        try {
          ViewEvent(
            user = event.entityId,
            item = event.targetEntityId.get,
            t = event.eventTime.getMillis
          )
        } catch {
          case e: Exception =>
            logger.error(s"Cannot convert ${event} to ViewEvent." +
              s" Exception: ${e}.")
            throw e
        }
      }

    // like, cancel_like 이벤트 필터: 레시피 좋아요 버튼 클릭 및 클릭 해제
    val likeEventsRDD: RDD[LikeEvent] = eventsRDD
      .filter { event => event.event == "like" | event.event == "cancel_like"}  // MODIFIED
      .map { event =>
        try {
          LikeEvent(
            user = event.entityId,
            item = event.targetEntityId.get,
            t = event.eventTime.getMillis,
            like = (event.event == "like")
          )
        } catch {
          case e: Exception =>
            logger.error(s"Cannot convert ${event} to LikeEvent." +
              s" Exception: ${e}.")
            throw e
        }
      }

    // 모든 Training Data: 각 아이템 별 feature, 각 유저 별 view, like, cancel_like 이벤트로 이루어짐
    new TrainingData(
      users = usersRDD,
      items = itemsRDD,
      viewEvents = viewEventsRDD,
      likeEvents = likeEventsRDD  
    )
  }
}

case class User()

case class Item(
  item: String, 
  title: String, 
  categories: Array[String], 
  categories2: Option[List[String]],
  feelings: Array[String], 
  feelings2: Option[List[String]],
  cooktime: Int,
  calories: Int, 
  expire: Int
)

case class ViewEvent(
  user: String, 
  item: String, 
  t: Long
)

case class LikeEvent( 
  user: String,
  item: String,
  t: Long,
  like: Boolean // true: like. false: cancel_like
)

class TrainingData(
  val users: RDD[(String, User)],
  val items: RDD[(String, Item)],
  val viewEvents: RDD[ViewEvent],
  val likeEvents: RDD[LikeEvent] 
) extends Serializable {
  override def toString = {
    s"users: [${users.count()} (${users.take(2).toList}...)]" +
    s"items: [${items.count()} (${items.take(2).toList}...)]" +
    s"viewEvents: [${viewEvents.count()}] (${viewEvents.take(2).toList}...)" +
    s"likeEvents: [${likeEvents.count()}] (${likeEvents.take(2).toList}...)"
  }
}