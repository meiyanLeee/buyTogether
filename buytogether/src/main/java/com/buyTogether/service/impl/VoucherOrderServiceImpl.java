package com.buyTogether.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.buyTogether.dto.Result;
import com.buyTogether.entity.SeckillVoucher;
import com.buyTogether.entity.VoucherOrder;
import com.buyTogether.mapper.VoucherOrderMapper;
import com.buyTogether.service.ISeckillVoucherService;
import com.buyTogether.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.buyTogether.utils.ILock;
import com.buyTogether.utils.RedisGlobalId;
import com.buyTogether.utils.SimpleRedisLock;
import com.buyTogether.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisGlobalId redisGlobalId;

    @Resource
    private RedissonClient redissonClient;

    // 在当前类中创建一个代理，这样子线程也能通过当前类调用代理
    private IVoucherOrderService proxy;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 当一个线程尝试从阻塞队列获取元素的时候，如果没有元素，线程会被阻塞
    // 直到队列中有元素线程才会被唤醒
    //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池和线程任务
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //当类初始化完成，就向线程池提交VoucherOrderHandler任务
    @PostConstruct
    private void init(){
        initStreamConsumerGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initStreamConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
        } catch (Exception e) {
            log.debug("订单消息队列消费组已存在或暂不可初始化: " + e.getMessage());
        }
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        public void run(){
            while(true){
                try {
                    // 1.获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // 大于号代表最后一条未消费的元素
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1如果没有获取成功直接continue
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();

                    // 将map转为order
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.获取成功，下单
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK确认任务完成
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    // 5.有异常，去pending队列中取元素
                    log.error("处理团购订单异常，准备检查 pending 队列", e);
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    // 6.有元素，尝试处理异常

                    e.printStackTrace();
                }
            }
        }

        private void handlePendingList() throws InterruptedException {
            while(true){
                try {
                    // 1.获取pending list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    // 大于号代表最后一条未消费的元素
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                            //from 0 就是读pending list 的标志
                    );

                    // 2.判断消息获取是否成功
                    if(list == null || list.isEmpty()){
                        // 2.1如果没有获取成功直接退出，返回调用方
                        break;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();

                    // 将map转为order
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 4.获取成功，下单
                    handleVoucherOrder(voucherOrder);
                    // 5.ACK确认任务完成
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    // 5.有异常，去pending队列中取元素
                    log.error("处理 pending 团购订单异常", e);
                    Thread.sleep(20);
                }
            }
        }
    }


//    private class VoucherOrderHandler implements Runnable{
//        public void run(){
//            while(true){
//
//                try {
//                    // 1.获取队列中的订单信息
//                    VoucherOrder order = orderTasks.take();
//                    // 2.创建订单
//                    handleVoucherOrder(order);
//                } catch (Exception e) {
//                    log.error("处理订单异常");
//                    e.printStackTrace();
//                }
//            }
//        }
//    }

    //创建订单
    public void handleVoucherOrder(VoucherOrder order){
        long userId = order.getUserId();
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.warn("用户 " + userId + " 已有团购券下单任务处理中，拒绝重复提交");
            return ;
        }
        // 获取锁成功，去处理数据库
        try{
            proxy.createVoucherOrder(order);
        }finally {
            lock.unlock();
        }
    }

    /**
     * 限时团购抢购，用 Lua 脚本预扣库存并投递 Stream，返回值为 0 说明获得下单资格。
     * @return
     */
    // 这里先给用户判断一下是否有剩余票，是否是第一次下单，通过返回值re看是否有抢券资格
    // re为0就返回订单号，响应迅速
    // 数据库操作交给子线程执行
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisGlobalId.globalId("order");
        // 将订单信息交给lua脚本，lua判断是否符合条件，将订单信息交到消息队列
        // 同时这个类中有一个在初始化类的时候就启动的任务，自动处理消息队列中的消息
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int re = result.intValue();
        if (re != 0) {
            return Result.fail(re == 1 ? "团购券库存不足" : "每位用户限购一份");
        }

        // 获取代理对象
        // 这是主线程中的执行的函数
        // 在这里获取代理对象，方便后续子线程在数据库中处理订单的传参操作

        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回成功
        return Result.ok(orderId);
    }
    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisGlobalId.globalId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int re = result.intValue();
        if(re != 0){
            return Result.fail(re == 1?"库存不足":"不能重复下单");
        }

        //2.2 为0，有购买资格，把下单信息存到阻塞队列
        // 获取订单id，随机生成的下一个随机id

        //将order的信息都放到voucherOrder中，并加入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder().setVoucherId(voucherId)
                .setId(orderId)
                .setUserId(userId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 返回成功
        return Result.ok(orderId);
    }*/


    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        long voucherId = order.getVoucherId();
        // 一人一单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if(!VoucherOrderPolicy.canCreateOrder(count, success)){
            log.warn("团购订单创建被拒绝，userId=" + userId
                    + ", voucherId=" + voucherId
                    + ", existingOrderCount=" + count
                    + ", stockDeducted=" + success);
            return;
        }
        //在数据库中添加订单
        save(order);
    }




//
//    public Result seckillVoucher(Long seckillVoucherId) {
//
//        // 看优惠券是否到了抢购的时间
//        LocalDateTime now = LocalDateTime.now();
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(seckillVoucherId);
//        LocalDateTime start = seckillVoucher.getBeginTime();
//        LocalDateTime end = seckillVoucher.getEndTime();
//        //没到，返回错误
//        if(now.isBefore(start)){
//            return Result.fail("秒杀还未开始");
//        } else if (now.isAfter(end)) {
//            return Result.fail("秒杀已结束");
//        }
//
//        //到了，查看库存是否大于0
//        // 秒杀的时候是去数据库查吗，还是在redis里？
//        // 额，在数据库查
//        Integer stock = seckillVoucher.getStock();
//        //小于零，返回错误
//        if(stock<1){
//            return Result.fail("该秒杀券5已抢空");
//        }
//
//
//        /**用synchronized解决但集群下的高并发
//         * 用到了常量池
//         * intern(),保证相同字符串创建的是同一个对象，这样这个锁才锁得住它
//         * 不在函数体内部加锁是保证事务提交上午之后才释放锁，避免在提交事务前，
//         * 其他线程获取了锁，修改了数据，这时事务再提交上去就是提交的脏数据
//         */
//        Long userId = UserHolder.getUser().getId();
//        /*
//
//        synchronized(userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(seckillVoucherId);
//        }*/
//
//        // 保证一人一单所以肯定是锁的key上一定有id呀
//        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("一个用户只能下一单哦");
//        }
//        //只有成功锁住了才能释放锁
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(seckillVoucherId);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }
//


}
