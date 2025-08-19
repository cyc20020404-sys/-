package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SimpleRedisLock implements ILock{
    @Resource
private StringRedisTemplate stringRedisTemplate;
    private  String name;
    private static  final String KEY_PREFIX="lock";
    private static  final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";


    public boolean tryLock(Long timeoutSec) {
        //获取线程id
        String threadId =ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //获取线程标识
        String threadId =ID_PREFIX+Thread.currentThread().getId();
        //获取锁中标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否是当前线程
if (threadId.equals(id)){
    //释放锁
    stringRedisTemplate.delete(KEY_PREFIX+name);

}
    }
}
