local processing_key = KEYS[1]
local dirty_key = KEYS[2]
local count = tonumber(ARGV[1])
if count == 0 then
    return 0
end

local members = {}
local expected_scores = {}
local token_versions = {}
local argument_index = 2
for index = 1, count do
    members[index] = ARGV[argument_index]
    expected_scores[index] = tonumber(ARGV[argument_index + 1])
    token_versions[index] = tonumber(ARGV[argument_index + 2])
    argument_index = argument_index + 3
end

local scores = redis.call('ZMSCORE', processing_key, unpack(members))
local valid_indexes = {}
local zrem_processing = {'ZREM', processing_key}
local zrem_dirty = {'ZREM', dirty_key}
for index = 1, count do
    if scores[index] and tonumber(scores[index]) == expected_scores[index] then
        valid_indexes[#valid_indexes + 1] = index
        zrem_processing[#zrem_processing + 1] = members[index]
        zrem_dirty[#zrem_dirty + 1] = members[index]
    end
end
if #valid_indexes == 0 then
    return 0
end

redis.call(unpack(zrem_processing))
redis.call(unpack(zrem_dirty))

-- 每个快照位于不同 Key，跨 Key 无法 HMGET；只对已精确匹配 claim 的有界成员检查终态版本。
for _, index in ipairs(valid_indexes) do
    local snapshot_key = KEYS[index + 2]
    local snapshot = redis.call('HMGET', snapshot_key, 'stateVersion', 'status')
    if snapshot[1] and tonumber(snapshot[1]) == token_versions[index]
            and (snapshot[2] == 'PAID'
                or snapshot[2] == 'CANCELLED'
                or snapshot[2] == 'CLOSED') then
        redis.call('UNLINK', snapshot_key)
    end
end
return #valid_indexes
