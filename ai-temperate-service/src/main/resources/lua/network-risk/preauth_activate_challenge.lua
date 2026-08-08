-- 在同一个 PreAuth Hash 内复用相同决策上下文，或原子创建新的活动 Challenge。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2] then
  return {-1}
end

local activeContext = redis.call('HGET', KEYS[1], 'activeChallengeContextDigest')
local activeIp = redis.call('HGET', KEYS[1], 'activeChallengeIpDigest')
local activeNonce = redis.call('HGET', KEYS[1], 'activeChallengeNonce')
local activeExpiresAt = redis.call('HGET', KEYS[1], 'activeChallengeExpiresAt')

if activeContext == ARGV[4]
  and activeIp == ARGV[3]
  and activeNonce and activeNonce ~= ''
  and activeExpiresAt and activeExpiresAt ~= ''
  and activeExpiresAt > ARGV[6] then
  redis.call('PEXPIRE', KEYS[1], ARGV[8])
  return {0, activeNonce, activeExpiresAt}
end

redis.call('HSET', KEYS[1],
  'activeChallengeNonce', ARGV[5],
  'activeChallengeIpDigest', ARGV[3],
  'activeChallengeContextDigest', ARGV[4],
  'activeChallengeExpiresAt', ARGV[7])
redis.call('HINCRBY', KEYS[1], 'challengeIssuedCount', 1)
redis.call('PEXPIRE', KEYS[1], ARGV[8])
return {1, ARGV[5], ARGV[7]}
