local callback_data_key = KEYS[1]
local ready_key = KEYS[2]
local processing_key = KEYS[3]
local callback_id = ARGV[1]
local ready_at = ARGV[2]

if redis.call('EXISTS', callback_data_key) == 0 then
    return 0
end
if redis.call('ZSCORE', processing_key, callback_id) then
    return 1
end
redis.call('ZADD', ready_key, ready_at, callback_id)
return 1
