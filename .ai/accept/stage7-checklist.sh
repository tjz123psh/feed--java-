#!/usr/bin/env bash
# 阶段 7 · 第 9 步：本阶段验收清单（六项全过才算完）
# 前置：API（8081）在跑、Worker 在跑、mysql/redis/kafka 容器健康、库里至少 6 个视频。
# 副作用（脚本会尽量清理，但会留下）：
#   - 若干探针账号 fe_c9_*（账号本身保留，点赞/评论行与计数会被清回去）
#   - **Worker 会被停掉再用脚本自己的方式重启为后台进程**（日志 /tmp/c9-worker.log，pid 在 /tmp/c9-worker.pid）
#     —— 你原来终端里的那个 Worker 会被 SIGTERM（看到优雅退出日志是预期），之后由脚本拉起的后台 Worker 继续干活。
#   - 第 5 项会把 v1:feed:global_timeline 填满探针后清空（DEL），推荐流下次请求会自动兜底重建。
# 预计耗时：4~6 分钟（第 4 项停/启 Kafka 最慢）。
# 用法：bash .ai/accept/stage7-checklist.sh   （退出码 = FAIL 数，0 为六项全过）

set -u
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
KAFKA="$COMPOSE exec -T kafka /opt/kafka/bin"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
mysql_q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -N -B -e "$1" 2>/dev/null; }
q(){ mysql_q "$1" | tail -1; }
redis_q(){ $COMPOSE exec -T redis redis-cli -a 123456 --no-auth-warning "$@" 2>/dev/null; }
register(){ curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' \
              -d "{\"username\":\"$1\",\"password\":\"password123\"}" >/dev/null; }
login(){ curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' \
           -d "{\"username\":\"$1\",\"password\":\"password123\"}" | jq -r '.token // empty'; }
like(){ curl -s --noproxy '*' -m 40 -XPOST $API/like/like -H "Authorization: Bearer $1" \
          -H 'content-type: application/json' -d "{\"video_id\":$2}"; }
wait_worker_gone(){
  for _ in $(seq 1 30); do pgrep -f 'io\.github\.p_feed\.WorkerApplication' >/dev/null || return 0; sleep 1; done
  return 1
}
start_worker(){
  ( cd "$ROOT" && setsid nohup ./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication \
      > /tmp/c9-worker.log 2>&1 & echo $! > /tmp/c9-worker.pid )
  for _ in $(seq 1 60); do grep -q "worker consumers started: 4" /tmp/c9-worker.log 2>/dev/null && return 0; sleep 2; done
  return 1
}
dlq_has(){ # topic marker → 0=找到
  $KAFKA/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$1" \
    --group "c9-$(date +%s%N)" --from-beginning --timeout-ms 3000 2>/dev/null | grep -qF "$2"
}
on_exit(){ $COMPOSE start kafka >/dev/null 2>&1 || true; rm -f /tmp/c9-probe.sql; }
trap on_exit EXIT

echo "== 0) 前置检查 =="
CODE=$(curl -s --noproxy '*' -m 3 -o /dev/null -w '%{http_code}' $API/actuator/health)
if [ "$CODE" != "000" ]; then ok "API 在跑（http=$CODE）"; else bad "API 不在跑，先 <leader>sr"; exit 1; fi
if pgrep -f 'io\.github\.p_feed\.WorkerApplication' >/dev/null; then ok "Worker 在跑"; else
  bad "Worker 不在跑：./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication"; exit 1; fi
$KAFKA/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && ok "Kafka 可用" || { bad "Kafka 不可用"; exit 1; }
VCOUNT=$(q "SELECT COUNT(*) FROM videos")
if [ "${VCOUNT:-0}" -ge 6 ]; then ok "视频数=$VCOUNT"; else bad "视频不足 6 个，无法完成第 2 项"; exit 1; fi

echo "== 1) 端到端点赞（复用 stage7-like-flow.sh）=="
if bash "$(dirname "$0")/stage7-like-flow.sh" > /tmp/c9-item1.log 2>&1; then
  ok "端到端：点赞 200 → 秒级落库、计数 +1、DLQ 空、LAG=0"
else
  bad "端到端验收失败，看 /tmp/c9-item1.log"
fi

echo "== 2) 积压回放：kill Worker → 点赞 5 次 → 重启 Worker → 恰好补落 5 条 =="
echo "  （你终端里的 Worker 会被 SIGTERM，优雅退出日志是预期）"
if pkill -TERM -f 'io\.github\.p_feed\.WorkerApplication' 2>/dev/null && wait_worker_gone; then ok "Worker 已停"; else bad "Worker 没停干净"; fi
U2="fe_c9_$(date +%s)"; register "$U2"; T2=$(login "$U2"); A2=$(q "SELECT id FROM accounts WHERE username='$U2'")
mapfile -t VIDS < <(mysql_q "SELECT id FROM videos ORDER BY id")
PICK=("${VIDS[@]:1:5}")   # 跳过第 1 个（第 1 项用过），取 5 个
declare -A BEFORE=()
for v in "${PICK[@]}"; do BEFORE[$v]=$(q "SELECT likes_count FROM videos WHERE id=$v"); done
N_OK=0
for v in "${PICK[@]}"; do
  R=$(like "$T2" "$v")
  case "$R" in *"like success"*) N_OK=$((N_OK+1));; *) echo "      点赞 $v 响应异常：$R";; esac
done
[ "$N_OK" = "5" ] && ok "Worker 停着，5 次点赞全部 200（事件挂起在 topic）" || bad "只有 $N_OK/5 次点赞成功"
sleep 1
PEND=$(q "SELECT COUNT(*) FROM likes WHERE account_id=$A2")
[ "$PEND" = "0" ] && ok "此刻库里 0 行（异步挂起，符合预期）" || bad "Worker 停着却已有 $PEND 行"
if start_worker; then ok "Worker 已重启（后台，日志 /tmp/c9-worker.log）"; else bad "Worker 重启失败，看 /tmp/c9-worker.log"; fi
for _ in $(seq 1 30); do [ "$(q "SELECT COUNT(*) FROM likes WHERE account_id=$A2")" = "5" ] && break; sleep 1; done
LANDED=$(q "SELECT COUNT(*) FROM likes WHERE account_id=$A2")
[ "$LANDED" = "5" ] && ok "5 条积压全部补落" || bad "只补落 $LANDED/5 条"
INC_OK=1
for v in "${PICK[@]}"; do
  NOWC=$(q "SELECT likes_count FROM videos WHERE id=$v")
  [ "$NOWC" = "$((BEFORE[$v]+1))" ] || { INC_OK=0; echo "      video $v：${BEFORE[$v]} → $NOWC（应为 +1）"; }
done
[ "$INC_OK" = "1" ] && ok "5 个视频 likes_count 各恰好 +1" || bad "计数不对"
# 清理：探针账号的点赞行 + 计数改回
for v in "${PICK[@]}"; do
  mysql_q "UPDATE videos SET likes_count=GREATEST(likes_count-1,0), popularity=GREATEST(popularity-1,0) WHERE id=$v" >/dev/null
done
mysql_q "DELETE FROM likes WHERE account_id=$A2" >/dev/null

echo "== 3) 毒消息：notjson 进 DLQ、后续消息不受影响 =="
N1="notjson-c9-$(date +%s)"
printf '%s\n' "$N1" | $COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic like-events >/dev/null 2>&1
FOUND=0
for _ in $(seq 1 15); do dlq_has like-events.dlq "$N1" && { FOUND=1; break; }; sleep 1; done
[ "$FOUND" = "1" ] && ok "毒消息已进 like-events.dlq" || bad "毒消息没进 DLQ"
N2="evt-c9-$(date +%s)"
printf '{"event_id":"%s","action":"like","user_id":1,"video_id":999000777,"occurred_at":"2026-10-03T00:00:00Z"}\n' "$N2" \
  | $COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic like-events >/dev/null 2>&1
sleep 4
if dlq_has like-events.dlq "$N2"; then bad "后续正常消息也进了 DLQ（队列被卡？）"; else ok "后续正常消息未被牵连（队列未卡）"; fi
LAGC=$($KAFKA/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cg-video-write 2>/dev/null \
  | awk '$2=="like-events" {s+=$6} END {print s+0}')
[ "$LAGC" = "0" ] && ok "cg-video-write 对 like-events LAG 已归零" || bad "LAG=$LAGC"
$KAFKA/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic like-events.dlq >/dev/null 2>&1
$KAFKA/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic like-events.dlq \
  --partitions 1 --replication-factor 1 >/dev/null 2>&1 && echo "  （已清空 like-events.dlq：删掉重建）"

echo "== 4) broker 宕机降级：停 Kafka → 同步路径 → 起 Kafka → 自动恢复 =="
$COMPOSE stop kafka >/dev/null 2>&1
for _ in $(seq 1 20); do $KAFKA/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 || break; sleep 1; done
echo "  （Kafka 已停。降级路径每次投递要踩 2 秒截止，点赞/评论响应会慢 4~6 秒，属预期）"
U4="fe_c9d_$(date +%s)"; register "$U4"; T4=$(login "$U4"); A4=$(q "SELECT id FROM accounts WHERE username='$U4'")
VD="${VIDS[6]}"; VC="${VIDS[0]}"
B4=$(q "SELECT likes_count FROM videos WHERE id=$VD")
R4=$(like "$T4" "$VD")
case "$R4" in *"like success"*) ok "Kafka 停了，点赞仍 200（同步降级）";; *) bad "降级点赞异常：$R4";; esac
I4=$(q "SELECT COUNT(*) FROM likes WHERE account_id=$A4 AND video_id=$VD")
[ "$I4" = "1" ] && ok "点赞行【立即】落库" || bad "点赞行未立即落库（COUNT=$I4）"
C4=$(q "SELECT likes_count FROM videos WHERE id=$VD")
[ "$C4" = "$((B4+1))" ] && ok "likes_count 立即 +1（$B4→$C4）" || bad "likes_count 不对（$B4→$C4）"
CM="c9-comment-$(date +%s)"
RC=$(curl -s --noproxy '*' -m 40 -XPOST $API/comment/publish -H "Authorization: Bearer $T4" \
  -H 'content-type: application/json' -d "{\"video_id\":$VC,\"content\":\"$CM\"}")
case "$RC" in *"comment published successfully"*) ok "Kafka 停了，评论仍 200（同步降级）";; *) bad "降级评论异常：$RC";; esac
CC=$(q "SELECT COUNT(*) FROM comments WHERE author_id=$A4 AND content='$CM'")
[ "$CC" = "1" ] && ok "评论行【立即】落库" || bad "评论行未立即落库（COUNT=$CC）"
$COMPOSE start kafka >/dev/null 2>&1
READY=0
for _ in $(seq 1 60); do $KAFKA/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1 && { READY=1; break; }; sleep 2; done
[ "$READY" = "1" ] && ok "Kafka 已重新拉起" || bad "Kafka 起不来"
sleep 3
VR="${VIDS[0]}"
RR=$(like "$T4" "$VR")
case "$RR" in *"like success"*) ok "恢复后点赞 200";; *) bad "恢复后点赞异常：$RR";; esac
R1=0
for _ in $(seq 1 15); do [ "$(q "SELECT COUNT(*) FROM likes WHERE account_id=$A4 AND video_id=$VR")" = "1" ] && { R1=1; break; }; sleep 1; done
[ "$R1" = "1" ] && ok "恢复后由消费者补落（异步链路恢复）" || bad "恢复后 15 秒内没落库"
# 清理第 4 项
mysql_q "UPDATE videos SET likes_count=GREATEST(likes_count-1,0), popularity=GREATEST(popularity-1,0) WHERE id IN ($VD,$VR)" >/dev/null
mysql_q "DELETE FROM likes WHERE account_id=$A4" >/dev/null
mysql_q "UPDATE videos SET popularity=GREATEST(popularity-1,0) WHERE id=$VC" >/dev/null
mysql_q "DELETE FROM comments WHERE author_id=$A4 AND content='$CM'" >/dev/null

echo "== 5) outbox 裁剪：连发 1005 条 → ZCARD 恒为 1000 =="
{
  printf 'INSERT INTO outbox_msgs(video_id, event_type, create_time, status) VALUES'
  for i in $(seq 0 1004); do
    [ "$i" -gt 0 ] && printf ','
    printf "(%d,'video_published',NOW(3),'pending')" $((1002001+i))
  done
  printf ';\n'
} > /tmp/c9-probe.sql
$COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem < /tmp/c9-probe.sql 2>/dev/null
echo "  （1005 条已入 outbox；轮询器每轮 100 条，预计 10~20 秒投完）"
DRAINED=0
for _ in $(seq 1 90); do [ "$(q "SELECT COUNT(*) FROM outbox_msgs WHERE status='pending'")" = "0" ] && { DRAINED=1; break; }; sleep 2; done
[ "$DRAINED" = "1" ] && ok "1005 条全部投递（pending=0，行被删）" || bad "outbox 没投完"
ZC=$(redis_q ZCARD v1:feed:global_timeline)
[ "$ZC" = "1000" ] && ok "ZCARD=1000（裁剪只留最新 1000）" || bad "ZCARD=$ZC，应为 1000"
# 清理：探针行 + 时间线（下次推荐流请求会兜底重建）
mysql_q "DELETE FROM outbox_msgs WHERE video_id BETWEEN 1002001 AND 1003005" >/dev/null
redis_q DEL v1:feed:global_timeline >/dev/null
SMOKE=$(curl -s --noproxy '*' -m 10 -XPOST $API/feed/listLatest -H 'content-type: application/json' -d '{"limit":10}')
case "$SMOKE" in *'"video_list":'*) ok "推荐流冒烟 OK（兜底重建可用）";; *) bad "推荐流冒烟异常：$(echo "$SMOKE" | head -c 120)";; esac

echo "== 6) 消费组证明：cg-video-write 与 cg-timeline 各有进度 =="
for g in cg-video-write cg-timeline; do
  OUT=$($KAFKA/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$g" 2>/dev/null)
  # describe 输出开头有空行 + 表头：按第 1 列等于组名，只数真正的分区行
  LAG=$(awk -v g="$g" '$1==g {s+=$6} END {print s+0}' <<<"$OUT")
  MEM=$(awk -v g="$g" '$1==g && $7!="" && $7!="-" {c++} END {print c+0}' <<<"$OUT")
  ROWS=$(awk -v g="$g" '$1==g {c++} END {print c+0}' <<<"$OUT")
  if [ "$ROWS" -gt 0 ] && [ "$LAG" = "0" ] && [ "$MEM" -gt 0 ]; then
    ok "$g：$ROWS 个分区行、$MEM 行有 CONSUMER-ID、总 LAG=0"
  else
    bad "$g：rows=$ROWS members=$MEM LAG=$LAG"
  fi
done

echo
if [ "$FAILS" = 0 ]; then
  echo "===== 阶段 7 验收清单：六项全过 ====="
  echo "（后台 Worker 仍在跑：日志 /tmp/c9-worker.log，pid 文件 /tmp/c9-worker.pid；"
  echo "  想收回来：kill \$(cat /tmp/c9-worker.pid) 后在你自己的终端重新起）"
else
  echo "===== FAIL 条数 = $FAILS ====="
fi
exit "$FAILS"
