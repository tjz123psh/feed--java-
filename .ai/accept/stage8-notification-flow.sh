#!/usr/bin/env bash
# 阶段 8 第 6 步验收：接上 cg-notification —— 点赞真实产生通知并 SSE 推给作者
#
# 前置（脚本自检）：API(8081) 在跑（<leader>sr）；Worker 非必需——通知链在 API 进程内，
#   本脚本不检查 likes 行，把 Worker 停掉复跑照样全绿（两组独立推进）。
# 首次带第 6 步代码启动 API 时，cg-notification 会把三个 topic 的存量重放一遍（本机约 15 条），属预期。
# 副作用：注册探针账号 A/B 并各插一条探针视频；结束时清掉探针视频/点赞/通知行（账号保留）。
#
# 用法：bash .ai/accept/stage8-notification-flow.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
STREAM=$API/notification/stream
COMPOSE="docker compose -f deploy/docker-compose.yml"
KAFKA="$COMPOSE exec -T kafka /opt/kafka/bin"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -e "$1" -N -B 2>/dev/null | tail -1; }

echo "== 0) 前置：API 健康 + 消费组 cg-notification 已挂上 =="
CODE=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$CODE" = "200" ]; then ok "healthz 200"; else bad "healthz=$CODE（API 没起？<leader>sr）"; exit 1; fi
GRP=1
for i in 1 2 3 4 5 6 7 8 9 10; do
  if $KAFKA/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cg-notification >/dev/null 2>&1; then GRP=0; break; fi
  sleep 1
done
if [ "$GRP" = "0" ]; then ok "消费组 cg-notification 存在（三个通知消费者在 API 进程内）"; else bad "cg-notification 不存在——第 6 步的消费者没起，或 API 没重启"; fi

echo "== 1) 探针账号 A、B =="
UA="sse6a_$(date +%s)"; UB="sse6b_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" >/dev/null
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" >/dev/null
TA=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" | jq -r '.token // empty')
TB=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$TA" ] && [ -n "$TB" ]; then ok "A、B 登录拿到 token"; else bad "探针登录失败"; exit 1; fi
IDA=$(q "SELECT id FROM accounts WHERE username='$UA'")
IDB=$(q "SELECT id FROM accounts WHERE username='$UB'")

echo "== 2) 造探针视频（B 的给 A 赞；A 的给自赞）=="
q "INSERT INTO videos (author_id, username, title, play_url, cover_url, create_time) VALUES ($IDB, '$UB', 'sse probe B', '/static/videos/probe.mp4', '/static/covers/probe.jpg', NOW(3))"
q "INSERT INTO videos (author_id, username, title, play_url, cover_url, create_time) VALUES ($IDA, '$UA', 'sse probe A', '/static/videos/probe.mp4', '/static/covers/probe.jpg', NOW(3))"
VB=$(q "SELECT MAX(id) FROM videos WHERE author_id=$IDB")
VA=$(q "SELECT MAX(id) FROM videos WHERE author_id=$IDA")
if [ -n "$VB" ] && [ -n "$VA" ]; then ok "探针视频创建（B=$VB / A=$VA）"; else bad "视频创建失败"; exit 1; fi

echo "== 3) B 挂流（后台，最长 40 秒）=="
OUT=$(mktemp)
curl -sN -m 40 --noproxy '*' "$STREAM?token=$TB" > "$OUT" 2>/dev/null &
STREAM_PID=$!
sleep 1

echo "== 4) A 赞 B 的视频 → 秒级推送 + 落库 =="
RESP=$(curl -s --noproxy '*' -XPOST $API/like/like -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d "{\"video_id\":$VB}")
case "$RESP" in *"like success"*) ok "A 点赞成功";; *) bad "点赞响应异常：$RESP";; esac

FRAME=1
for i in $(seq 1 16); do
  if grep -q "\"recipient_id\":$IDB" "$OUT" 2>/dev/null && grep -q '"type":"like"' "$OUT" 2>/dev/null; then FRAME=0; break; fi
  sleep 0.5
done
if [ "$FRAME" = "0" ]; then ok "B 的流收到 data 帧（type=like、recipient_id=$IDB）"; else bad "8 秒内没收到 data 帧（当前内容：$(head -c 200 "$OUT" 2>/dev/null)）"; fi
if grep -q '点赞了你的视频' "$OUT" 2>/dev/null; then ok "帧里 content 逐字「点赞了你的视频」"; else bad "帧里 content 文案不对"; fi

ROWS=$(q "SELECT COUNT(*) FROM notifications WHERE recipient_id=$IDB AND sender_id=$IDA AND type='like' AND target_id=$VB AND is_read=0")
if [ "$ROWS" = "1" ]; then ok "notifications 落库 1 行（收件人=B、操作者=A、target=视频）"; else bad "落库行数=$ROWS（期望 1）"; fi

echo "== 5) A 赞自己的视频 → 不产生通知 =="
RESP2=$(curl -s --noproxy '*' -XPOST $API/like/like -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d "{\"video_id\":$VA}")
case "$RESP2" in *"like success"*) ok "自赞请求成功（事件已发出）";; *) bad "自赞请求异常：$RESP2";; esac
N=0
for i in $(seq 1 16); do
  N=$(q "SELECT COUNT(*) FROM notifications WHERE target_id=$VA")
  if [ "$N" != "0" ]; then break; fi
  sleep 0.5
done
if [ "$N" = "0" ]; then ok "自己赞自己：8 秒窗口内没有任何通知（操作者=作者被跳过）"; else bad "自己赞自己产生了通知（COUNT=$N）"; fi

echo "== 6) 清理探针 =="
kill $STREAM_PID 2>/dev/null
wait $STREAM_PID 2>/dev/null
rm -f "$OUT"
q "DELETE FROM notifications WHERE recipient_id IN ($IDA,$IDB) OR sender_id IN ($IDA,$IDB)"
q "DELETE FROM likes WHERE video_id IN ($VA,$VB)"
q "DELETE FROM videos WHERE id IN ($VA,$VB)"
ok "探针视频/点赞/通知行已清理（账号保留）"

echo
echo "== 结果：$FAILS 项失败 =="
echo "提示：通知链不经过 cg-video-write——把 Worker 停掉复跑，本脚本照样全绿。"
exit $FAILS
