package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopTypeMapper shopTypeMapper;

    public List<ShopType> shopTypeList(){
        //查询有无缓存，有就直接返回
        String key = CACHE_SHOP_TYPE_KEY ;
        String shopType = stringRedisTemplate.opsForValue().get(key);

        if(!StrUtil.isBlank(shopType)) return JSONUtil.toList(shopType, ShopType.class);
        //没有查数据库
        List<ShopType> list = new ArrayList<>();
        list = shopTypeMapper.selectList(null);

        //数据库没有就返回错误
        if(list.size()==0){
            return null;
        }

        //有就加到redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(list),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        //返回结果
        return list;
    }



}
