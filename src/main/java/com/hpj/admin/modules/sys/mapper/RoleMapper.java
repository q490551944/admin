package com.hpj.admin.modules.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.modules.sys.entity.Role;

/**
 * @author Huangpeijun
 */
public interface RoleMapper extends BaseMapper<Role> {

    int insertSelective(Role record);
}