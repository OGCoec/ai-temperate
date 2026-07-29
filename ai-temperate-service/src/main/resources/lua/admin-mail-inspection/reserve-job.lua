local existing = redis.call('GET', KEYS[1])
if existing then
    local separator = string.find(existing, '|', 1, true)
    local existingHash = separator and string.sub(existing, 1, separator - 1) or existing
    local existingFingerprint = separator and string.sub(existing, separator + 1) or ''
    if existingFingerprint == ARGV[6] then
        return 'REPLAYED|' .. existingHash
    end
    return 'FINGERPRINT_CONFLICT|' .. existingHash
end

if redis.call('GET', KEYS[3]) ~= 'ACCEPTING' then
    return 'UNAVAILABLE|' .. ARGV[5]
end

local activeKeyCount = tonumber(ARGV[1])
local activeCount = 0
for index = 7, 6 + activeKeyCount do
    if redis.call('EXISTS', KEYS[index]) == 1 then
        activeCount = activeCount + 1
    end
end
if redis.call('EXISTS', KEYS[2]) == 1
        or activeCount >= tonumber(ARGV[2]) then
    return 'TYPE_CAPACITY_CONFLICT|' .. ARGV[5]
end

redis.call('HSET', KEYS[4],
    'schemaVersion', ARGV[7],
    'jobId', ARGV[8],
    'jobHash', ARGV[5],
    'inspectionType', ARGV[9],
    'status', ARGV[10],
    'requestedCount', ARGV[11],
    'acceptedCount', ARGV[12],
    'duplicateCount', ARGV[13],
    'invalidCount', ARGV[14],
    'businessConcurrency', ARGV[15],
    'completionTarget', ARGV[16],
    'clientRequestId', ARGV[17],
    'requestFingerprint', ARGV[18],
    'submissionChunkCount', ARGV[19],
    'recoveredAfterRestart', ARGV[20],
    'resultHistoryLost', ARGV[21],
    'lostResultCount', ARGV[22],
    'resumeRequired', ARGV[23],
    'pendingItems', ARGV[24],
    'createdAt', ARGV[25],
    'startedAt', ARGV[26],
    'completedAt', ARGV[27],
    'expiresAt', ARGV[28],
    'submissionExpiresAt', ARGV[29],
    'recoveredAt', ARGV[30])

local resultCount = tonumber(ARGV[31])
redis.call('HSET', KEYS[5],
    'processedCount', resultCount,
    'runningCount', 0,
    'queuedCount', ARGV[12],
    'dispatchFailedCount', 0,
    'confirmedSubmissionChunkCount', 0,
    'dispatchedSubmissionChunkCount', 0)
redis.call('SET', KEYS[6], '1', 'PXAT', ARGV[3])
redis.call('SET', KEYS[1], ARGV[5] .. '|' .. ARGV[6], 'PXAT', ARGV[3])
redis.call('SET', KEYS[2], ARGV[5], 'PXAT', ARGV[3])
redis.call('PEXPIREAT', KEYS[4], ARGV[3])
redis.call('PEXPIREAT', KEYS[5], ARGV[3])

local argument = 32
for index = 1, resultCount do
    local keyIndex = tonumber(ARGV[argument])
    redis.call('HSET', KEYS[keyIndex], ARGV[argument + 1], ARGV[argument + 2])
    redis.call('HINCRBY', KEYS[5], 'status:' .. ARGV[argument + 3], 1)
    redis.call('PEXPIREAT', KEYS[keyIndex], ARGV[3])
    argument = argument + 4
end
return 'CREATED|' .. ARGV[5]
