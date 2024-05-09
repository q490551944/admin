package com.hpj.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpj.admin.entity.Role;
import org.springframework.stereotype.Repository;

/**
 * @author Huangpeijun
 */
@Repository
public interface RoleMapper extends BaseMapper<Role> {

    int insertSelective(Role record);
}