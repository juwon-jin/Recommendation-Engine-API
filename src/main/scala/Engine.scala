package com.recipe

import io.prediction.controller.IEngineFactory
import io.prediction.controller.Engine

/**
 * --쿼리-- 
 * 추천받을 user id와 추천받을 레시피 목록 num 기본, 
 * categories, whitelist, blacklist를 통한 필터링은 옵션
 */
case class Query(
  user: String,
  limit: Int,
  skip: Int,
  categories: Option[Set[String]],
  feelings: Option[Set[String]],
  whiteList: Option[Set[String]],
  blackList: Option[Set[String]]
) extends Serializable

// 추천 레시피 목록이 점수순으로 나열됌
case class PredictedResult(
  itemScores: Array[ItemScore]
) extends Serializable

case class ItemScore(
  item: String, 
  score: Double
  ) extends Serializable with Ordered[ItemScore] {
  def compare(that: ItemScore) = this.score.compare(that.score)
}

object RecommendationEngine extends IEngineFactory {
  def apply() = {
    new Engine(
      classOf[DataSource],
      classOf[Preparator],
      Map("RecipeAlgorithm" -> classOf[RecipeAlgorithm]), 
      classOf[Serving])
  }
}