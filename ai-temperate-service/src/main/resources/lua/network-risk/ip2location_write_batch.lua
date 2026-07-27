local mode = ARGV[1]
local count = tonumber(ARGV[2])
local maximumActiveKeys = tonumber(ARGV[3])
local created = 0
local updated = 0
local duplicate = 0
local offset = 4

-- 在任何写入前原子计算本批真正新增的字段，确保超限失败不会留下部分导入。
local currentActiveKeys = math.max(redis.call('HLEN', KEYS[1]), redis.call('HLEN', KEYS[2]))
local additionalKeys = 0
local capacityOffset = offset
for index = 1, count do
    local field = ARGV[capacityOffset]
    capacityOffset = capacityOffset + 4
    local secretExists = redis.call('HEXISTS', KEYS[1], field)
    local quotaExists = redis.call('HEXISTS', KEYS[2], field)
    if secretExists == 0 and quotaExists == 0 then
        additionalKeys = additionalKeys + 1
    end
end
if currentActiveKeys + additionalKeys > maximumActiveKeys then
    return {-1, 0, 0}
end

for index = 1, count do
    local field = ARGV[offset]
    local encrypted = ARGV[offset + 1]
    local quota = tonumber(ARGV[offset + 2])
    local expiresAt = tonumber(ARGV[offset + 3])
    offset = offset + 4

    local secretExists = redis.call('HEXISTS', KEYS[1], field)
    local quotaExists = redis.call('HEXISTS', KEYS[2], field)
    local complete = secretExists == 1 and quotaExists == 1

    if mode == 'CREATE_ONLY' and complete then
        duplicate = duplicate + 1
    else
        redis.call('HSET', KEYS[1], field, encrypted)
        redis.call('HSET', KEYS[2], field, quota)
        redis.call('HPEXPIREAT', KEYS[1], expiresAt, 'FIELDS', 1, field)
        redis.call('HPEXPIREAT', KEYS[2], expiresAt, 'FIELDS', 1, field)
        if secretExists == 1 or quotaExists == 1 then
            updated = updated + 1
        else
            created = created + 1
        end
    end
end

return {created, updated, duplicate}
