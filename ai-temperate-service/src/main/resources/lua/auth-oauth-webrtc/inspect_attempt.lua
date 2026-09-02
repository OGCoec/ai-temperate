-- 只读取 attempt 最终状态；不启动探测、不推进 generation，也不延长任一截止时间。
if redis.call('EXISTS', KEYS[1]) == 0
        or redis.call('EXISTS', KEYS[2]) == 0
        or redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'currentIpDigest') ~= ARGV[3]
        or redis.call('HGET', KEYS[2], 'generation') ~= ARGV[4]
        or redis.call('HGET', KEYS[2], 'preAuthTokenDigest')
                ~= string.match(KEYS[1], '[^:]+$')
        or redis.call('HGET', KEYS[2], 'deviceDigest') ~= ARGV[2]
        or redis.call('HGET', KEYS[2], 'currentIpDigest') ~= ARGV[3] then
    return {0, 0, 0}
end
local generation = tonumber(redis.call('HGET', KEYS[2], 'generation')) or 0
local deadline = tonumber(redis.call('HGET', KEYS[2], 'verdictDeadlineAt')) or 0
local status = redis.call('HGET', KEYS[2], 'status')
local redisTime = redis.call('TIME')
local nowMillis = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)
if deadline > 0 and nowMillis >= deadline and status ~= 'VERIFIED' and status ~= 'FAILED' then
    return {5, generation, deadline}
end
local codes = {
    OAUTH_SUSPENDED = 1,
    RESUMED = 2,
    VERIFIED = 3,
    FAILED = 4,
    EXPIRED = 5,
    REPLACED = 6
}
return {codes[status] or 0, generation, deadline}
