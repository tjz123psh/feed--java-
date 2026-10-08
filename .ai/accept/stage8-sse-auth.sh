#!/usr/bin/env bash
# 阶段 8 第 4 步验收：SSE 鉴权拦截器（/notification/stream 的 token 入口与逐字 401）
#
# 前置（脚本自检第 0 项）：API(8081) 在跑（<leader>sr）。
# 备注：第 5 步之前 /notification/stream 还没有 handler——鉴权通过的请求会落到静态资源兜底
#      （500 `No static resource notification/stream.`），所以第 6/7 项只断言"不是 401"；
#      第 5 步之后同样的检查会显示 200（-m 3 到点即断，不会挂住脚本）。
# 副作用：新注册探针账号（sse_<时间戳>）并登出一次；不动任何已有数据。
#
# 用法：bash .ai/accept/stage8-sse-auth.sh   （退出码 = FAIL 条数，0 为全绿）

set -u
cd "$(dirname "$0")/../.."   # 仓库根
export no_proxy='127.0.0.1,localhost' NO_PROXY='127.0.0.1,localhost'
bash "$(dirname "$0")/_reset-rate.sh"   # 复位注册/登录限流计数器（避免脚本连跑互相 429）
API=http://127.0.0.1:8081
STREAM=$API/notification/stream
FAILS=0
ok(){ echo "  PASS  $1"; }
bad(){ echo "  FAIL  $1"; FAILS=$((FAILS+1)); }
get_code(){ curl -s -m 3 --noproxy '*' -o /dev/null -w '%{http_code}' "$@"; }
get_err(){ curl -s -m 3 --noproxy '*' "$@" | jq -r '.error // empty'; }

echo "== 0) 前置：API 健康 =="
CODE=$(get_code $API/healthz)
if [ "$CODE" = "200" ]; then ok "healthz 200"; else bad "healthz=$CODE（API 没起？<leader>sr）"; exit 1; fi

echo "== 1) 注册并登录一个全新探针账号 =="
U="sse_$(date +%s)"
curl -s --noproxy '*' -XPOST $API/account/register -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" >/dev/null
T=$(curl -s --noproxy '*' -XPOST $API/account/login -H 'content-type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"password123\"}" | jq -r '.token // empty')
if [ -n "$T" ]; then ok "登录拿到 token（$U）"; else bad "登录失败"; exit 1; fi

echo "== 2) 无 token → 401 missing token =="
C=$(get_code $STREAM); E=$(get_err $STREAM)
if [ "$C" = "401" ] && [ "$E" = "missing token" ]; then ok "无 token → 401 missing token"; else bad "无 token：code=$C error=$E"; fi

echo "== 3) 乱 token（查询参数）→ 401 invalid token =="
C=$(get_code "$STREAM?token=not-a-jwt"); E=$(get_err "$STREAM?token=not-a-jwt")
if [ "$C" = "401" ] && [ "$E" = "invalid token" ]; then ok "乱 token(query) → 401 invalid token"; else bad "query 乱 token：code=$C error=$E"; fi

echo "== 4) 乱 token（Authorization 头）→ 401 invalid token =="
C=$(get_code $STREAM -H "Authorization: Bearer not-a-jwt"); E=$(get_err $STREAM -H "Authorization: Bearer not-a-jwt")
if [ "$C" = "401" ] && [ "$E" = "invalid token" ]; then ok "乱 token(header) → 401 invalid token"; else bad "header 乱 token：code=$C error=$E"; fi

echo "== 5) 查询参数优先：query 乱 + header 有效 → 401 invalid token =="
C=$(get_code "$STREAM?token=not-a-jwt" -H "Authorization: Bearer $T"); E=$(get_err "$STREAM?token=not-a-jwt" -H "Authorization: Bearer $T")
if [ "$C" = "401" ] && [ "$E" = "invalid token" ]; then ok "query 优先于 header（坏了也不回退）"; else bad "优先级：code=$C error=$E"; fi

echo "== 6) 有效 token（query）→ 鉴权通过 =="
C=$(get_code "$STREAM?token=$T")
if [ "$C" != "401" ]; then ok "有效 token(query) 未被 401（code=$C；第 5 步前=500 属预期）"; else bad "有效 token(query) 被 401（应通过）"; fi

echo "== 7) 有效 token（header）→ 鉴权通过 =="
C=$(get_code $STREAM -H "Authorization: Bearer $T")
if [ "$C" != "401" ]; then ok "有效 token(header) 未被 401（code=$C；第 5 步前=500 属预期）"; else bad "有效 token(header) 被 401（应通过）"; fi

echo "== 8) 登出后旧 token → 401 token has been revoked（决策 b 的效果）=="
curl -s --noproxy '*' -XPOST $API/account/logout -H "Authorization: Bearer $T" >/dev/null
C=$(get_code "$STREAM?token=$T"); E=$(get_err "$STREAM?token=$T")
if [ "$C" = "401" ] && [ "$E" = "token has been revoked" ]; then ok "登出后 → 401 token has been revoked"; else bad "登出后：code=$C error=$E"; fi

echo
echo "== 结果：$FAILS 项失败 =="
exit $FAILS
