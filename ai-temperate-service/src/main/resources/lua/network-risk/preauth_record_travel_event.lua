-- 在单个 PreAuth Hash 内原子清理、去重和追加三十分钟不可能旅行事件。
if redis.call('HGET', KEYS[1], 'schemaVersion') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'deviceDigest') ~= ARGV[2] then
  return -1
end

local now = tonumber(ARGV[3])
local cutoff = now - tonumber(ARGV[4])
local maximumEvents = tonumber(ARGV[6])
local raw = redis.call('HGET', KEYS[1], 'impossibleTravelEvents') or '[]'
local decoded, events = pcall(cjson.decode, raw)
if not decoded or type(events) ~= 'table' then
  events = {}
end

local compact = {}
local duplicate = false
for _, event in ipairs(events) do
  if type(event) == 'table'
    and type(event.at) == 'number'
    and event.at > cutoff then
    if event.digest == ARGV[5] then
      duplicate = true
    end
    table.insert(compact, event)
  end
end
if ARGV[5] ~= '' and not duplicate then
  table.insert(compact, {digest = ARGV[5], at = now})
end
while #compact > maximumEvents do
  table.remove(compact, 1)
end

redis.call('HSET', KEYS[1],
  'impossibleTravelEvents', cjson.encode(compact),
  'impossibleTravelCount', tostring(#compact))
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return #compact
