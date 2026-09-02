local state = redis.call('HGET', KEYS[1], 'state')
local attempt = redis.call('HGET', KEYS[1], 'attemptNo') or '0'
local messageId = redis.call('HGET', KEYS[1], 'messageId') or ''
redis.call('PEXPIRE', KEYS[1], ARGV[3])

if state == 'RETRY_WAIT' and attempt == ARGV[1] and messageId == ARGV[2] then
    redis.call('HSET', KEYS[1], 'state', 'ATTEMPTING')
    redis.call('HDEL', KEYS[1], 'nextAttemptNo', 'terminalOutcome', 'safeReason')
    return 'ATTEMPT_PROVIDER|' .. attempt .. '|' .. messageId .. '|||'
end
if state == 'ATTEMPTING' and attempt == ARGV[1] and messageId == ARGV[2] then
    return 'ATTEMPT_OUTCOME_UNKNOWN|' .. attempt .. '|' .. messageId .. '|||'
end
local sourceMessageId = redis.call('HGET', KEYS[1], 'sourceMessageId') or ''
if state == 'RETRY_PUBLISH_PENDING' and sourceMessageId == ARGV[2] then
    local nextAttempt = redis.call('HGET', KEYS[1], 'nextAttemptNo') or ''
    local safeReason = redis.call('HGET', KEYS[1], 'safeReason') or ''
    return 'PUBLISH_RETRY|' .. attempt .. '|' .. messageId .. '|' .. nextAttempt .. '||' .. safeReason
end
if state == 'TERMINAL_PUBLISH_PENDING' and sourceMessageId == ARGV[2] then
    local terminalOutcome = redis.call('HGET', KEYS[1], 'terminalOutcome') or ''
    local safeReason = redis.call('HGET', KEYS[1], 'safeReason') or ''
    return 'PUBLISH_TERMINAL|' .. attempt .. '|' .. messageId .. '||' .. terminalOutcome .. '|' .. safeReason
end
return 'STALE_MESSAGE|' .. attempt .. '|' .. messageId .. '|||'
