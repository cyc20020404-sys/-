package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.injector.methods.UpdateById;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.testng.reporters.JUnitReportReporter;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
@Autowired
    private CacheClient cacheClient;
    @Override
    public Result quetyById(Long id) {
        //调用工具类缓存穿透
        //Shop shop = cacheClient.queryWithPassThough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        //调用工具类用逻辑过期解决缓存击穿问题
        Shop shop = cacheClient.queryWithLoginExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺信息不存在");
        }
        // 并返回商铺信息
        return Result.ok(shop);
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

    public void saveShop2Redis(Long id, Long expireSecond) {
        //查询店铺信息
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        //写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateBy(Shop shop) {
        //判断店铺id不能为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete("shop:" + shop.getId());
        return Result.ok();
    }

    public Shop queryWithMutex(Long id) {
        // 从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("shop:" + id);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//反序列化
            return shop;
        }
        //判断是否命中空值
        if (shopJson != null) {
            return null;
        }
        //4实现缓存重建
        //4.1获取互斥锁
        String lockkey = "lock:shop" + id;
        Shop shop = null;
        try {
            boolean islock = trylock(lockkey);
            //4.2判断是否获取成功
            if (!islock) {
                //4.3失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4成功则根据 id 查询数据库商铺信息
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            // 不存在，返回null到REDIS，并设置TTL
            if (shop == null) {
                stringRedisTemplate.opsForValue().set("shop:" + id, "", 2L, TimeUnit.MINUTES);
                return null;
            }
            // 存在，写入 Redis
            stringRedisTemplate.opsForValue().set("shop:" + id, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockkey);
        }
        // 并返回商铺信息
        return shop;
    }

    public Shop queryWithPassThough(Long id) {
        // 从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("shop:" + id);

        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);//反序列化
            return shop;
        }
        //判断是否命中空值
        if (shopJson != null) {
            return null;
        }
        // 不存在则根据 id 查询数据库商铺信息
        Shop shop = getById(id);
        // 不存在，返回nuu到REDIS，并设置TTL
        if (shop == null) {
            stringRedisTemplate.opsForValue().set("shop:" + id, "", 2L, TimeUnit.MINUTES);
            return null;
        }

        // 存在，写入 Redis
        stringRedisTemplate.opsForValue().set("shop:" + id, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        // 并返回商铺信息
        return shop;
    }
private static final ExecutorService CHACE_REBUID_EXECUTOR= Executors.newFixedThreadPool(10);
    public Shop queryWithLoginExpire(Long id) {
        // 1从 Redis 中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("shop:" + id);

        // 2判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3 未命中，返回
            return null;
        }
         //4命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject)redisData.getData(),Shop.class);
        //5判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，返回店铺信息

            return  shop;
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
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
                    });
        }
        //6.4返回过期商铺信息
        return shop;
    }


}
