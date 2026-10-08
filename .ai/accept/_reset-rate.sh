#!/usr/bin/env bash
# 复位 127.0.0.1 的注册/登录限流计数器。
# 背景：验收脚本会连续注册/登录探针账号，而计数器与线上共享（注册 5/小时、登录 10/分钟）——
# 不复位的话，先跑的脚本会把额度吃光、后跑的脚本注册直接 429（2026-10-08 矩阵实测：
# cache-map 的探针注册正好是它前面三个脚本之后的第 6 个）。
# 各验收脚本入口都会静默调用本脚本；跑限流专测（stage9-ratelimit.sh）不受影响，它自会构造时序。
cd "$(dirname "$0")/../.."   # 仓库根
docker compose -f deploy/docker-compose.yml exec -T redis \
  redis-cli --no-auth-warning -a 123456 DEL \
  feedsystem:ratelimit:account_register:127.0.0.1 \
  feedsystem:ratelimit:account_login:127.0.0.1 >/dev/null 2>&1
exit 0
