# 레시피 추천 알고리즘

## 설명

PredictionIO의 'E-Commerce Recommendation Engine Template'과 'Similar Product Engine Template'을 결합, 수정, 보완하여 구현한 Collaborative Filtering Algorithm과
이를 보완하기 위해 저희가 구현한 Content Based Filtering Algorithm을 유기적으로 합친 Recommender입니다.


이 엔진은 다음과 같은 기능들을 제공합니다.
Collaborative Filtering Algorithm과 Content Based Filtering Algorithm은 독립적으로 수행되는것이 아니라, 하나의 쿼리에 두 알고리즘이 동시에 수행됩니다. 그리고 이렇게 해서 나온 각각의 결과값을 자동으로 합산하여 나온 최종 결과값을 반환하게 됩니다.


### Collaborative Filtering Recommender

* 기존 유저의 최근 행동 패턴을 기반으로 비슷한(선호할만한) 아이템을 추천
* 기존 유저의 행동 패턴과 비슷한 아이템이 없을 경우 대중적으로 인기있는 아이템을 추천
* 새로운 유저에게는 대중적으로 인기있는 아이템을 추천
* 보지 않은 아이템만 추천할 수 있음 (optional)
* 카테고리, 식감, 화이트리스트, 블랙리스트 필터링 가능 (optional)
* 아이템을 일시 접근불가 설정 가능 (optional)
* 페이징 기능

### Content Based Filtering Recommender

* 기존 유저가 최근 'view' 또는 'like'를 한 아이템의 attributes와 비슷한 아이템을 추천
* 보지 않은 아이템만 추천할 수 있음 (optional)
* 카테고리, 식감, 화이트리스트, 블랙리스트 필터링 가능 (optional)
* 아이템을 일시 접근불가 설정 가능 (optional)
* 페이징 기능


## 사용법

### Event Data Requirements

* Item $set event, which sets the attributes of the item
* Users' view events
* Users' like events
* Users' cancel_like events
* Constraint unavailable set events

### Input Query

* UserID
* Start num of items to be recommended (skip)
* End num of items to be recommended (limit)
* List of white-listed item categories (optional)
* List of white-listed item feelings (optional)
* List of white-listed itemIDs (optional)
* List of black-listed itemIDs (optional)

### Output PredictedResult

* A ranked list of recommended itemIDs

## Sending data & query example

send_data.py:

```

import predictionio
import argparse
import random

SEED = 3

def send_event(client):
  random.seed(SEED)
  count = 0
  print client.get_status()
  print "Sending data..."

  client.create_event(
    event="$set",
    entity_type="user",
    entity_id="u21"
  )

  client.create_event(
      event="$set",
      entity_type="item",
      entity_id="i1",
      properties={
        "title" : "t1",
        "categories" : "c3",
        "feelings" : ["f3", "f5"],
        "cooktime" : 30,
        "calories" : 250,
        "expire" : 14
      }
    )

  client.create_event(
    event="$set",
    entity_type="constraint",
    entity_id="unavailableItems",
    properties={
      "items" : ["i4", "i14", "i11"]
    }
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="u7",
    target_entity_type="item",
    target_entity_id="i1"
  )

  client.create_event(
    event="like",
    entity_type="user",
    entity_id="u7",
    target_entity_type="item",
    target_entity_id="i1"
  )

  client.create_event(
    event="cancel_like",
    entity_type="user",
    entity_id="u7",
    target_entity_type="item",
    target_entity_id="i1"
  )

  print "Complete"


if __name__ == '__main__':
  parser = argparse.ArgumentParser(
    description="Import sample data for Recipe Recommendation Engine")
  parser.add_argument('--access_key', default='<Your Access Key>')
  parser.add_argument('--url', default="http://localhost:7070")

  args = parser.parse_args()
  print args

  client = predictionio.EventClient(
    access_key=args.access_key,
    url=args.url,
    threads=5,
    qsize=500)
  send_event(client)

```


send-query.py:

```

import predictionio
engine_client = predictionio.EngineClient(url="http://localhost:8000")

print "Sending query..."

print engine_client.send_query(
  {
    "user": "u10", 
    "skip": 15,
    "limit": 30
  }
)

print engine_client.send_query(
  {
    "user": "u11",
    "skip": 0,
    "limit": 15,
    "categories": ["c4", "c3"],
    "feelings": [f1, f5],
    "whiteList": ["i1", "i23", "i26", "i31"],
    "blackList": ["i21", "i25", "i30"]
  }
)

```

