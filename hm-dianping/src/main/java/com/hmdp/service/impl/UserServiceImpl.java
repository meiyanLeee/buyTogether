package com.hmdp.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (!RegexUtils.isCodeInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("社区团购登录验证码已发送，phone={}, code={}", phone, code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (!RegexUtils.isCodeInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        User user = query().eq("phone", phone).one();

        // 判断登录方式：有code走验证码登录，有password走密码登录
        if (loginForm.getCode() != null && !loginForm.getCode().isEmpty()) {
            // ========== 验证码登录 ==========
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
                return Result.fail("验证码错误或已过期");
            }
            // 验证码登录自动注册
            if (user == null) {
                user = createByPhone(phone);
            }
            stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        } else if (loginForm.getPassword() != null && !loginForm.getPassword().isEmpty()) {
            // ========== 密码登录 ==========
            if (user == null) {
                return Result.fail("手机号未注册，请使用验证码登录");
            }
            if (user.getPassword() == null || !PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
                return Result.fail("密码错误");
            }
        } else {
            return Result.fail("请输入验证码或密码");
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        String maskedToken = token == null ? "null" : token.substring(0, Math.min(8, token.length())) + "...";
        log.info("[Login] StpUtil.login完成, userId={}, token={}", user.getId(), maskedToken);
        // 验证存储是否成功

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String yearMonth = now.format(DateTimeFormatter.ofPattern(":yyyy:MM:"));
        String key = USER_SIGN_KEY + userId + yearMonth;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String yearMonth = now.format(DateTimeFormatter.ofPattern(":yyyy:MM:"));
        String key = USER_SIGN_KEY + userId + yearMonth;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(8));
        save(user);
        return user;
    }
}
