package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserResolver {

    private final IUserService userService;

    public AuthenticatedUserResolver(IUserService userService) {
        this.userService = userService;
    }

    public UserDTO resolve(Object loginId) {
        if (loginId == null) {
            return null;
        }
        Long userId = Long.valueOf(loginId.toString());
        User user = userService.getById(userId);
        if (user == null) {
            return null;
        }
        return BeanUtil.copyProperties(user, UserDTO.class);
    }
}
