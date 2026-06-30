package com.buyTogether;

import com.buyTogether.entity.SeckillVoucher;
import com.buyTogether.entity.Shop;
import com.buyTogether.service.ISeckillVoucherService;
import com.buyTogether.service.IShopService;
import com.buyTogether.utils.CacheClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.buyTogether.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.buyTogether.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.buyTogether.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@Disabled("手动数据预热工具：需要本地 MySQL 和 Redis 准备好后再单独运行")
class DataPreheatTests {

    private static final String ORDER_STREAM_KEY = "stream.orders";
    private static final String ORDER_STREAM_GROUP = "g1";

    @Resource
    private IShopService shopService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void preheatShopLogicalExpireCache() {
        List<Shop> shops = shopService.list();
        shops.forEach(shop -> cacheClient.setLogicalExpireTime(
                CACHE_SHOP_KEY + shop.getId(),
                shop,
                30L,
                TimeUnit.MINUTES
        ));
    }

    @Test
    void preheatShopGeoIndex() {
        Map<Long, List<Shop>> shopsByType = shopService.list()
                .stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        shopsByType.forEach((typeId, shops) -> {
            String key = SHOP_GEO_KEY + typeId;
            stringRedisTemplate.delete(key);
            shops.forEach(shop -> stringRedisTemplate.opsForGeo().add(
                    key,
                    new Point(shop.getX(), shop.getY()),
                    shop.getId().toString()
            ));
        });
    }

    @Test
    void preheatSeckillVoucherStock() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        vouchers.forEach(voucher -> stringRedisTemplate.opsForValue().set(
                SECKILL_STOCK_KEY + voucher.getVoucherId(),
                voucher.getStock().toString()
        ));
    }

    @Test
    void initVoucherOrderStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(ORDER_STREAM_KEY, ReadOffset.from("0"), ORDER_STREAM_GROUP);
        } catch (Exception firstCreateFailure) {
            stringRedisTemplate.opsForStream().add(MapRecord.create(ORDER_STREAM_KEY, java.util.Collections.singletonMap("init", "1")));
            try {
                stringRedisTemplate.opsForStream().createGroup(ORDER_STREAM_KEY, ReadOffset.from("0"), ORDER_STREAM_GROUP);
            } catch (Exception ignored) {
                // Consumer group already exists. This test is an idempotent local preheat helper.
            }
        }
    }
}
