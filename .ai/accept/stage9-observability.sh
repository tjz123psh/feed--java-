#!/usr/bin/env bash
# 阶段 9 第 5 步验收：观测面与优雅停机（自动部分）
#
# 前置：API(8081) 在跑（<leader>sr；yml 已加 graceful 配置）。
# 手动部分（脚本查不了）：在跑 API 的终端按 Ctrl-C，日志应出现
#   「Commencing graceful shutdown」与「Graceful shutdown complete」两行。
#
# 用法：bash .ai/accept/stage9-observability.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
API=http://127.0.0.1:8081
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }

echo "== 0) 自研健康端点保留（无条件 200，不探依赖） =="
R=$(curl -s -m 3 --noproxy '*' -w '\n%{http_code}' $API/healthz)
C=$(echo "$R" | tail -1); B=$(echo "$R" | head -1)
if [ "$C" = "200" ] && echo "$B" | grep -q '"status":"ok"'; then ok "/healthz 200 + {\"status\":\"ok\"}"; else bad "/healthz code=$C body=$B"; fi

echo "== 1) actuator/health =="
R=$(curl -s -m 3 --noproxy '*' -w '\n%{http_code}' $API/actuator/health)
C=$(echo "$R" | tail -1); B=$(echo "$R" | head -1)
if [ "$C" = "200" ] && echo "$B" | grep -q '"status":"UP"'; then ok "/actuator/health 200 + UP"; else bad "/actuator/health code=$C body=$B"; fi

echo "== 2) actuator/metrics =="
C=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/actuator/metrics)
if [ "$C" = "200" ]; then ok "/actuator/metrics 200"; else bad "/actuator/metrics code=$C"; fi

echo "== 3) 暴露面只开 health,metrics（没多开） =="
LINKS=$(curl -s -m 3 --noproxy '*' $API/actuator | jq -r '._links | keys | join(",")' 2>/dev/null)
if echo "$LINKS" | grep -q health && echo "$LINKS" | grep -q metrics \
   && ! echo "$LINKS" | grep -qE "env|beans|loggers|configprops|threaddump|heapdump|mappings"; then
  ok "暴露面 = $LINKS"
else
  bad "暴露面异常：$LINKS"
fi

echo
echo "== 结果：$FAILS 项失败 =="
echo "手动项：在 API 终端 Ctrl-C——应出现「Commencing graceful shutdown」与「Graceful shutdown complete」；此后 <leader>sr 重新拉起。"
exit $FAILS
