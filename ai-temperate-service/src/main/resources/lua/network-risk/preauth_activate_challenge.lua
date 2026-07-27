-- 在同一个 PreAuth v4 Hash 内复用相同决策上下文，或原子创建新的活动 Challenge。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= '4'
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[1] then
  return {-1}
end

local activeContext = redis.call('HGET', KEYS[1], 'activeChallengeContextDigest')
local activeIp = redis.call('HGET', KEYS[1], 'activeChallengeIpDigest')
local activeNonce = redis.call('HGET', KEYS[1], 'activeChallengeNonce')
local activeExpiresAt = redis.call('HGET', KEYS[1], 'activeChallengeExpiresAt')

if activeContext == ARGV[3]
  and activeIp == ARGV[2]
  and activeNonce and activeNonce ~= ''
  and activeExpiresAt and activeExpiresAt ~= ''
  and activeExpiresAt > ARGV[5] then
  redis.call('PEXPIRE', KEYS[1], ARGV[7])
  return {0, activeNonce, activeExpiresAt}
end

redis.call('HSET', KEYS[1],
  'activeChallengeNonce', ARGV[4],
  'activeChallengeIpDigest', ARGV[2],
  'activeChallengeContextDigest', ARGV[3],
  'activeChallengeExpiresAt', ARGV[6])
redis.call('HINCRBY', KEYS[1], 'challengeIssuedCount', 1)
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return {1, ARGV[4], ARGV[6]}
