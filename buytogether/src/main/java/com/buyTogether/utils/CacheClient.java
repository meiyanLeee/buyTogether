package com.buyTogether.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.buyTogether.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.javassist.bytecode.SignatureAttribute;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.buyTogether.utils.RedisConstants.*;

@Component
@Slf4j
//将缓存击穿和缓存穿透封装起来
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
     public void set(String key, Object value, Long time, TimeUnit unit){
         stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
     }

    public void setLogicalExpireTime(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID>R queryWithPassThrough(String prefixKey, ID id, Class<R> type, Function<ID,R> dbfallback, Long time, TimeUnit unit){
        String key = prefixKey+id;
        //从redis获取商铺信息
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，不为空直接返回数据
        //isNotBlank是判断字符串中是否有数据，对于""，null，"\t\n"，这些判断都为false，这里我对数据库中没查到数据的情况都将值设为""
        if(StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        if(json!=null){
            return null;
        }
        //为空，去数据库查
        R r = (R) dbfallback.apply(id);
        //查不到，数据为空，返回错误
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        //查到了，将数据写入redis
        this.set(key,JSONUtil.toJsonStr(r),time,unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID>R queryWithLogicalExpire(String prefixKey,String prefixLockKey,ID id, Class<R> type, Function<ID,R> dbfallback, Long time, TimeUnit unit) {
        String key = prefixKey+id;
        //从redis获取商铺信息
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在，如果是空字符串就直接返回null
        //isBlank当shopJson为空字符串，如：""  '/n'  '/t'  null时为true
        if(StrUtil.isBlank(json)){
            return null;
        }
        //命中，将json反序列化为对象
        RedisData data = JSONUtil.toBean(json, RedisData.class);

        R r1 = JSONUtil.toBean((JSONObject) data.getData(),type);

        //判断是否过期
        if(LocalDateTime.now().isBefore(data.getExpireTime())){
            //未过期，返回对象
            return r1;
        }

        // 过期，尝试获取锁
        String lockKey = prefixLockKey+id;
        boolean flag;
        try {
            flag = tryLock(lockKey);
            // 获取锁成功
            if(flag){
                //再次判断redis中的数据是否过期
                String json1 = stringRedisTemplate.opsForValue().get(key);
                RedisData redisData = JSONUtil.toBean(json1,RedisData.class);
                r1 = JSONUtil.toBean((JSONObject) redisData.getData(),type);

                //数据过期
                if(LocalDateTime.now().isAfter(redisData.getExpireTime())){
                    // 开启新线程，完成缓存重建
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        R r = dbfallback.apply(id);
                        this.setLogicalExpireTime(key,r,time,unit);

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
        return r1;
    }

    public boolean tryLock(String key){
        Boolean flag;
        flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}
