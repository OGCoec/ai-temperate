-- 先校验 Java 传入的 PreAuth schema 与设备摘要，再原子更新有界字段并刷新滑动 TTL。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2] then
  return 0
end
local ttl = ARGV[3]
if #ARGV > 3 then
  redis.call('HSET', KEYS[1], unpack(ARGV, 4))
end
redis.call('PEXPIRE', KEYS[1], ttl)
return 1
