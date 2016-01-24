package com.recipe

import io.prediction.controller.LServing

import grizzled.slf4j.Logger

import breeze.stats.mean
import breeze.stats.meanAndVariance
import breeze.stats.MeanAndVariance

class Serving
  extends LServing[Query, PredictedResult] {

  override
  def serve(query: Query,
    predictedResults: Seq[PredictedResult]): PredictedResult = {

    @transient lazy val logger = Logger[this.type]

    val standard: Seq[Array[ItemScore]] = if (query.limit - query.skip == 1) {
      // 쿼리 아이템 갯수가 1이면, 표준화 안함
      predictedResults.map(_.itemScores)
    } else {
      // combine 하기 전에 표준화
      val mvList: Seq[MeanAndVariance] = predictedResults.map { pr =>
        meanAndVariance(pr.itemScores.map(_.score))
      }

      predictedResults.zipWithIndex
        .map { case (pr, i) =>
          pr.itemScores.map { is =>
            // 표준화 점수(z-score)
            // 만약 표준편차가 0이면 = 모든 score가 같다면
            // 0을 리턴함
            val score = if (mvList(i).stdDev == 0) {
              0
            } else {
              (is.score - mvList(i).mean) / mvList(i).stdDev
            }

            ItemScore(is.item, score)
          }
        }
    }

    // 같은 item에 대해 Collaborative Filtering Recommender의 결과값과 
    // Content Based Filtering Recommender의 결과값을 합산, 정렬함
    val combined = standard.flatten // ItemScore 배열
      .groupBy(_.item) // 같은 item id끼리 묶음
      .mapValues(itemScores => itemScores.map(_.score).reduce(_ + _))
      .toArray // (item id, score) 배열
      .sortBy(_._2)(Ordering.Double.reverse)
      .slice(query.skip, query.limit)
      .map { case (k,v) => ItemScore(k, v) }
    
    if (!combined.isEmpty) logger.info(s"Recommendation result for user ${query.user} is successfully sent to the user.")

    new PredictedResult(combined)
  }
}