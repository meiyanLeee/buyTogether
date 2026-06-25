package com.hmdp.utils;

import org.apache.tomcat.jni.Time;
import org.apache.tomcat.util.bcel.Const;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Component
//全局id生成器
public class RedisGlobalId {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public final int COUNT_BITS = 32;
    public Long globalId(String keyPrefix){
        Long start = 1778025600L;
        //生成时间戳
        LocalDateTime time = LocalDateTime.now();
        Long time1 = time.toEpochSecond(ZoneOffset.UTC);
        Long timeStamp = time1-start;

        //生成序列号
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long increment = stringRedisTemplate.opsForValue().increment("incr:" + keyPrefix + ":"+date);

        //拼接（时间戳做位运算）
        return timeStamp<<COUNT_BITS | increment;
    }
//    从1970-01-01 00:00:00 UTC到现在的秒数
//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2026,5,6,0,0,0);
//        Long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second="+second);
//
//    }

}
