package com.hpj.admin.modules.sys.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpj.admin.modules.sys.entity.User;
import com.hpj.admin.modules.sys.mapper.UserMapper;
import com.hpj.admin.modules.sys.service.UserService;
import org.springframework.stereotype.Service;

/**
 * @author huangpeijun
 * @date 2020/3/4
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

}
