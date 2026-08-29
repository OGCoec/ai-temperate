local secret_values = redis.call('HMGET', KEYS[1], unpack(ARGV))
local quota_values = redis.call('HMGET', KEYS[2], unpack(ARGV))
local deleted = 0
for index = 1, #ARGV do
    if secret_values[index] or quota_values[index] then
        deleted = deleted + 1
    end
end
redis.call('HDEL', KEYS[1], unpack(ARGV))
redis.call('HDEL', KEYS[2], unpack(ARGV))
return deleted
