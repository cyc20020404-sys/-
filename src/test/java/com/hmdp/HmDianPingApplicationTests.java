package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;

import com.hmdp.utils.RedisidWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;



import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
@Resource
private RedisidWorker redisidWorker;
private ExecutorService es = Executors.newFixedThreadPool(500);
@Test
void testidwork() throws InterruptedException {
    CountDownLatch latch=new CountDownLatch(300);
    Runnable task=()->{
for (int i=0;i<100;i++){
    long id=redisidWorker.nextid("order");
    System.out.println("id="+id);
        }
latch.countDown();
    };
long begin=System.currentTimeMillis();
    for(int i=0;i<300;i++){
        es.submit(task);
    }
latch.await();
    long end=System.currentTimeMillis();
    System.out.println("time="+(end-begin));
}



    @Test
    public void testSaveShop() {
        shopService.saveShop2Redis(1L, 10L);
    }

}
