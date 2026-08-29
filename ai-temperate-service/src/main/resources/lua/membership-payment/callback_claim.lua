local ready_key = KEYS[1]
local processing_key = KEYS[2]
local maximum = tonumber(ARGV[1])
local claimed_at = ARGV[2]

local popped = redis.call('ZPOPMIN', ready_key, maximum)
if #popped == 0 then
    return {}
end

local claimed = {}
local zadd = {'ZADD', processing_key}
for index = 1, #popped, 2 do
    local callback_id = popped[index]
    claimed[#claimed + 1] = callback_id
    zadd[#zadd + 1] = claimed_at
    zadd[#zadd + 1] = callback_id
end
redis.call(unpack(zadd))
return claimed
