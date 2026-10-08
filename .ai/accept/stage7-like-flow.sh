#!/usr/bin/env bash
# 阶段 7 第 7 步验收：点赞走 Kafka 异步链路 → 秒级落库、计数正确；DLQ 空；cg-video-write 有进度。
#
# 前置（脚本会自检第 0 项）：API(8081) 在跑、Worker 在跑、mysql/redis/kafka 容器健康。
# 副作用：新注册一个探针账号（fe_step7_<时间戳>）并点赞一次；不动任何已有数据。
#   ——特意用全新账号：老账号可能"已赞过"，会被接口按 500 拦下。
#
# 用法：bash .ai/accept/stage7-like-flow.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
KAFKA="$COMPOSE exec -T kafka /opt/kafka/bin"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -e "$1" -N -B 2>/dev/null | tail -1; }

echo "== 0) 前置：Worker 在跑吗（看 cg-video-write 的活跃成员；空组只是'存在过'，不能只看组在不在）=="
STATE=$($KAFKA/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cg-video-write --state 2>/dev/null)
MEM=$(printf '%s\n' "$STATE" | awk '$1=="cg-video-write" {print $NF}' | tail -1)
case "$MEM" in ''|*[!0-9]*) MEM=0 ;; esac
if [ "$MEM" -gt 0 ]; then
  ok "cg-video-write 活跃成员 $MEM 个（Worker 在跑）"
else
  bad "cg-video-write 活跃成员 0 个 —— Worker 没起（Kafka 没起也会落到这里）"
  echo
  echo "先起 Worker（另开一个终端）："
  echo "  ./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication"
  echo "看到 4 行 consumer started + 'worker consumers started: 4' 后再跑本脚本。"
  exit 1
fi

echo "== 1) 注册并登录一个全新探针账号 =="
U="fe_step7_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" >/dev/null
TOKEN=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$TOKEN" ]; then ok "登录拿到 token（$U）"; else bad "登录失败"; exit 1; fi
AID=$(q "SELECT id FROM accounts WHERE username='$U'")

echo "== 2) 选一个视频并记录点赞前基线 =="
VID=$(q "SELECT id FROM videos ORDER BY id LIMIT 1")
B_CNT=$(q "SELECT likes_count FROM videos WHERE id=$VID")
B_POP=$(q "SELECT popularity FROM videos WHERE id=$VID")
echo "  video=$VID account=$AID 基线：likes_count=$B_CNT popularity=$B_POP"

echo "== 3) 点赞（期望 like success）=="
RESP=$(curl -s --noproxy '*' -XPOST $API/like/like -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' -d "{\"video_id\":$VID}")
echo "  响应：$RESP"
case "$RESP" in
  *"like success"*) ok "点赞 200" ;;
  *) bad "点赞响应异常" ;;
esac

echo "== 4) 轮询落库（最多 5 秒）=="
DEADLINE=$((SECONDS+5)); ROW=0
while [ $SECONDS -lt $DEADLINE ]; do
  ROW=$(q "SELECT COUNT(*) FROM likes WHERE video_id=$VID AND account_id=$AID")
  [ "$ROW" = "1" ] && break
  sleep 0.3
done
if [ "$ROW" = "1" ]; then ok "likes 行已落库（≤5s）"; else bad "likes 行没落（COUNT=$ROW）"; fi
A_CNT=$(q "SELECT likes_count FROM videos WHERE id=$VID")
A_POP=$(q "SELECT popularity FROM videos WHERE id=$VID")
if [ "$A_CNT" = "$((B_CNT+1))" ]; then ok "likes_count +1（$B_CNT→$A_CNT）"; else bad "likes_count 不对（$B_CNT→$A_CNT）"; fi
if [ "$A_POP" = "$((B_POP+1))" ]; then ok "popularity +1（$B_POP→$A_POP）"; else bad "popularity 不对（$B_POP→$A_POP）"; fi

echo "== 5) DLQ 应空 + cg-video-write LAG=0 =="
DLQ=$($KAFKA/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic like-events.dlq \
      --group "fe_step7_dlq_$(date +%s)" --from-beginning --timeout-ms 3000 2>/dev/null | wc -l)
if [ "$DLQ" = "0" ]; then ok "like-events.dlq 无消息"; else bad "DLQ 里有 $DLQ 条"; fi
LAG=$($KAFKA/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cg-video-write 2>/dev/null \
      | awk '$2=="like-events" {s+=$6} END {print s+0}')
if [ "$LAG" = "0" ]; then ok "cg-video-write 对 like-events LAG=0"; else bad "LAG=$LAG（可能还没消费完）"; fi

echo
if [ $FAILS = 0 ]; then echo "===== 阶段 7 第 7 步验收：全绿 ====="; else echo "===== FAIL 条数 = $FAILS ====="; fi
exit $FAILS
