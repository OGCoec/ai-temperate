local snapshot_key = KEYS[1]
local expected_owner = ARGV[1]
local incoming_version = tonumber(ARGV[2])
local payment_started_at = ARGV[3]
local updated_at = ARGV[4]
local ttl_millis = tonumber(ARGV[5])

local order_fields = {
    'schemaVersion', 'orderId', 'loginIdentityId', 'membershipTier',
    'payAmountYuan', 'payType', 'status', 'idempotencyKey',
    'providerTradeNo', 'paymentStartedAt', 'expiresAt', 'closingDeadlineAt',
    'paidAt', 'stateVersion', 'createdAt', 'updatedAt'
}

local function current_snapshot_reply(outcome)
    local current_snapshot = redis.call('HMGET', snapshot_key, unpack(order_fields))
    local response = {outcome}
    for index = 1, #order_fields do
        response[#response + 1] = current_snapshot[index] or false
    end
    return response
end

local guard = redis.call('HMGET', snapshot_key,
        'loginIdentityId', 'status', 'paymentStartedAt', 'stateVersion')
if not guard[4] then
    return {'MISSING'}
end

local current_version = tonumber(guard[4])
if guard[1] ~= expected_owner or guard[2] ~= 'PENDING_PAYMENT' then
    return current_snapshot_reply('CONFLICT')
elseif current_version > incoming_version then
    return current_snapshot_reply('STALE')
elseif current_version == incoming_version
        and (guard[3] or '') == payment_started_at then
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    return {'UNCHANGED'}
elseif incoming_version == current_version + 1 then
    redis.call(
            'HSET', snapshot_key,
            'paymentStartedAt', payment_started_at,
            'stateVersion', tostring(incoming_version),
            'updatedAt', updated_at)
    redis.call('PEXPIRE', snapshot_key, ttl_millis)
    -- PostgreSQL 已提交快照正好包含这三处变化，正常路径只回结果码，避免十六字段回读。
    return {'APPLIED'}
elseif incoming_version > current_version + 1 then
    return {'REQUIRES_RESTORE'}
end
return current_snapshot_reply('CONFLICT')
