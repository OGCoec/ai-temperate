-- 校验旧设备绑定后原子复制全部 v4 风控快照，再关联登录会话并清除未完成 Challenge。
if redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[1]
  or redis.call('EXISTS', KEYS[2]) == 1 then
  return 0
end

local existing = redis.call('HGETALL', KEYS[1])
if #existing == 0 or redis.call('HGET', KEYS[1], 'schemaVersion') ~= '4' then
  return 0
end
redis.call('HSET', KEYS[2], unpack(existing))
redis.call('HSET', KEYS[2],
  'authState', 'AUTHENTICATED',
  'sessionType', ARGV[4],
  'sessionRefDigest', ARGV[5],
  'lastDecisionContextDigest', ARGV[6],
  'lastSeenAt', ARGV[3],
  'activeChallengeNonce', '',
  'activeChallengeIpDigest', '',
  'activeChallengeContextDigest', '',
  'activeChallengeExpiresAt', '')
-- AAD 绑定 Token 摘要，因此旋转时必须覆盖为 Java 使用新摘要重加密的密文；损坏旧状态则清除两字段。
if ARGV[7] == '' then
  redis.call('HDEL', KEYS[2], 'webRtcStatus', 'webRtcIps')
else
  redis.call('HSET', KEYS[2],
    'webRtcStatus', ARGV[7],
    'webRtcIps', ARGV[8])
end
redis.call('PEXPIRE', KEYS[2], ARGV[2])
redis.call('UNLINK', KEYS[1])
return 1
