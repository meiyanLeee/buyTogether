package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisGlobalId;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
@Disabled("Manual Redis/MySQL data warm-up and pressure-test utilities; run explicitly when local services are ready.")
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private CacheClient cacheClient;

    @Test
    void testSaveShop() throws InterruptedException {
        System.out.println("执行测试");
        //shopService.saveShop2Redis(3L,30L);

        Shop shop = shopService.getById(1L);

        cacheClient.setLogicalExpireTime(CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将所有优惠券都加载到redis中，方便秒杀
     */
    @Test
    public void loadStockToRedis() {
        // 1. 查询所有秒杀券
        List<SeckillVoucher> list = seckillVoucherService.list();
        for (SeckillVoucher v : list) {
            String key = "seckill:stock:" + v.getVoucherId();
            stringRedisTemplate.opsForValue().set(key, v.getStock().toString());
        }
    }

    @Resource
    private RedisGlobalId redisGlobalId;
    @Resource
    private IUserService userService;
    @Test
    public void generate100UsersAndTokens() throws IOException {
        // 输出到项目根目录的 tokens.txt
        PrintWriter printWriter = new PrintWriter(new FileWriter("tokens.txt"));

        // 循环生成 100 个用户
        for (int i = 0; i < 100; i++) {
            // 生成一个不重复的手机号
            String phone = "138000" + String.format("%04d", i);

            // 1. 发送验证码（会存入Redis）
            userService.sendCode(phone, null);

            // 2. 从Redis取出验证码
            String code = stringRedisTemplate.opsForValue().get("login:code:" + phone);

            // 3. 构建登录表单
            LoginFormDTO loginForm = new LoginFormDTO();
            loginForm.setPhone(phone);
            loginForm.setCode(code);

            // 4. 执行登录 → 自动创建用户 + 返回token
            Result result = userService.login(loginForm, null);
            String token = (String) result.getData();

            // 5. 写入文件
            printWriter.println(token);

            // 控制台打印，方便查看进度
            System.out.println("第 " + (i + 1) + " 个用户，手机号：" + phone + "，token：" + token);
        }

        // 关闭流
        printWriter.close();
        System.out.println("===== 100 个用户生成完成，token 已保存到 tokens.txt =====");
    }


    @Test
    void addgroup() {
        // 直接执行 XGROUP CREATE 命令，不走Lua
        stringRedisTemplate.opsForStream().createGroup("stream.orders", "g1");
    }

    @Test
    void addGeoShop(){
        // 将所有店铺查询的出来
        List<Shop> list = shopService.list();
        // 创建一个map，让所有数据都按店铺类型分类，key是店铺类型，value是shop数据
        Map<Long,List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 获取map中的key和value，存入list
        for(Map.Entry<Long,List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY+typeId;
            List<Shop> shopList = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
            for(Shop shop : shopList){
                // stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);

        }
        // 将每一个list存入redis中
    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j = i%1000;
            values[j] = "user_" + i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hl1",values);
            }
        }
        //统计数量
        Long hl1 = stringRedisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println("count="+hl1);
    }


}












