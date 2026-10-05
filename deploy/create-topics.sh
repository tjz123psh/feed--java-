#!/usr/bin/env bash
# 文件：deploy/create-topics.sh
set -e
cd "$(dirname "$0")"
BROKER=localhost:9092
create() {
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$BROKER" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
}
for t in like-events comment-events social-events video-popularity video-timeline; do
  create "$t" 3          # 业务 topic 3 分区
  create "$t.dlq" 1      # 死信队列 1 分区
done
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BROKER" --list
