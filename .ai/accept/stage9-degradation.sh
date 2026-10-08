#!/usr/bin/env bash
# 阶段 9 第 6 步（矩阵·降级与全停）：Redis 挂 → fail-open/降级；三件全停 → /healthz 仍 200
#
# 前置：API(8081) 在跑；三容器在跑。**本脚本会 stop/start 容器**（redis 一幕；mysql+redis+kafka 全停一幕），
#       尾部 trap 保证无论如何都把三容器 start 回来。
# 用法：bash .ai/accept/stage9-degradation.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }

restore_all(){ $COMPOSE start mysql redis kafka >/dev/null 2>&1; }
trap 'restore_all; echo "[trap] 已确保三容器处于 start 状态"' EXIT

wait_mysql(){ for i in $(seq 1 45); do $COMPOSE exec -T mysql mysqladmin ping -h127.0.0.1 -uroot -p123456 --silent >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
wait_redis(){ for i in $(seq 1 20); do [ "$($COMPOSE exec -T redis redis-cli --no-auth-warning -a 123456 PING 2>/dev/null | tr -d '\r')" = "PONG" ] && return 0; sleep 1; done; return 1; }

echo "== 0) 前置 =="
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "healthz 200（API 在跑）"; else bad "API 没起（<leader>sr）"; exit 1; fi

echo "== 1) Redis 挂：限流 fail-open + 详情/Feed/登录降级 =="
$COMPOSE stop redis >/dev/null 2>&1
ok "已停 redis"
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "Redis 挂时 /healthz 仍 200"; else bad "Redis 挂时 /healthz=$C"; fi

CODES=""
for i in $(seq 1 12); do
  C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/account/login -H 'content-type: application/json' -d '{"username":"ct_deg_probe","password":"x"}')
  CODES="$CODES $C"
done
case "$CODES" in
  *429*) bad "Redis 挂时出现 429（fail-open 失效）：$CODES" ;;
  *) ok "12 次登录无一 429（Redis 挂 → 限流 fail-open）" ;;
esac

UN="ct_deg_$(date +%s)"
curl -s -m 5 --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' -d "{\"username\":\"$UN\",\"password\":\"password123\"}" >/dev/null
C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/account/login -H 'content-type: application/json' -d "{\"username\":\"$UN\",\"password\":\"password123\"}")
if [ "$C" = "200" ]; then ok "Redis 挂时注册+登录仍 200（先库后缓存、写缓存失败被容忍）"; else bad "Redis 挂时有效登录 code=$C"; fi

OUT=$(curl -s -m 25 --noproxy '*' -o /dev/null -w '%{http_code} %{time_total}' -XPOST $API/video/getDetail -H 'content-type: application/json' -d '{"id":1}')
C=${OUT%% *}; T=${OUT##* }
if [ "$C" = "200" ]; then ok "getDetail 降级查库 200（耗时 ${T}s）"; else bad "getDetail code=$C（耗时 ${T}s）"; fi
OUT=$(curl -s -m 25 --noproxy '*' -o /dev/null -w '%{http_code} %{time_total}' -XPOST $API/feed/listLatest -H 'content-type: application/json' -d '{"limit":10}')
C=${OUT%% *}; T=${OUT##* }
if [ "$C" = "200" ]; then ok "listLatest 降级 200（耗时 ${T}s）"; else bad "listLatest code=$C（耗时 ${T}s）"; fi

$COMPOSE start redis >/dev/null 2>&1
if wait_redis; then ok "redis 已拉起（PONG）"; else bad "redis 拉起失败"; fi
C=$(curl -s -m 5 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "redis 恢复后 /healthz 200"; else bad "恢复后 /healthz=$C"; fi

echo "== 2) 三件全停：/healthz 无条件 200 =="
$COMPOSE stop mysql redis kafka >/dev/null 2>&1
ok "已停 mysql + redis + kafka"
R=$(curl -s -m 3 --noproxy '*' -w '\n%{http_code}' $API/healthz)
C=$(echo "$R" | tail -1); B=$(echo "$R" | head -1)
if [ "$C" = "200" ] && echo "$B" | grep -q '"status":"ok"'; then
  ok "全停时 /healthz 200 + {\"status\":\"ok\"}（不探任何依赖）"
else
  bad "全停时 /healthz code=$C body=$B"
fi

echo "== 3) 拉起三件并等恢复 =="
restore_all
if wait_mysql; then ok "mysql 就绪"; else bad "mysql 未就绪"; fi
if wait_redis; then ok "redis 就绪"; else bad "redis 未就绪"; fi
K=1
for i in $(seq 1 20); do
  if $COMPOSE exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then K=0; break; fi
  sleep 1
done
if [ "$K" = "0" ]; then ok "kafka 就绪（topics --list 可用）"; else bad "kafka 未就绪"; fi

RECOVER=1
for i in $(seq 1 30); do
  C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/video/getDetail -H 'content-type: application/json' -d '{"id":1}')
  if [ "$C" = "200" ]; then RECOVER=0; break; fi
  sleep 1
done
if [ "$RECOVER" = "0" ]; then ok "API 全链路恢复（getDetail 200，连接池自愈）"; else bad "API 未在 30 秒内恢复"; fi

echo
echo "== 结果：$FAILS 项失败 =="
exit $FAILS
