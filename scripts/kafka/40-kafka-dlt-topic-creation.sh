docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic payments.requested.DLT --partitions 3 --replication-factor 1
