package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    public Result queryById(Long id){
        // 1.1 存储空数据防止缓存穿透（大量不存在的数据请求数据库）
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //  1.2 带锁的，防止被恶意攻击击穿导致击穿
        // Shop shop = queryWithMutex(id);
        // if(shop==null){
        //    return Result.fail("店铺不存在");
        // }
        //return Result.ok(shop);
//         3.逻辑过期
//         必须先用测试类将数据预热加载到redis中，因为假设一定有这条数据，不存在null的情况
//         且没有过期时间，只有逻辑过期时间
//         需要通过RedisData的expireTime和现在的时间对比看他是否过期。
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,LOCK_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        if(shop==null){
            return Result.fail("没有这条数据");
        }
        return Result.ok(shop);

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //不考虑缓存穿透，因为有缓存预热，已经将所有的数据都加载到redis了，不会存在null的情况
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY+id;
        //从redis获取商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，如果是空字符串就直接返回null
        //isBlank当shopJson为空字符串，如：""  '/n'  '/t'  null时为true
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //命中，将json反序列化为对象
        RedisData data = JSONUtil.toBean(shopJson, RedisData.class);

        Shop shop1 = JSONUtil.toBean((JSONObject) data.getData(),Shop.class);

        //判断是否过期
        if(LocalDateTime.now().isBefore(data.getExpireTime())){
            //未过期，返回对象
            return shop1;
        }

        // 过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean flag;
        try {
            flag = tryLock(lockKey);
            // 获取锁成功
            if(flag){
                //再次判断redis中的数据是否过期
                String shopJson1 = stringRedisTemplate.opsForValue().get(key);
                RedisData redisData = JSONUtil.toBean(shopJson1,RedisData.class);
                shop1 = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);

                //数据过期
                if(LocalDateTime.now().isAfter(redisData.getExpireTime())){
                    // 开启新线程，完成缓存重建
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        try {
                            this.saveShop2Redis(id, CACHE_SHOP_TTL);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }

        //返回旧数据
        return shop1;
    }

    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY+id;
        //从redis获取商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不为空直接返回数据
        //isNotBlank是判断字符串中是否有数据，对于""，null，"\t\n"，这些判断都为false，这里我对数据库中没查到数据的情况都将值设为""
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //如果是以前查过的空信息，已经将“”存到redis中了，返回空
        if(shopJson!=null){
            return null;
        }
        // 为空，去数据库查
        // 尝试获取锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        // ctrl+t，鼠标移动到surround可以直接封装选中的代码，形成try，catch，finally结构
        try {
            boolean flag = tryLock(lockKey);
            //获取锁失败，休眠
            // 怎么循环这个请求过程？我设置了一个while循环，视频是直接调用的函数
            while(!flag){
                try {
                    Thread.sleep(300); // 休眠300ms
                } catch (InterruptedException e) {
                    // 线程被中断，合理处理
                    Thread.currentThread().interrupt(); // 恢复中断标志
                    break; // 退出循环
                }
            }

            //获取锁成功，再次检查redis中有没有拿到数据
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            if (shopJson != null) {
                return null;
            }

            //去数据库获取数据
            shop = getById(id);

            //模拟重建延时
            Thread.sleep(200);

            //写入redis，有效期设为随机值
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RandomUtil.randomLong(1, 120), TimeUnit.SECONDS);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RandomUtil.randomLong(1, 30), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        return shop;
    }

    public Shop queryById(String id) throws InterruptedException {
        String key = CACHE_SHOP_KEY+id;
        //从redis获取商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不为空直接返回数据
        //isNotBlank是判断字符串中是否有数据，对于""，null，"\t\n"，这些判断都为false，这里我对数据库中没查到数据的情况都将值设为""
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if(shopJson!=null){
            return null;
        }
        //为空，去数据库查
        Shop shop = getById(id);
        Thread.sleep(300);
        //查不到，数据为空，返回错误
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //查到了，将数据写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return shop;
    }
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return null;
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断是否需要根据坐标查询
        if(x==null || y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY+typeId;
        // 查询redis，按照距离排序、分页。结果：shopId、distance
        // GeoReference.fromCoordinate(x, y) 就是一个点，可以直接改成 new Point(x,y)
        // newGeoSearchArgs：让redis返回该店铺与中心点的距离
        // limit(end) 限制返回条数
        //  Redis 低版本兼容写法（GEORADIUS）
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new org.springframework.data.geo.Circle(
                        new org.springframework.data.geo.Point(x, y),
                        new org.springframework.data.geo.Distance(5000)
                ),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(end)
        );
        // 方圆5公里内没有店铺
        if(results == null){
            return Result.ok();
        }
        // getContent 是解包，将redis返回的result这个大包解开，让一个List接收，其实本来里边也是一个List
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        // 将id取出来，将距离取出来
        Map<String,Distance> distanceMap = new HashMap<>();
        List<Long> ids = new ArrayList<>(list.size());
        list.stream().skip(from).forEach(result->{
            String shopStrId = result.getContent().getName();
            ids.add(Long.valueOf(shopStrId));
            Distance distance = result.getDistance();
            distanceMap.put(shopStrId,distance);
        });
        // 根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Shop shop : shopList){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }

    public boolean tryLock(String key){
        Boolean flag;
        flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
