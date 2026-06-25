package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedUserResolverTest {

    @Test
    void resolvesUserDtoFromSaTokenLoginId() {
        IUserService userService = mock(IUserService.class);
        User user = new User()
                .setId(1011L)
                .setNickName("邻里用户")
                .setIcon("/imgs/community/photo5.png");
        when(userService.getById(1011L)).thenReturn(user);

        AuthenticatedUserResolver resolver = new AuthenticatedUserResolver(userService);

        UserDTO userDTO = resolver.resolve(1011L);

        assertEquals(1011L, userDTO.getId());
        assertEquals("邻里用户", userDTO.getNickName());
        assertEquals("/imgs/community/photo5.png", userDTO.getIcon());
    }
}
