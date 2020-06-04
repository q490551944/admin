package com.hpj.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.Role;

/**
 * @author Huangpeijun
 */
public interface RoleMapper extends BaseMapper<Role> {

    int insertSelective(Role record);
}