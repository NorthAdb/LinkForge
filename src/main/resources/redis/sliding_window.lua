-- Sliding window log (B6): KEYS[1] = ZSET key; ARGV = max, window_ms, nonce.
-- The clock is Redis TIME (µs) — the JVM never passes a timestamp, so clock
-- drift between instances cannot skew the window.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local max = tonumber(ARGV[1])
local win = tonumber(ARGV[2]) * 1000
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)
local n = redis.call('ZCARD', KEYS[1])
if n < max then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return {1, n + 1, 0}
end
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_ms = math.ceil((tonumber(oldest[2]) + win - now) / 1000)
if retry_ms < 1 then retry_ms = 1 end
return {0, n, retry_ms}
