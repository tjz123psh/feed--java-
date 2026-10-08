#!/usr/bin/env bash
# 阶段 9 第 3 步验收：真的 429 + 固定窗口语义
#
# 前置：API(8081) 在跑；Redis 在跑。对 127.0.0.1 的 login 额度（10/分钟）会被本脚本打满——
#       脚本结束会 DEL 计数器把额度复位，不影响你后续手测。
# 用法：bash .ai/accept/stage9-ratelimit.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
COMPOSE="docker compose -f deploy/docker-compose.yml"
KEY="feedsystem:ratelimit:account_login:127.0.0.1"
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
r(){ $COMPOSE exec -T redis redis-cli --no-auth-warning -a 123456 "$@" 2>/dev/null | tr -d '\r'; }

echo "== 0) 前置：API 健康 + 计数器复位 =="
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$C" = "200" ]; then ok "healthz 200"; else bad "healthz=$C（API 没起？<leader>sr）"; exit 1; fi
r DEL "$KEY" >/dev/null
ok "计数器已复位（$KEY）"

echo "== 1) 连打 12 次登录：1-10 应 400、11-12 应 429 =="
CODES=""
for i in $(seq 1 12); do
  C=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -XPOST $API/account/login -H 'content-type: application/json' -d '{"username":"ct_rl_probe","password":"x"}')
  CODES="$CODES $C"
done
echo "  序列：$CODES"
if [ "$CODES" = " 400 400 400 400 400 400 400 400 400 400 429 429" ]; then ok "前 10 次 400、第 11 次起 429（固定窗口）"; else bad "状态码序列不对：$CODES"; fi

echo "== 2) 429 的响应体与响应头（第 13 次） =="
RESP=$(curl -s --noproxy '*' -i -XPOST $API/account/login -H 'content-type: application/json' -d '{"username":"ct_rl_probe","password":"x"}')
if echo "$RESP" | grep -q '{"error":"too many requests"}'; then ok "响应体逐字 {\"error\":\"too many requests\"}"; else bad "响应体不对：$(echo "$RESP" | tail -1)"; fi
if echo "$RESP" | grep -qi "retry-after"; then bad "不该有 Retry-After 头"; else ok "没有 Retry-After 头（照抄原版）"; fi

echo "== 3) 计数与 TTL =="
V=$(r GET "$KEY"); T1=$(r TTL "$KEY")
if [ -n "$V" ] && [ "$V" -ge 12 ]; then ok "计数器=$V（≥12）"; else bad "计数器=${V:-空}"; fi
if [ -n "${T1:-}" ] && [ "$T1" -gt 0 ] && [ "$T1" -le 60 ]; then ok "TTL=${T1}s ∈ (0,60]"; else bad "TTL=${T1:-空} 不在 (0,60]"; fi

echo "== 4) 关键语义：继续打时 TTL 只递减、不重置 =="
sleep 2
curl -s --noproxy '*' -o /dev/null -XPOST $API/account/login -H 'content-type: application/json' -d '{"username":"ct_rl_probe","password":"x"}'
T2=$(r TTL "$KEY")
if [ -n "$T2" ] && [ "$T2" -le "$T1" ]; then ok "TTL 未重置（${T1}s → ${T2}s；只在 count==1 时 PEXPIRE）"; else bad "TTL 被重置了：${T1}s → ${T2:-空}"; fi

echo "== 5) 复位计数器（你后续手测不受影响） =="
r DEL "$KEY" >/dev/null
ok "已复位"

echo
echo "== 结果：$FAILS 项失败 =="
exit $FAILS
