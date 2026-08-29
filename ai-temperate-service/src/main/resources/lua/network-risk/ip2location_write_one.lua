local mode = ARGV[1]
local maximum_active_keys = tonumber(ARGV[2])
local field = ARGV[3]
local encrypted = ARGV[4]
local quota = ARGV[5]
local expires_at = tonumber(ARGV[6])

local exists = redis.call('HMGET', KEYS[1], field)
local quotas = redis.call('HMGET', KEYS[2], field)
local secret_exists = exists[1] ~= false
local quota_exists = quotas[1] ~= false
local complete = secret_exists and quota_exists

if mode == 'CREATE_ONLY' and complete then
    return 'DUPLICATE'
end

-- 只有两个 Hash 都没有该字段时才占用新容量；孤儿修复和 UPSERT 不增加活动凭据数量。
if not secret_exists and not quota_exists then
    local current_active = math.max(
            redis.call('HLEN', KEYS[1]),
            redis.call('HLEN', KEYS[2]))
    if current_active >= maximum_active_keys then
        return 'CAPACITY_REJECTED'
    end
end

redis.call('HSET', KEYS[1], field, encrypted)
redis.call('HSET', KEYS[2], field, quota)
redis.call('HPEXPIREAT', KEYS[1], expires_at, 'FIELDS', 1, field)
redis.call('HPEXPIREAT', KEYS[2], expires_at, 'FIELDS', 1, field)

if secret_exists or quota_exists then
    return 'UPDATED'
end
return 'CREATED'
