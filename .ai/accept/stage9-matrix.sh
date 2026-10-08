#!/usr/bin/env bash
# 阶段 9 第 6 步 · 自测矩阵（一口总跑）
#
# 覆盖《09》第 6 步表格：以 `./mvnw test`（135 条：账号/视频/评论点赞/社交Feed/通知私信/Kafka 的合同用例）
# 为底，叠加六本快验收脚本 + 降级/全停一幕。矩阵总表在尾部打印。
#
# 排除项（此前已单独全过、重跑代价高——需要时手动跑）：
#   stage7-checklist.sh（会 kill/重启 Worker + 毒消息剧本，不适合串跑）、stage8-sse-stream.sh（40 秒挂流）。
#
# 前置：API(8081) 与 Worker 都在跑；三容器在跑。Go 版可开可不开（互通用例自动 skip）。
#   Worker 是硬前置（第 0 步检查 cg-video-write 活跃成员）：like-flow 的落库/计数与 cache-map 的两键清理、
#   热度窗口都靠它消费事件完成；缺了直接中止，不然 cache-map 会以"三红"的形式浪费一次整跑。
# 时长：约 3~5 分钟（含容器停/起一幕）。失败时日志保留（脚本末尾给路径）。
# 用法：bash .ai/accept/stage9-matrix.sh   （退出码 = FAIL 项数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
LOGDIR=$(mktemp -d)
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }

run_step(){ # $1=标签；其余=命令
  local label="$1"; shift
  bash .ai/accept/_reset-rate.sh   # 每步前复位注册/登录计数器（脚本连跑会互相吃额度）
  echo "---- [$label] ----"
  if "$@" > "$LOGDIR/$label.log" 2>&1; then
    ok "$label（脚本退出码 0）"
    grep -E "结果：|全绿|FAIL 条数" "$LOGDIR/$label.log" | tail -1 | sed 's/^/         /'
  else
    bad "$label（有红；日志 $LOGDIR/$label.log）"
    tail -6 "$LOGDIR/$label.log" | sed 's/^/         /'
  fi
}

echo "== 0) 前置 =="
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "API 8081 在跑"; else bad "API 没起（先 <leader>sr 再跑矩阵）"; echo "== 中止：前置不满足 =="; exit 1; fi
RUNNING=$($COMPOSE ps --services --filter status=running 2>/dev/null | sort | tr '\n' ' ')
if [ "$RUNNING" = "kafka mysql redis " ]; then ok "三容器在跑"; else bad "容器状态异常：[$RUNNING]"; fi
STATE=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cg-video-write --state 2>/dev/null)
MEM=$(printf '%s\n' "$STATE" | awk '$1=="cg-video-write" {print $NF}' | tail -1)
case "$MEM" in ''|*[!0-9]*) MEM=0 ;; esac
if [ "$MEM" -gt 0 ]; then
  ok "Worker 在跑（cg-video-write 活跃成员 $MEM）"
else
  bad "Worker 没起（cg-video-write 活跃成员 0；Kafka 没起也会落到这里）"
  echo "     另开一个终端：./mvnw spring-boot:run -Dmain.class=io.github.p_feed.WorkerApplication"
  echo "     看到 'worker consumers started: 4' 后再跑矩阵"
  echo "== 中止：前置不满足 =="; exit 1
fi

echo "== 1) 套件：./mvnw test =="
echo "---- [suite] ----"
if ./mvnw test > "$LOGDIR/suite.log" 2>&1; then
  ok "suite 全绿"
  grep -E "Tests run: [0-9]+, Failures" "$LOGDIR/suite.log" | tail -1 | sed 's/^/         /'
else
  bad "suite 有红（日志 $LOGDIR/suite.log）"
  grep -E "Tests run: [0-9]+, Failures" "$LOGDIR/suite.log" | tail -1 | sed 's/^/         /'
fi

echo "== 2) 快验收脚本 =="
run_step notif-read   bash .ai/accept/stage8-notif-read.sh
run_step sse-auth     bash .ai/accept/stage8-sse-auth.sh
run_step notification bash .ai/accept/stage8-notification-flow.sh
run_step like-flow    bash .ai/accept/stage7-like-flow.sh
run_step cache-map    bash .ai/accept/stage8-cache-map.sh
run_step ratelimit    bash .ai/accept/stage9-ratelimit.sh
run_step observability bash .ai/accept/stage9-observability.sh

echo "== 3) 降级与全停 =="
run_step degradation  bash .ai/accept/stage9-degradation.sh

echo
echo "================ 矩阵总表（《09》第 6 步） ================"
cat <<'TABLE'
账号        由 suite 覆盖（AccountContractTest：注册/登录/刷新/改名/登出 + 失败路径）
视频        由 suite 覆盖（分块/发布/详情 + 400/403/not found）
评论点赞    由 suite 覆盖（评论/@/正序/计数联动 + 失败路径）
社交与Feed  由 suite 覆盖（关注流 + 五种流分页）；stage6-accept.sh 可按需重跑（曾全过）
通知私信    notif-read / sse-auth / notification 三本 + suite 全覆盖
Kafka异步   suite（consumer/outbox/event 用例）+ like-flow（点赞 200→≤5s 落库、计数 +1、LAG 归零、DLQ 空）；stage7-checklist.sh 曾六项全过（按需重跑）
限流        stage9-ratelimit（10×400→429、逐字、无 Retry-After、TTL 不重置）
缓存与降级  cache-map（FLUSHDB；两键清理靠 Worker 消费热度事件）+ degradation（Redis 挂 fail-open/降级、全停 /healthz 200）
------------------------------------------------------------
TABLE
echo "== 矩阵结果：$FAILS 项失败 =="
if [ "$FAILS" = "0" ]; then
  rm -rf "$LOGDIR"
else
  echo "（失败日志保留在：$LOGDIR）"
fi
exit $FAILS
