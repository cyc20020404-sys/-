package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@Slf4j

public class RedisidWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP=1640995200L;
    private static final int COUNT_BITS=32;

    public long nextid(String keyprefix){
        //生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-BEGIN_TIMESTAMP;
        //生成序列号
        //2.1获取当前日期精确到天
        String data=  now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
               // 2.2自增长
        Long count=stringRedisTemplate.opsForValue().increment("inc"+keyprefix+":"+data);
        //拼接并返回
     return  timestamp<<COUNT_BITS |count;
    }
}
