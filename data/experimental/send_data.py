
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
    entity_id="56ed1fa023c3ce5137a6791e"
  )

  # client.create_event(
  #   event="$set",
  #   entity_type="user",
  #   entity_id="1"
  # )

  # client.create_event(
  #   event="$set",
  #   entity_type="user",
  #   entity_id="u22"
  # )

  client.create_event(
    event="like",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d2f35242631d04affb"
  )




  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d2f35242631d04affb"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d2f35242631d04b02c"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d5f35242631d04b19e"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d6f35242631d04b1f2"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d9f35242631d04b34c"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19d9f35242631d04b366"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19e5f35242631d04b78e"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19f1f35242631d04bbed"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56dc7fe4bb869fd6cf633749"
  )

  client.create_event(
    event="view",
    entity_type="user",
    entity_id="56ed1fa023c3ce5137a6791e",
    target_entity_type="item",
    target_entity_id="56ed19f2f35242631d04bc58"
  )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u21",
  #   target_entity_type="item",
  #   target_entity_id="i3"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u21",
  #   target_entity_type="item",
  #   target_entity_id="i4"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u21",
  #   target_entity_type="item",
  #   target_entity_id="i6"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u21",
  #   target_entity_type="item",
  #   target_entity_id="i8"
  # )



  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i1"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i3"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i4"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i6"
  # )

  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i8"
  # )




  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i62"
  # )




  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u22",
  #   target_entity_type="item",
  #   target_entity_id="i17"
  # )

  # client.create_event(
  #   event="like",
  #   entity_type="user",
  #   entity_id="u1",
  #   target_entity_type="item",
  #   target_entity_id="i2"
  # )

  # client.create_event(
  #   event="cancel_like",
  #   entity_type="user",
  #   entity_id="u1",
  #   target_entity_type="item",
  #   target_entity_id="i2"
  # )

  print "Complete"


if __name__ == '__main__':
  parser = argparse.ArgumentParser(
    description="Import sample data for e-commerce recommendation engine")
  parser.add_argument('--access_key', default='FKN95k4MIda1bTnoTuHk3tSh8qdwnnhbpC3SGzhqMbrHR7s5eZ7lEP6hNhijQdeN')
  parser.add_argument('--url', default="http://40.83.122.139:7070")

  args = parser.parse_args()
  print args

  client = predictionio.EventClient(
    access_key=args.access_key,
    url=args.url,
    threads=5,
    qsize=500)
  send_event(client)
