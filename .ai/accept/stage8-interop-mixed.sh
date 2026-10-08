#!/usr/bin/env bash
# 阶段 8 第 8 步验收 · ③ 混合部署剧本：SSE 只推本进程连接（架构事实，不是 bug）
#
# 前置：Java API(8081) 在跑；Go 参考实现(8080) 在跑；RabbitMQ 在跑（Go 的通知 worker 靠它）。
# 剧本：B 的 SSE 挂在 Java 侧(8081)；A 用 Go 版(8080)点赞 B 的视频 →
#   1) B 收不到实时推送（A 的事件走 Go 的 RabbitMQ，Java 进程根本看不到）；
#   2) B 在 Java 侧的 /notification/list 里能看到通知（Go 的 worker 落库到共享 MySQL）。
# 副作用：注册探针账号 A/B、造一条探针视频，结束清理探针行（账号保留）。
#
# 用法：bash .ai/accept/stage8-interop-mixed.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
GO=http://127.0.0.1:8080
COMPOSE="docker compose -f deploy/docker-compose.yml"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -e "$1" -N -B 2>/dev/null | tail -1; }

echo "== 0) 前置：两边都活着 =="
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "Java API healthz 200"; else bad "Java API healthz=$C（<leader>sr）"; exit 1; fi
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $GO/healthz)
if [ "$C" = "200" ]; then ok "Go 版 healthz 200"; else bad "Go 版 healthz=$C（8080 没跑？）"; exit 1; fi

echo "== 1) 探针账号 A、B（B 登 Java、A 登 Go——顺带验共享账号）=="
UA="mixA_$(date +%s)"; UB="mixB_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" >/dev/null
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" >/dev/null
TB=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" | jq -r '.token // empty')
TG=$(curl -s --noproxy '*' -XPOST $GO/account/login -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$TB" ] && [ -n "$TG" ]; then ok "B 在 Java 侧、A 在 Go 侧登录成功（共享库 + 同款哈希）"; else bad "登录失败（TB/TG 为空）"; exit 1; fi
IDA=$(q "SELECT id FROM accounts WHERE username='$UA'")
IDB=$(q "SELECT id FROM accounts WHERE username='$UB'")

echo "== 2) 造 B 的视频 V =="
q "INSERT INTO videos (author_id, username, title, play_url, cover_url, create_time) VALUES ($IDB, '$UB', 'mixed probe', '/static/videos/probe.mp4', '/static/covers/probe.jpg', NOW(3))"
V=$(q "SELECT MAX(id) FROM videos WHERE author_id=$IDB")
if [ -n "$V" ]; then ok "探针视频 V=$V（作者=B）"; else bad "视频创建失败"; exit 1; fi

echo "== 3) B 在 Java 侧挂 SSE（后台，最长 30 秒）=="
OUT=$(mktemp)
curl -sN -m 30 --noproxy '*' "$API/notification/stream?token=$TB" > "$OUT" 2>/dev/null &
STREAM_PID=$!
sleep 1

echo "== 4) A 用 Go 版（8080）点赞 V =="
C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $GO/like/like -H "Authorization: Bearer $TG" -H 'content-type: application/json' -d "{\"video_id\":$V}")
if [ "$C" = "200" ]; then ok "Go 侧点赞 200"; else bad "Go 侧点赞 code=$C"; fi

echo "== 5) 等通知链走完（Go 的 RabbitMQ worker → 共享 MySQL）=="
SEEN=""
for i in $(seq 1 20); do
  if curl -s --noproxy '*' -XPOST $API/notification/list -H "Authorization: Bearer $TB" -H 'content-type: application/json' -d '{}' | grep -q "\"target_id\":$V"; then SEEN=1; break; fi
  sleep 0.5
done

if [ -n "$SEEN" ]; then ok "Java 侧 /notification/list 里出现了这条点赞通知（共享 MySQL 可见）"; else bad "10 秒内 /notification/list 没看到通知"; fi
if grep -q '"type":"like"' "$OUT" 2>/dev/null; then bad "B 不该收到实时推送，却收到了（SSE 跨进程推送？）"; else ok "B 的流没收到任何推送（SSE 只推本进程连接，架构事实成立）"; fi
BODY=$(curl -s --noproxy '*' -XPOST $API/notification/list -H "Authorization: Bearer $TB" -H 'content-type: application/json' -d '{}')
if echo "$BODY" | grep -q '点赞了你的视频'; then ok "通知内容逐字「点赞了你的视频」"; else bad "通知 content 文案不对：$(echo "$BODY" | head -c 200)"; fi

echo "== 6) 清理探针 =="
kill $STREAM_PID 2>/dev/null
wait $STREAM_PID 2>/dev/null
rm -f "$OUT"
q "DELETE FROM notifications WHERE recipient_id IN ($IDA,$IDB) OR sender_id IN ($IDA,$IDB)"
q "DELETE FROM likes WHERE video_id=$V"
q "DELETE FROM videos WHERE id=$V"
ok "探针行已清理（账号保留）"

echo
echo "== 结果：$FAILS 项失败 =="
echo "说明：这条验证的是架构事实——A 的事件走 Go 的 RabbitMQ，Java 进程看不到它，所以不推给 B；落库在共享 MySQL，所以列表可见。结论会写进 README。"
exit $FAILS
