package com.buyTogether.utils;

import cn.hutool.core.bean.BeanUtil;
import com.buyTogether.dto.UserDTO;
import com.buyTogether.entity.User;
import com.buyTogether.service.IUserService;
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
