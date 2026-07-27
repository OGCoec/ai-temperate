-- 使用 Redis 原生 HSCAN 游标分页，并在同一脚本中清理缺少额度字段的孤立密文。
local scanned = redis.call('HSCAN', KEYS[1], ARGV[1], 'COUNT', ARGV[2])
local result = {scanned[1]}
local entries = scanned[2]

for index = 1, #entries, 2 do
    local field = entries[index]
    local encrypted = entries[index + 1]
    local quota = redis.call('HGET', KEYS[2], field)
    if quota then
        table.insert(result, field)
        table.insert(result, encrypted)
        table.insert(result, quota)
    else
        redis.call('HDEL', KEYS[1], field)
    end
end

return result
