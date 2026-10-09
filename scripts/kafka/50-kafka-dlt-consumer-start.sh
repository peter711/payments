#!/usr/bin/env bash
# Inspects records that ended up on the dead letter topic.
set -euo pipefail

docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payments.requested.DLT \
  --from-beginning --property print.key=true --property print.partition=true \
  --property print.headers=true
