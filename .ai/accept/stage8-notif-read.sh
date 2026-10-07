#!/usr/bin/env bash
# 阶段 8 第 3 步验收：通知读端三接口（list / markRead / unreadCount）
#
# 前置（脚本自检第 0 项）：API(8081) 在跑（<leader>sr）、mysql 容器健康；Worker 不需要。
# 副作用：新注册探针账号（ntf_<时间戳>）并往 notifications 插 1~3 行探针；结束时删掉探针通知行。
#
# 用法：bash .ai/accept/stage8-notif-read.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
q(){ $COMPOSE exec -T mysql mysql -uroot -p123456 --default-character-set=utf8mb4 feedsystem -e "$1" -N -B 2>/dev/null | tail -1; }

echo "== 0) 前置：API 健康 =="
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$CODE" = "200" ]; then ok "healthz 200"; else bad "healthz=$CODE（API 没起？<leader>sr）"; exit 1; fi

echo "== 1) 注册并登录一个全新探针账号 =="
U="ntf_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" >/dev/null
TOKEN=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$TOKEN" ]; then ok "登录拿到 token（$U）"; else bad "登录失败"; exit 1; fi
AID=$(q "SELECT id FROM accounts WHERE username='$U'")
if [ -z "$AID" ]; then bad "拿不到探针账号 id"; exit 1; fi

echo "== 2) 手工造两条通知（未读 like + 已读 comment）并打 list =="
q "INSERT INTO notifications (recipient_id,sender_id,type,target_id,content,is_read,created_at) VALUES ($AID,2,'like',1,'点赞了你的视频',0,NOW(3)), ($AID,2,'comment',1,'评论了你的视频',1,NOW(3))"
RESP=$(curl -s --noproxy '*' -XPOST $API/notification/list -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}')
echo "  响应：$RESP"
if echo "$RESP" | jq -e '(.notifications | length) == 2
  and ([.notifications[] | has("recipient_id") and has("sender_id") and has("target_id") and has("is_read") and has("created_at")] | all)
  and ([.notifications[] | has("recipientId") or has("isRead") or has("createdAt")] | any | not)' >/dev/null; then
  ok "list：2 条、五项蛇形键齐全、无驼峰残留"
else
  bad "list 结构不对（长度或键名）"
fi
V=$(echo "$RESP" | jq -r '[.notifications[].is_read] | sort | tostring')
if [ "$V" = "[false,true]" ]; then ok "list：is_read 一未读一已读"; else bad "list：is_read 值异常（$V）"; fi

echo "== 3) unreadCount：期望 1 =="
C=$(curl -s --noproxy '*' -XPOST $API/notification/unreadCount -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | jq -r '.count // empty')
if [ "$C" = "1" ]; then ok "unreadCount=1"; else bad "unreadCount=$C（期望 1）"; fi

echo "== 4) markRead {}（标全部）=="
M=$(curl -s --noproxy '*' -XPOST $API/notification/markRead -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | jq -r '.message // empty')
if [ "$M" = "ok" ]; then ok "markRead {} → message=ok"; else bad "markRead {} → $M"; fi
C=$(curl -s --noproxy '*' -XPOST $API/notification/unreadCount -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | jq -r '.count // empty')
if [ "$C" = "0" ]; then ok "标全部后 unreadCount=0"; else bad "标全部后 unreadCount=$C（期望 0）"; fi

echo "== 5) markRead 空 body（完全不带 body）=="
q "INSERT INTO notifications (recipient_id,sender_id,type,target_id,content,is_read,created_at) VALUES ($AID,2,'follow',1,'关注了你',0,NOW(3))"
M=$(curl -s --noproxy '*' -XPOST $API/notification/markRead -H "Authorization: Bearer $TOKEN" | jq -r '.message // empty')
C=$(curl -s --noproxy '*' -XPOST $API/notification/unreadCount -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | jq -r '.count // empty')
if [ "$M" = "ok" ] && [ "$C" = "0" ]; then ok "空 body → message=ok 且 count=0"; else bad "空 body：message=$M count=$C"; fi

echo "== 6) markRead 单条 {\"id\":N} =="
q "INSERT INTO notifications (recipient_id,sender_id,type,target_id,content,is_read,created_at) VALUES ($AID,2,'like',1,'点赞了你的视频',0,NOW(3))"
NID=$(q "SELECT id FROM notifications WHERE recipient_id=$AID AND is_read=0 ORDER BY id DESC LIMIT 1")
M=$(curl -s --noproxy '*' -XPOST $API/notification/markRead -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d "{\"id\":$NID}" | jq -r '.message // empty')
C=$(curl -s --noproxy '*' -XPOST $API/notification/unreadCount -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{}' | jq -r '.count // empty')
if [ "$M" = "ok" ] && [ "$C" = "0" ]; then ok "单条标记 → message=ok 且 count=0"; else bad "单条：message=$M count=$C"; fi

echo "== 7) 无 token → 401（防 WebConfig 漏注册）=="
CODE=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/notification/list -H 'content-type: application/json' -d '{}')
ERR=$(curl -s --noproxy '*' -XPOST $API/notification/list -H 'content-type: application/json' -d '{}' | jq -r '.error // empty')
if [ "$CODE" = "401" ] && [ "$ERR" = "missing authorization header" ]; then ok "无 token → 401 missing authorization header"; else bad "无 token：code=$CODE error=$ERR"; fi

q "DELETE FROM notifications WHERE recipient_id=$AID"
echo
echo "== 结果：$FAILS 项失败 =="
exit $FAILS
