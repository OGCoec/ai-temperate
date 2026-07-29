if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
if redis.call('EXISTS', KEYS[4]) == 1 then
    return -1
end
redis.call('HSET', KEYS[1],
    'schemaVersion', ARGV[1],
    'jobId', ARGV[2],
    'jobHash', ARGV[3],
    'inspectionType', ARGV[4],
    'status', ARGV[5],
    'requestedCount', ARGV[6],
    'acceptedCount', ARGV[7],
    'duplicateCount', ARGV[8],
    'invalidCount', ARGV[9],
    'businessConcurrency', ARGV[10],
    'completionTarget', ARGV[11],
    'clientRequestId', ARGV[12],
    'requestFingerprint', ARGV[13],
    'submissionChunkCount', ARGV[14],
    'recoveredAfterRestart', ARGV[15],
    'resultHistoryLost', ARGV[16],
    'lostResultCount', ARGV[17],
    'resumeRequired', ARGV[18],
    'pendingItems', ARGV[19],
    'createdAt', ARGV[20],
    'startedAt', ARGV[21],
    'completedAt', ARGV[22],
    'expiresAt', ARGV[23],
    'submissionExpiresAt', ARGV[24],
    'recoveredAt', ARGV[25])
redis.call('HSET', KEYS[2],
    'processedCount', ARGV[26],
    'runningCount', 0,
    'queuedCount', ARGV[27],
    'dispatchFailedCount', 0,
    'confirmedSubmissionChunkCount', ARGV[28],
    'dispatchedSubmissionChunkCount', ARGV[29])
redis.call('SET', KEYS[3], ARGV[30], 'PXAT', ARGV[23])
redis.call('SET', KEYS[4], ARGV[3], 'PXAT', ARGV[23])
redis.call('PEXPIREAT', KEYS[1], ARGV[23])
redis.call('PEXPIREAT', KEYS[2], ARGV[23])
local statusCount = tonumber(ARGV[31])
for index = 1, statusCount do
    redis.call('HINCRBY', KEYS[2], 'status:' .. ARGV[31 + index], 1)
end
return 1
