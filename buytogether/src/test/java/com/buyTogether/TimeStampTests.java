package com.buyTogether;

import com.buyTogether.entity.Shop;
import com.buyTogether.mapper.ShopMapper;
import com.buyTogether.service.impl.ShopServiceImpl;
import com.buyTogether.utils.CacheClient;
import com.buyTogether.utils.RedisGlobalId;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.buyTogether.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
@Disabled("Manual Redis ID generation and lock pressure-test utilities; run explicitly when local services are ready.")
class TimeStampTests {
    @Resource
    RedisGlobalId redisGlobalId;

    @Resource
    private RedissonClient redissonClient;
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testTimeStamp() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for(int i=0;i<100;i++){
                long id = redisGlobalId.globalId("order");
                System.out.println("id="+id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }


    @Test
    void method1() throws InterruptedException {

        RLock lock = redissonClient.getLock("order");
        boolean islock = lock.tryLock(30L,TimeUnit.SECONDS);
    }
}
