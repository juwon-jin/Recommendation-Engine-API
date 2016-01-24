
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
    entity_id="2"
  )

  client.create_event(
    event="$set",
    entity_type="user",
    entity_id="1"
  )

  # client.create_event(
  #   event="$set",
  #   entity_type="user",
  #   entity_id="u22"
  # )


  


  # client.create_event(
  #   event="view",
  #   entity_type="user",
  #   entity_id="u21",
  #   target_entity_type="item",
  #   target_entity_id="i1"
  # )

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
  parser.add_argument('--access_key', default='LkOTv7EL7rrV93Y5iTUQTvPoM13NbI1L8wbkPsLRKM4mjRn4KeFWBwQSLKTnTN5G')
  parser.add_argument('--url', default="http://localhost:7070")

  args = parser.parse_args()
  print args

  client = predictionio.EventClient(
    access_key=args.access_key,
    url=args.url,
    threads=5,
    qsize=500)
  send_event(client)