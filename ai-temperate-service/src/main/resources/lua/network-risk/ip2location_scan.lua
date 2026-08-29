-- 使用 Redis 原生 HSCAN 游标分页，并在同一脚本中清理缺少额度字段的孤立密文。
local scanned = redis.call('HSCAN', KEYS[1], ARGV[1], 'COUNT', ARGV[2])
local result = {scanned[1]}
local entries = scanned[2]
local fields = {}

for index = 1, #entries, 2 do
    fields[#fields + 1] = entries[index]
end

local quotas = {}
if #fields > 0 then
    quotas = redis.call('HMGET', KEYS[2], unpack(fields))
end
local orphaned = {}
for field_index = 1, #fields do
    local field = fields[field_index]
    local encrypted = entries[field_index * 2]
    local quota = quotas[field_index]
    if quota then
        table.insert(result, field)
        table.insert(result, encrypted)
        table.insert(result, quota)
    else
        orphaned[#orphaned + 1] = field
    end
end
if #orphaned > 0 then
    redis.call('HDEL', KEYS[1], unpack(orphaned))
end

return result
