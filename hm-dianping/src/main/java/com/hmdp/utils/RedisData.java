package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    // 该类用于数据热加载，通过设置逻辑过期事件告诉程序他是否过期，
    // 可以在还未获取到未过期数据的时候返回旧数据

    private LocalDateTime expireTime;

    //Object可以继承别的类，该类具有高可扩展性
    private Object data;
}
