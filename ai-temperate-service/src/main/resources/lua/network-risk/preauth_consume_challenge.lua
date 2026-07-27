-- 校验单 Hash 内活动 Challenge 后原子消费，并把当前快照提升为可信网络。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= '4'
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'activeChallengeIpDigest') ~= ARGV[2]
  or redis.call('HGET', KEYS[1], 'activeChallengeContextDigest') ~= ARGV[3]
  or redis.call('HGET', KEYS[1], 'activeChallengeNonce') ~= ARGV[4] then
  return 0
end

local activeExpiresAt = redis.call('HGET', KEYS[1], 'activeChallengeExpiresAt')
if not activeExpiresAt or activeExpiresAt == '' or activeExpiresAt <= ARGV[5] then
  return 0
end

local current = redis.call('HMGET', KEYS[1],
  'currentIpDigest', 'currentCountryCode', 'currentAsn',
  'currentLatitude', 'currentLongitude')
if not current[1] or current[1] == '' then
  return 0
end

redis.call('HSET', KEYS[1],
  'lastSeenAt', ARGV[5],
  'lastTrustedIpDigest', current[1],
  'lastTrustedCountryCode', current[2] or '',
  'lastTrustedAsn', current[3] or '',
  'lastTrustedLatitude', current[4] or '',
  'lastTrustedLongitude', current[5] or '',
  'lastTrustedObservedAt', ARGV[5],
  'lastDecision', 'ALLOW',
  'lastDecisionAt', ARGV[5],
  'lastDecisionContextDigest', ARGV[3],
  'temporaryBlockUntil', '',
  'challengeVerifiedUntil', ARGV[6],
  'activeChallengeNonce', '',
  'activeChallengeIpDigest', '',
  'activeChallengeContextDigest', '',
  'activeChallengeExpiresAt', '')
redis.call('HINCRBY', KEYS[1], 'challengePassedCount', 1)
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return 1
