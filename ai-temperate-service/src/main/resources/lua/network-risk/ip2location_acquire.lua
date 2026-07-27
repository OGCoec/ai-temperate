local attempts = tonumber(ARGV[1])

for index = 1, attempts do
    local field = redis.call('HRANDFIELD', KEYS[2])
    if not field then
        return {0}
    end

    local quotaText = redis.call('HGET', KEYS[2], field)
    local encrypted = redis.call('HGET', KEYS[1], field)
    local quota = tonumber(quotaText)
    if not encrypted or not quota or quota <= 0 then
        redis.call('HDEL', KEYS[1], field)
        redis.call('HDEL', KEYS[2], field)
    else
        local secretExpiry = redis.call(
            'HPEXPIRETIME', KEYS[1], 'FIELDS', 1, field)[1]
        local quotaExpiry = redis.call(
            'HPEXPIRETIME', KEYS[2], 'FIELDS', 1, field)[1]
        local remaining = redis.call('HINCRBY', KEYS[2], field, -1)
        if remaining <= 0 then
            redis.call('HDEL', KEYS[1], field)
            redis.call('HDEL', KEYS[2], field)
        else
            if secretExpiry and secretExpiry > 0 then
                redis.call(
                    'HPEXPIREAT', KEYS[1], secretExpiry, 'FIELDS', 1, field)
            end
            if quotaExpiry and quotaExpiry > 0 then
                redis.call(
                    'HPEXPIREAT', KEYS[2], quotaExpiry, 'FIELDS', 1, field)
            end
        end
        return {1, field, encrypted, tostring(remaining)}
    end
end

return {0}
