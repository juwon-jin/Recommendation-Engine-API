"""
Import sample data for E-Commerce Recommendation Engine Template
"""

import predictionio
import argparse
import random

SEED = 3

def import_events(client):
  random.seed(SEED)
  count = 0
  print client.get_status()
  print "Importing data..."

  # generate 20 users, with user ids u1,u2,....,u20
  user_ids = ["u%s" % i for i in range(1, 21)]
  for user_id in user_ids:
    print "Set user", user_id
    client.create_event(
      event="$set",
      entity_type="user",
      entity_id=user_id
    )
    count += 1

  # generate 100 items, with item ids i1,i2,....,i100
  sampleCategories = ["c%s" % i for i in range(1, 5)]
  #sampleFeelings = ["f%s" % i for i in range(1, 10)]
  sampleFeelings = ["f%s" % i for i in range(1, 3)]
  item_ids = ["i%s" % i for i in range(1, 101)]
  for item_id in item_ids:
    title = "recipe " + item_id
    categories = random.sample(sampleCategories, 1)
    #feelings = random.sample(sampleFeelings, random.randint(1, 3))
    feelings = random.sample(sampleFeelings, 2)
    cooktime = 30 #random.randint(10, 50)
    calories = 30 #random.randint(100, 700)
    expire = 30 #random.randint(1, 15)
    print "Set item", item_id, "= title:", title, ", category:", categories, ", feelings:", feelings, ", cooktime:", cooktime, ", calories: ", calories, ", expire: ", expire
    client.create_event(
      event="$set",
      entity_type="item",
      entity_id=item_id,
      properties={
        "title" : title,
        "categories" : categories,
        "feelings" : feelings,
        "cooktime" : cooktime,
        "calories" : calories,
        "expire" : expire
      }
    )
    count += 1

  # each user randomly viewed 10 items
  for user_id in user_ids:
    for viewed_item in random.sample(item_ids, 10):
      print "User", user_id ,"views item", viewed_item
      client.create_event(
        event="view",
        entity_type="user",
        entity_id=user_id,
        target_entity_type="item",
        target_entity_id=viewed_item
      )
      count += 1
      # randomly like some of the viewed items
      if random.choice([True, False]):
        print "User", user_id ,"likes item", viewed_item
        client.create_event(
          event="like",
          entity_type="user",
          entity_id=user_id,
          target_entity_type="item",
          target_entity_id=viewed_item
        )
        count += 1

        # randomly cancel like some of the viewed items
        if random.choice([True, False]):
          print "User", user_id ,"cancel_like item", viewed_item
          client.create_event(
            event="cancel_like",
            entity_type="user",
            entity_id=user_id,
            target_entity_type="item",
            target_entity_id=viewed_item
          )
          count += 1

  print "%s events are imported." % count

if __name__ == '__main__':
  parser = argparse.ArgumentParser(
    description="Import sample data for e-commerce recommendation engine")
  parser.add_argument('--access_key', default='LkOTv7EL7rrV93Y5iTUQTvPoM13NbI1L8wbkPsLRKM4mjRn4KeFWBwQSLKTnTN5G')
  parser.add_argument('--url', default="http://localhost:7070")

  args = parser.parse_args()
  print args

  client = predictionio.EventClient(
    access_key=args.access_key,
    url=args.url,
    threads=5,
    qsize=500)
  import_events(client)
