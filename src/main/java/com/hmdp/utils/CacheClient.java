package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
public class CacheClient {
@Resource
    private StringRedisTemplate stringRedisTemplate;
public  void set(String key, Object value, Long time, TimeUnit unit){
stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
}

    public  void setWithLoginExpire(String key, Object value, Long time, TimeUnit unit){
    //设置逻辑过期
    RedisData redisData=new RedisData();
    redisData.setData(value);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID>R queryWithPassThough(String keyprefix, ID id, Class<R>type, Function<ID,R> dbfallback, Long time, TimeUnit unit) {
   String key=keyprefix+id;
        // 从 Redis 中查询缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        // 判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            // 存在，返回商铺信息
           //反序列化
            return JSONUtil.toBean(Json, type);
        }
        //判断是否命中空值
        if (Json != null) {
            return null;
        }
        // 不存在则根据 id 查询数据库商铺信息
       R r= dbfallback.apply(id);
        // 不存在，返回nuu到REDIS，并设置TTL
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
            return null;
        }

        // 存在，写入 Redis
    this.set(key,r,time,unit);
        // 并返回商铺信息
        return r;
    }

    //获取锁
    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //施放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    private static final ExecutorService CHACE_REBUID_EXECUTOR= Executors.newFixedThreadPool(10);
    public <R,ID>R queryWithLoginExpire(String keyprefix, ID id,Class<R> type,Function<ID,R> dbFallBack,Long time, TimeUnit unit) {
        String key=keyprefix+id;
        // 1从 Redis 中查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);

        // 2判断是否存在
        if (StrUtil.isBlank(Json)) {
            // 3 未命中，返回
            return null;
        }
        //4命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
       R r = JSONUtil.toBean((JSONObject)redisData.getData(),type);
        //5判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，返回店铺信息

            return  r;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey=LOCK_SHOP_KEY+id;
        boolean isLock = trylock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock)
        {
            //6.3成功，开启独立线程，完成缓存重建
            CHACE_REBUID_EXECUTOR.submit(()->{
                try {
                //查询数据库
                    R r1 = dbFallBack.apply(id);
                    //写入redis
                    this.setWithLoginExpire(key,r1,time,unit);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期商铺信息
        return r;
    }












}
