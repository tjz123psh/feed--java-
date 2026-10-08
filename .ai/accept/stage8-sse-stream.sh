#!/usr/bin/env bash
# 阶段 8 第 5 步验收：SseHub 与 /notification/stream（建流 + 空闲 keepalive）
#
# 前置（脚本自检第 0 项）：API(8081) 在跑（<leader>sr）；Worker 不需要。
# 时长：约 40 秒（要等 30 秒的 keepalive；事件投递在单测与第 6 步验收里验）。
# 副作用：新注册探针账号（sse_<时间戳>）并开一条流；跑完看 API 终端：
#       应先出现 `sse subscribe`；断开后的下一次发送失败时（≤35 秒）出现 `sse remove` = 断线摘除。
#
# 用法：bash .ai/accept/stage8-sse-stream.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
STREAM=$API/notification/stream
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }

echo "== 0) 前置：API 健康 =="
CODE=$(curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' $API/healthz)
if [ "$CODE" = "200" ]; then ok "healthz 200"; else bad "healthz=$CODE（API 没起？<leader>sr）"; exit 1; fi

echo "== 1) 注册并登录一个全新探针账号 =="
U="sse_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" >/dev/null
T=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$T" ]; then ok "登录拿到 token（$U）"; else bad "登录失败"; exit 1; fi

echo "== 2) 挂流 36 秒：200 + text/event-stream + 空闲 keepalive（本项约 36 秒）=="
HDR=$(mktemp) ; BODY=$(mktemp)
curl -sN -m 36 --noproxy '*' -D "$HDR" -o "$BODY" "$STREAM?token=$T" || true
LINE1=$(head -1 "$HDR" 2>/dev/null)
if echo "$LINE1" | grep -q " 200"; then ok "建流 HTTP 200（$LINE1）"; else bad "建流状态：${LINE1:-（没有响应头）}"; fi
if grep -qi "content-type: text/event-stream" "$HDR" 2>/dev/null; then ok "Content-Type: text/event-stream"; else bad "Content-Type 不对：$(grep -i content-type "$HDR" 2>/dev/null | head -1)"; fi
if grep -q "keepalive" "$BODY" 2>/dev/null; then ok "空闲 keepalive 已收到"; else bad "36 秒内没收到 keepalive（body: $(head -c 120 "$BODY" 2>/dev/null)）"; fi
rm -f "$HDR" "$BODY"

echo
echo "== 结果：$FAILS 项失败 =="
echo "提示：看 API 终端——应出现 sse subscribe（刚这条流）；断开后的下一次 keepalive 失败时（≤35 秒）出现 sse remove。"
exit $FAILS
