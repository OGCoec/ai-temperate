local snapshot_key = KEYS[1]
local incoming_version = tonumber(ARGV[1])
local ttl_millis = tonumber(ARGV[2])
local field_count = tonumber(ARGV[3])

local order_fields = {
    'schemaVersion',
    'orderId',
    'loginIdentityId',
    'membershipTier',
    'payAmountYuan',
    'payType',
    'status',
    'idempotencyKey',
    'providerTradeNo',
    'paymentStartedAt',
    'expiresAt',
    'closingDeadlineAt',
    'paidAt',
    'stateVersion',
    'createdAt',
    'updatedAt'
}

local function current_snapshot_reply(outcome)
    local current_snapshot = redis.call('HMGET', snapshot_key, unpack(order_fields))
    local response = {outcome}
    for index = 1, #order_fields do
        response[#response + 1] = current_snapshot[index] or false
    end
    return response
end

local current_version_text = redis.call('HGET', snapshot_key, 'stateVersion')
local current_version = current_version_text and tonumber(current_version_text) or nil
local outcome
if current_version and current_version > incoming_version then
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    return current_snapshot_reply('STALE')
elseif current_version and current_version == incoming_version then
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    return current_snapshot_reply('UNCHANGED')
end

local existed = redis.call('EXISTS', snapshot_key) == 1
if existed then
    redis.call('UNLINK', snapshot_key)
end
local argument_index = 4
local fields = {}
for index = 1, field_count do
    fields[#fields + 1] = ARGV[argument_index]
    fields[#fields + 1] = ARGV[argument_index + 1]
    argument_index = argument_index + 2
end
if #fields > 0 then
    redis.call('HSET', snapshot_key, unpack(fields))
end
redis.call('PEXPIRE', snapshot_key, ttl_millis)
outcome = existed and 'REPLACED' or 'CREATED'
-- 正常写入后的 Hash 与 PostgreSQL 已提交快照完全相同，调用方可以直接复用输入，省掉十六字段回读。
return {outcome}
