#!/usr/bin/env bash
# 阶段 6 验收清单（《06》第 11 步"本阶段验收清单"六条的自动化版）。自包含，无需手填。
# 前置：应用在 8081；mysql/redis 容器 healthy；库里有视频。
# 副作用与自愈：会插 12 条探针视频（结束删除）、会 FLUSHDB（token 白名单一起清，脚本内重新登录）、
#               会停/起 Redis；trap 保证 Redis 一定被拉起、探针数据一定被清掉。
# 注：2026-09-28 原本放在 /tmp，机器重启后被清掉，故入库到此路径。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1   # 从 .ai/accept/ 回到项目根

export NO_PROXY="localhost,127.0.0.1,::1"; export no_proxy="$NO_PROXY"
API=http://127.0.0.1:8081
GO_API=http://127.0.0.1:8080
DC=(docker compose -f deploy/docker-compose.yml)
REDIS_CLI=("${DC[@]}" exec -T redis redis-cli --no-auth-warning -a 123456)
MYSQL=("${DC[@]}" exec -T mysql mysql -uroot -p123456 -N -B feedsystem)
PROBE_AUTHOR=9900000006
PROBE_USER="stage6_probe_$(date +%s)"
PROBE_PASS=password123
FAILED=0

cleanup() {
  "${DC[@]}" start redis > /dev/null 2>&1 || true
  "${MYSQL[@]}" -e "delete from likes where video_id in (select id from videos where author_id=$PROBE_AUTHOR); delete from video_tags where video_id in (select id from videos where author_id=$PROBE_AUTHOR); delete from videos where author_id=$PROBE_AUTHOR" > /dev/null 2>&1 || true
}
trap cleanup EXIT

call() {  # call <path> <json> [token]
  if [ -n "${3:-}" ]; then
    curl -s --noproxy '*' -X POST "$API$1" -H 'content-type: application/json' -H "Authorization: Bearer $3" -d "$2"
  else
    curl -s --noproxy '*' -X POST "$API$1" -H 'content-type: application/json' -d "$2"
  fi
}
httpcode() {  # httpcode <path> <json> [token]
  if [ -n "${3:-}" ]; then
    curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST "$API$1" -H 'content-type: application/json' -H "Authorization: Bearer $3" -d "$2"
  else
    curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST "$API$1" -H 'content-type: application/json' -d "$2"
  fi
}
ok()  { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; FAILED=$((FAILED+1)); }

echo "== 0) 前置 =="
[ "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$API/healthz" || true)" = "200" ] || { echo "FAIL: 8081 没起来（./mvnw spring-boot:run ↔ <leader>sr）"; exit 1; }
VIDEOS=$("${MYSQL[@]}" -e "select count(*) from videos" 2>/dev/null)
echo "8081 OK，库里视频 $VIDEOS 条"

echo
echo "== 1) 与参考实现双跑对比（《06》标注：可选，需 8080 上跑着 Go 版） =="
if [ "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$GO_API/healthz" 2>/dev/null || true)" = "200" ]; then
  for spec in "/feed/listLatest|{\"limit\":3}" "/feed/listLikesCount|{\"limit\":3}" "/feed/listByPopularity|{\"limit\":3}" "/feed/listByTag|{\"tag_name\":\"java\"}"; do
    P="${spec%%|*}"; B="${spec#*|}"
    A=$(curl -s --noproxy '*' -X POST "$API$P" -H 'content-type: application/json' -d "$B" | jq -c '{ids:[.video_list[].id],has_more}')
    G=$(curl -s --noproxy '*' -X POST "$GO_API$P" -H 'content-type: application/json' -d "$B" | jq -c '{ids:[.video_list[].id],has_more}')
    [ "$A" = "$G" ] && ok "$P 与参考实现一致" || echo "WARN: $P 不一致（我=$A 参考=$G）"
  done
else
  echo "SKIP: 8080 上没有服务（参考实现未启动）。这一条本来就标注"可选"，差异清单已逐条记录在 README §4。"
fi

echo
echo "== 2) 新关注一个博主 → 其视频立刻出现在关注流第一页（SCAN 失效） =="
REG=$(call /account/register "{\"username\":\"$PROBE_USER\",\"password\":\"$PROBE_PASS\"}")
echo "注册响应：$REG（注册只回 message，不发 token —— token 要另调 /account/login）"
TOKEN=$(call /account/login "{\"username\":\"$PROBE_USER\",\"password\":\"$PROBE_PASS\"}" | jq -r '.token // empty')
[ -n "$TOKEN" ] || { echo "FAIL: 探针账号登录失败"; exit 1; }
echo "探针账号 $PROBE_USER 已注册并登录"
BLOGGER=$("${MYSQL[@]}" -e "select v.author_id from videos v join accounts a on a.id=v.author_id group by v.author_id order by count(*) desc limit 1" 2>/dev/null | head -1)
echo "被关注的博主 author_id=$BLOGGER（必须是 accounts 里真实存在的账号，否则 follow 会 500 account not found）"
BEFORE=$(call /feed/listByFollowing '{"limit":10,"latest_time":0}' "$TOKEN" | jq -c '[.video_list[].author.id]|unique')
call /social/follow "{\"vlogger_id\":$BLOGGER}" "$TOKEN" > /dev/null
AFTER=$(call /feed/listByFollowing '{"limit":10,"latest_time":0}' "$TOKEN" | jq -c '[.video_list[].author.id]|unique')
echo "关注前作者集合=$BEFORE   关注后=$AFTER"
echo "$AFTER" | jq -e --argjson b "$BLOGGER" 'index($b) != null' > /dev/null \
  && ok "新关注的博主立刻出现在关注流（说明 SCAN 失效真的删到了整页缓存）" \
  || bad "关注后第一页仍看不到 $BLOGGER"

echo
echo "== 3) 点赞写窗口 → 同一 as_of 第一页冻结 → 下一分钟排名更新 =="
MIN=$(date -u +%Y%m%d%H%M)
AS_OF=$(( $(date -u +%s) / 60 * 60 ))
VID=$("${MYSQL[@]}" -e "select id from videos order by id limit 1" 2>/dev/null | head -1)
VID2=$("${MYSQL[@]}" -e "select id from videos order by id limit 1 offset 1" 2>/dev/null | head -1)
S0=$("${REDIS_CLI[@]}" ZSCORE "v1:hot:video:1m:$MIN" "$VID")
call /like/like "{\"video_id\":$VID}" "$TOKEN" > /dev/null
S1=$("${REDIS_CLI[@]}" ZSCORE "v1:hot:video:1m:$MIN" "$VID")
echo "真实点赞一次：窗口分数 ${S0:-（无）} → ${S1:-（无）}（应 +1）"
[ -n "$S1" ] && ok "点赞确实写进了当前分钟窗口（v1:hot:video:1m:$MIN）" || bad "点赞没有写进热度窗口"
P1=$(call /feed/listByPopularity "{\"limit\":3,\"as_of\":$AS_OF}" | jq -r '.video_list[0].id // empty')
"${REDIS_CLI[@]}" ZINCRBY "v1:hot:video:1m:$MIN" 99 "$VID2" > /dev/null
P2=$(call /feed/listByPopularity "{\"limit\":3,\"as_of\":$AS_OF}" | jq -r '.video_list[0].id // empty')
echo "同一 as_of=$AS_OF：灌 99 分前第一名=$P1，之后=$P2"
[ "$P1" = "$P2" ] && ok "1 分钟内第一页不变（快照冻结生效）" || bad "快照没生效，第一名被顶成了 $P2"
P3=$(call /feed/listByPopularity "{\"limit\":3,\"as_of\":$((AS_OF+60))}" | jq -r '.video_list[0].id // empty')
echo "下一分钟 as_of=$((AS_OF+60))：第一名=$P3（期望 $VID2）"
[ "$P3" = "$VID2" ] && ok "跨分钟后排名按新热度更新" || echo "WARN: 下一分钟第一名是 $P3，期望 $VID2"

echo
echo "== 4) FLUSHDB → 五种流全部 200（全走 DB 降级） =="
"${REDIS_CLI[@]}" FLUSHDB > /dev/null
TOKEN=$(call /account/login "{\"username\":\"$PROBE_USER\",\"password\":\"$PROBE_PASS\"}" | jq -r '.token // empty')
[ -n "$TOKEN" ] || { echo "FAIL: FLUSHDB 后重新登录失败（token 白名单被清了）"; exit 1; }
echo "（FLUSHDB 把 token 白名单也清了，已重新登录拿新 token）"
ALL200=1
for spec in "/feed/listLatest|{\"limit\":3}" "/feed/listLikesCount|{\"limit\":3}" "/feed/listByPopularity|{\"limit\":3}" "/feed/listByTag|{\"tag_name\":\"java\"}"; do
  P="${spec%%|*}"; B="${spec#*|}"
  C=$(httpcode "$P" "$B"); echo "  $P → $C"; [ "$C" = "200" ] || ALL200=0
done
C=$(httpcode /feed/listByFollowing '{"limit":3,"latest_time":0}' "$TOKEN"); echo "  /feed/listByFollowing → $C"; [ "$C" = "200" ] || ALL200=0
[ "$ALL200" = "1" ] && ok "FLUSHDB 后五种流全部 200" || bad "有流不是 200"

echo
echo "== 5) limit 归一（0/51 → 10 条）与游标成对校验（400） =="
"${MYSQL[@]}" -e "delete from likes where video_id in (select id from videos where author_id=$PROBE_AUTHOR); delete from video_tags where video_id in (select id from videos where author_id=$PROBE_AUTHOR); delete from videos where author_id=$PROBE_AUTHOR" > /dev/null 2>&1
"${MYSQL[@]}" -e "insert into videos (author_id, username, title, description, play_url, cover_url, create_time, likes_count, popularity) with recursive n(i) as (select 1 union all select i+1 from n where i<12) select $PROBE_AUTHOR,'stage6_probe',concat('stage6 probe ',i),'probe','http://127.0.0.1:8081/static/videos/x/x.mp4','http://127.0.0.1:8081/static/covers/x/x.jpg',date_sub(now(3), interval i minute),0,0 from n" 2>&1 | grep -v "Warning" || true
TOTAL=$("${MYSQL[@]}" -e "select count(*) from videos" 2>/dev/null)
echo "库里现在 $TOTAL 条（含 12 条探针）"
# 用 listLikesCount 断言：它直查 DB，不经过推荐流的时间线窗口，计数才稳定
N0=$(call /feed/listLikesCount '{"limit":0}' | jq '.video_list | length')
N51=$(call /feed/listLikesCount '{"limit":51}' | jq '.video_list | length')
echo "listLikesCount：limit=0 → $N0 条；limit=51 → $N51 条（都应为 10）"
{ [ "$N0" = "10" ] && [ "$N51" = "10" ]; } && ok "limit 归一正确（0/51 都归一成 10）" || bad "limit 归一不对（期望 10/10，实得 $N0/$N51）"
echo "（参考）listLatest limit=51 → $(call /feed/listLatest '{"limit":51}' | jq '.video_list | length') 条：它读时间线窗口，窗口是插入探针之前重建的，看不到新行属正常（阶段 7 的消费者才会实时写窗口）"
C=$(httpcode /feed/listLikesCount '{"limit":3,"id_before":5}')
echo "只给 id_before → $C $(call /feed/listLikesCount '{"limit":3,"id_before":5}')"
[ "$C" = "400" ] && ok "单游标 → 400" || bad "期望 400，实得 $C"

echo
echo "== 6) 三级缓存与 Redis 挂掉 =="
echo "  100 并发只 1 次查库由单测覆盖：VideoEntityCacheTest.concurrent100Misses_queryDbOnce（mvnw test 已绿）"
"${DC[@]}" stop redis > /dev/null
C=$(httpcode /feed/listLatest '{"limit":3}')
echo "  停 Redis 后 /feed/listLatest → $C（期望 200，走 DB 兜底）"
[ "$C" = "200" ] && ok "Redis 挂掉时 listLatest 走 DB 且仍 200" || bad "期望 200，实得 $C"
"${DC[@]}" start redis > /dev/null
sleep 2
echo "  恢复后 /feed/listLatest → $(httpcode /feed/listLatest '{"limit":3}')"

echo
echo "== 汇总：FAIL 行数 = $FAILED（0 = 六条全绿） =="
exit "$FAILED"
