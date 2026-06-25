-- 传进来keys[1] 和args[1]，分别是要获取的value的key和发当前进程的value
-- 通过对比获取到的线程号和当前线程是否一致，判断这个锁能不能删
if(redis.call('get',KEYS[1]) == ARGV[1])then
    -- 释放锁
    redis.call('del',KEYS[1])
end