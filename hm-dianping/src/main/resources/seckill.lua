local voucherId = ARGV[1]
local userId = ARGV[2]
local id = ARGV[3]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 1. 获取库存，强制转数字（不存在则为0）
local stock = tonumber(redis.call("get", stockKey) or "0")

-- 2. 库存不足直接返回
if stock < 1 then
    return 1
end

-- 3. 判断用户是否重复下单
if redis.call("sismember", orderKey, userId) == 1 then
    return 2
end

-- 4. 扣库存（这里用 set 绝对安全！不会报数字错误）
redis.call("set", stockKey, stock - 1)

-- 5. 记录用户 方便后续判断是否重复下单
redis.call("sadd", orderKey, userId)

-- 6，将订单加到消息队列 XADD stream orders * k1 v1 k2 v2
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',id)

return 0