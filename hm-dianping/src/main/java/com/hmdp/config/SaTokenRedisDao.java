package com.hmdp.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Sa-Token 持久层 Redis 实现
 * 解决多实例负载均衡时内存 session 不共享的问题
 */
@Slf4j
@Component
public class SaTokenRedisDao implements SaTokenDao {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ==================== String 操作 ====================

    @Override
    public String get(String key) {
        String val = stringRedisTemplate.opsForValue().get(key);
        log.debug("[RedisDao] GET key={}, value={}", key, val != null ? val.substring(0, Math.min(50, val.length())) : "null");
        return val;
    }

    @Override
    public void set(String key, String value, long timeout) {
        log.debug("[RedisDao] SET key={}, timeout={}, value={}", key, timeout, value.substring(0, Math.min(50, value.length())));
        if (timeout > 0) {
            stringRedisTemplate.opsForValue().set(key, value, timeout, TimeUnit.SECONDS);
        } else {
            stringRedisTemplate.opsForValue().set(key, value);
        }
    }

    @Override
    public void update(String key, String value) {
        Long ttl = getTimeout(key);
        if (ttl != null && ttl > 0) {
            set(key, value, ttl);
        } else {
            stringRedisTemplate.opsForValue().set(key, value);
        }
    }

    @Override
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public long getTimeout(String key) {
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null ? ttl : SaTokenDao.NOT_VALUE_EXPIRE;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (timeout > 0) {
            stringRedisTemplate.expire(key, timeout, TimeUnit.SECONDS);
        }
    }

    // ==================== Object 操作（带类名，反序列化时还原类型） ====================

    @Override
    public Object getObject(String key) {
        String data = get(key);
        if (data == null) {
            log.warn("[RedisDao] getObject MISS: key={}", key);
            return null;
        }
        // 格式: className|jsonString
        int sepIndex = data.indexOf('|');
        if (sepIndex < 0) {
            log.warn("[RedisDao] getObject 格式错误, key={}", key);
            return null;
        }
        String className = data.substring(0, sepIndex);
        String json = data.substring(sepIndex + 1);
        try {
            Class<?> clazz = Class.forName(className);
            Object obj = JSONUtil.toBean(json, clazz);
            log.info("[RedisDao] getObject OK: key={}, class={}, jsonLen={}", key, className, json.length());
            return obj;
        } catch (ClassNotFoundException e) {
            log.error("[RedisDao] getObject 类未找到: {}", className);
            return null;
        }
    }

    @Override
    public void setObject(String key, Object value, long timeout) {
        if (value == null) {
            return;
        }
        String className = value.getClass().getName();
        String json = JSONUtil.toJsonStr(value);
        log.info("[RedisDao] setObject: key={}, class={}, jsonLen={}, timeout={}", key, className, json.length(), timeout);
        set(key, className + "|" + json, timeout);
    }

    @Override
    public void updateObject(String key, Object value) {
        if (value == null) {
            return;
        }
        String className = value.getClass().getName();
        String json = JSONUtil.toJsonStr(value);
        log.info("[RedisDao] updateObject: key={}, class={}, jsonLen={}", key, className, json.length());
        update(key, className + "|" + json);
    }

    @Override
    public void deleteObject(String key) {
        delete(key);
    }

    @Override
    public long getObjectTimeout(String key) {
        return getTimeout(key);
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        updateTimeout(key, timeout);
    }

    // ==================== 会话搜索 ====================

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        Set<String> keys = stringRedisTemplate.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> list = new ArrayList<>(keys);

        // 关键词过滤
        if (keyword != null && !keyword.isEmpty()) {
            list = list.stream()
                    .filter(k -> k.contains(keyword))
                    .collect(Collectors.toList());
        }

        // 排序
        Collections.sort(list);
        if (!sortType) {
            Collections.reverse(list);
        }

        // 分页
        int fromIndex = Math.min(start, list.size());
        int toIndex = size < 0 ? list.size() : Math.min(start + size, list.size());
        if (fromIndex >= toIndex) {
            return Collections.emptyList();
        }

        return list.subList(fromIndex, toIndex);
    }
}
