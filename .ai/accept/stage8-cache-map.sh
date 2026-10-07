#!/usr/bin/env bash
# 阶段 8 第 7 步验收：缓存地图收口（FLUSHDB 生存性 + 点赞两键同消 + 窗口/快照实测）
#
# 前置（脚本自检第 0 项）：API(8081) 在跑（<leader>sr）；Worker 非必需。
# 副作用：**FLUSHDB 清空本项目 Redis**（内容全是可重建物：缓存/锁/限流计数；登录态走 DB 兜底，不受影响）；
#   注册探针账号 A/B、插一条探针视频，结束清掉探针行（账号保留）。清空后第一波请求回源/重建，属预期。
#
# 用法：bash .ai/accept/stage8-cache-map.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -e "$1" -N -B 2>/dev/null | tail -1; }
r(){ $COMPOSE exec -T redis redis-cli --no-auth-warning -a 123456 "$@" 2>/dev/null | tr -d '\r'; }

echo "== 0) 前置：API 健康 =="
CODE=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$CODE" = "200" ]; then ok "healthz 200"; else bad "healthz=$CODE（API 没起？<leader>sr）"; exit 1; fi

echo "== 1) 探针账号 A、B（先拿好 token，再做 FLUSHDB 验 DB 兜底）=="
UA="sse7a_$(date +%s)"; UB="sse7b_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" >/dev/null
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" >/dev/null
TA=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UA\",\"password\":\"password123\"}" | jq -r '.token // empty')
TB=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UB\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$TA" ] && [ -n "$TB" ]; then ok "A、B 登录拿到 token"; else bad "探针登录失败"; exit 1; fi
IDA=$(q "SELECT id FROM accounts WHERE username='$UA'")
IDB=$(q "SELECT id FROM accounts WHERE username='$UB'")

echo "== 2) FLUSHDB：缓存全清，登录态与两域可用性靠 DB 兜底 =="
r FLUSHDB >/dev/null
ok "FLUSHDB 执行完成"
C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/notification/list -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d '{}')
[ "$C" = "200" ] && ok "通知读端仍可用（老 token，DB 兜底 + 缓存回填）" || bad "通知读端 code=$C"
C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/message/send -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d "{\"to_id\":$IDB,\"content\":\"probe after flush\"}")
[ "$C" = "200" ] && ok "私信发送仍可用" || bad "私信发送 code=$C"
R=$(curl -s -m 5 --noproxy '*' -XPOST $API/message/list -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d "{\"peer_id\":$IDB}")
echo "$R" | jq -e 'has("messages")' >/dev/null 2>&1 && ok "私信列表仍可用" || bad "私信列表响应异常：$R"

echo "== 3) 造探针视频 V（B 作者，A 来赞）=="
q "INSERT INTO videos (author_id, username, title, play_url, cover_url, create_time) VALUES ($IDB, '$UB', 'cache probe', '/static/videos/probe.mp4', '/static/covers/probe.jpg', NOW(3))"
V=$(q "SELECT MAX(id) FROM videos WHERE author_id=$IDB")
if [ -n "$V" ]; then ok "探针视频 V=$V"; else bad "视频创建失败"; exit 1; fi

echo "== 4) 预热两个键：getDetail 写 detail；listLatest 触发 timeline 冷重建 + entity 异步回写 =="
curl -s --noproxy '*' -XPOST $API/video/getDetail -H 'content-type: application/json' -d "{\"id\":$V}" >/dev/null
curl -s --noproxy '*' -XPOST $API/feed/listLatest -H 'content-type: application/json' -d '{"limit":10}' >/dev/null
D=1; E=1
for i in $(seq 1 20); do
  [ "$(r EXISTS "v1:video:detail:id=$V")" = "1" ] && D=0
  [ "$(r EXISTS "v1:video:entity:$V")" = "1" ] && E=0
  if [ "$D" = "0" ] && [ "$E" = "0" ]; then break; fi
  sleep 0.2
done
if [ "$D" = "0" ] && [ "$E" = "0" ]; then ok "两个键已就位（detail + entity）"; else bad "键没就位：detail=$D entity=$E"; fi
Z=$(r ZCARD v1:feed:global_timeline)
if [ -n "$Z" ] && [ "$Z" -gt 0 ]; then ok "FLUSHDB 后 global_timeline 冷重建（ZCARD=$Z）"; else bad "timeline 冷重建失败（ZCARD=${Z:-空}）"; fi

echo "== 5) A 点赞 V → 两键应同时消失 + 热度窗口 +1 =="
RESP=$(curl -s --noproxy '*' -XPOST $API/like/like -H "Authorization: Bearer $TA" -H 'content-type: application/json' -d "{\"video_id\":$V}")
case "$RESP" in *"like success"*) ok "A 点赞成功";; *) bad "点赞响应异常：$RESP";; esac
GONE=1
for i in $(seq 1 20); do
  if [ "$(r EXISTS "v1:video:detail:id=$V")" = "0" ] && [ "$(r EXISTS "v1:video:entity:$V")" = "0" ]; then GONE=0; break; fi
  sleep 0.1
done
if [ "$GONE" = "0" ]; then ok "点赞后 detail 与 entity 两键同时消失（约 $((i*100))ms 内）"; else bad "两键未消失：detail=$(r EXISTS "v1:video:detail:id=$V") entity=$(r EXISTS "v1:video:entity:$V")"; fi

HOT=1
for k in $(r KEYS 'v1:hot:video:1m:*'); do
  SC=$(r ZSCORE "$k" "$V")
  if [ -n "$SC" ]; then HOT=0; echo "  （窗口 $k：ZSCORE=$SC）"; break; fi
done
if [ "$HOT" = "0" ]; then ok "热度窗口有 V 的 ZINCRBY 记录"; else bad "没找到热度窗口里的 V"; fi

echo "== 6) 热榜快照：listByPopularity 触发 ZUNIONSTORE 快照（TTL≈120s）=="
curl -s --noproxy '*' -XPOST $API/feed/listByPopularity -H 'content-type: application/json' -d '{"limit":10}' >/dev/null
SNAP=$(r KEYS 'v1:hot:video:merge:1m:*' | head -1)
if [ -n "$SNAP" ]; then ok "merge 快照已建（TTL=$(r TTL "$SNAP")s）：$SNAP"; else bad "merge 快照没出现"; fi

echo "== 7) 清理探针 =="
q "DELETE FROM notifications WHERE recipient_id IN ($IDA,$IDB) OR sender_id IN ($IDA,$IDB)"
q "DELETE FROM likes WHERE video_id=$V"
q "DELETE FROM videos WHERE id=$V"
q "DELETE FROM messages WHERE from_id IN ($IDA,$IDB) OR to_id IN ($IDA,$IDB)"
ok "探针行已清理（账号保留）"

echo
echo "== 结果：$FAILS 项失败 =="
echo "提示：本脚本把缓存清空过——之后第一波请求会重新回源/重建，属预期。"
exit $FAILS
