package com.buyTogether.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.beans.beancontext.BeanContext;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private  String name;
    private StringRedisTemplate stringRedisTemplate;

    public String lock_prefix = "lock:";
    private static final String id_prefix = UUID.randomUUID().toString(true)+"-";

    // 类在加载的时候，脚本就初始化完成了，不需要每次执行前才加载
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // resource 就是 ClassPathResource 所以直接在里边写“unlock.lua”即可
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 进程一获取了锁，但是过一会他阻塞了，然后锁超时释放了，
     * 这时另一个进程成功的获取了锁，他正在执行任务，
     * 然后第一个进程醒了，醒了之后要释放锁，这时锁已经不是第一个进程的了，他把别人的锁释放了，
     * 别人的进程还没执行完呢（生气 T_T）
     *
     * 疑问：key上不是有用户的id吗，别人怎么释放
     * 注意 可以是相同用户发出的多个请求，这时他们的key是相同的，可以互相删
     * @param timeoutSec  锁持有的超时时间，过期后自动释放
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();
        //由于不同集群下的进程号可能会重复，这里用UUID拼上PID作为value，在删除锁的时候判断进程要删的锁的值和真正的锁的值是否是一个锁，防止删错了
        String value = id_prefix+threadId;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lock_prefix+name,value,timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    /**
     * 确保删除的锁是自己的锁
     * 判断和删除都是在lua脚本中执行的，保证了原子性
     */
    public void unLock() {
        // lua脚本
        // 应该在每次释放锁之前就将文件读取，
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(lock_prefix+name),
                id_prefix+Thread.currentThread().getId()
        );
    }

    /**
     public void unLock() {
        long threadId = Thread.currentThread().getId();
        // 这个进程的id号
        String value = id_prefix+threadId;
        // value2是当前真正的锁的所有者
        String value2 = stringRedisTemplate.opsForValue().get(lock_prefix+name);
        if(value.equals(value2)){
            stringRedisTemplate.delete(value);
        }

    }*/
}
