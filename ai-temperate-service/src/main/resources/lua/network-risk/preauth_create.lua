-- 原子创建随机 PreAuth Hash；极小概率令牌摘要冲突时拒绝覆盖现有状态。
if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end
redis.call('HSET', KEYS[1], unpack(ARGV, 2))
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return 1
