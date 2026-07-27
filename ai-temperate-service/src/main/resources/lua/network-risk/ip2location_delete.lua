local deleted = 0

for index = 1, #ARGV do
    local field = ARGV[index]
    local secretDeleted = redis.call('HDEL', KEYS[1], field)
    local quotaDeleted = redis.call('HDEL', KEYS[2], field)
    if secretDeleted == 1 or quotaDeleted == 1 then
        deleted = deleted + 1
    end
end

return deleted
