-- 先校验 PreAuth v4 与设备摘要，再原子更新有界字段并刷新滑动 TTL。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= '4'
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[1] then
  return 0
end
local ttl = ARGV[2]
if #ARGV > 2 then
  redis.call('HSET', KEYS[1], unpack(ARGV, 3))
end
redis.call('PEXPIRE', KEYS[1], ttl)
return 1
