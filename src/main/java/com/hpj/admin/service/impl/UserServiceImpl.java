package com.hpj.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.entity.User;
import com.hpj.admin.mapper.UserMapper;
import com.hpj.admin.service.UserService;
import org.springframework.stereotype.Service;

/**
 * @author huangpeijun
 * @date 2020/3/4
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

}
