package com.hmdp.service.impl;

import cn.hutool.core.io.resource.ClassPathResource;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisidWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
@Autowired
private ISeckillVoucherService seckillVoucherService;
@Resource
private RedisidWorker redisidWorker;
@Autowired
private StringRedisTemplate stringRedisTemplate;
@Resource
private RedissonClient redissonClient;

private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private static final DefaultRedisScript<Long> SKILL_SCRIPT;
    static {
        SKILL_SCRIPT = new DefaultRedisScript<Long>();
        //SKILL_SCRIPT.setLocation((org.springframework.core.io.Resource) new ClassPathResource("skill.lua"));
        SKILL_SCRIPT.setResultType(Long.class);
    }

    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1执行LUA脚本
        Long result = stringRedisTemplate.execute(
                SKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2判断是否为零
        int r=result.intValue();
        if(r!=0){
            //2.1不为0，没有购买资格
            return Result.fail(r==1? "库存不足":"不能重复购买");
        }

        //2.2 为0，有购买资格
        VoucherOrder voucherOrder=new VoucherOrder();
        //订单id
        long orderId = redisidWorker.nextid("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金卷id
voucherOrder.setVoucherId(voucherId);
        // TODO保存到阻塞队列
        orderTasks.add(voucherOrder);
        //3.返回订单id


    return Result.ok(orderId);
    }
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock()<1) {
//            //库存不足
//            return Result.fail("库存不足");        }
//        Long userid = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock lock=new SimpleRedisLock(stringRedisTemplate,"order:"+userid);
//        RLock lock = redissonClient.getLock("lock:order:" + userid);
//        //获取锁
//
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            return Result.fail("一人只能下一单");
//        }
//        try {
//            //获取代理对象（事务）
//            IVoucherOrderService proxy= (IVoucherOrderService)AopContext.currentProxy();
//            return creatVoucher(voucherId);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }
    @Transactional
    public synchronized Result creatVoucher(Long voucherId){
        //6一人一单
        Long userid = UserHolder.getUser().getId();
            //6.1查询订单
            int count = query().eq("user_id", userid).eq("voucher_id", voucherId).count();
            //6.2判断订单是否存在
            if (count > 0) {
                return Result.fail("用户已经下过一单");
            }
            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                //扣减失败
                return Result.fail("库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderid = redisidWorker.nextid("order");
            voucherOrder.setId(orderid);
            //用户id

            voucherOrder.setUserId(userid);
            //优惠卷id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //返回订单ID
            return Result.ok(orderid);

    }

}
