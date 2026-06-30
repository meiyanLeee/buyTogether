package com.buyTogether.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.buyTogether.dto.UserDTO;
import org.aopalliance.intercept.Interceptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.buyTogether.utils.RedisConstants.LOGIN_USER_KEY;
import static com.buyTogether.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头中的authorization
        String token = request.getHeader("authorization");
        //看是否为空,为空就放行
        if(StrUtil.isBlank(token)){
            return true;
        }
        //在redis中查询数据
        String tokenKey = LOGIN_USER_KEY+token;

        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(tokenKey);

        //如果为空，放行
        if(map.isEmpty()){
            return true;
        }
        //不为空，写入ThreadLocal
        UserDTO userDTO = new UserDTO();
        BeanUtil.fillBeanWithMap(map,userDTO,false);
        UserHolder.saveUser(userDTO);
        //更新有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }


}
