docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payments.requested \
  --from-beginning --property print.key=true --property print.partition=true