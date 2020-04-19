package com.hpj.admin.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * @author huangpeijun
 * @date 2020/3/10
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        boolean createTime = metaObject.hasSetter("createTime");
        boolean updateTime = metaObject.hasSetter("updateTime");
        if (createTime || updateTime) {
            LocalDateTime now = LocalDateTime.now();
            if (createTime) {
                this.setFieldValByName("createTime", now, metaObject);
            }
            if (updateTime) {
                this.setFieldValByName("updateTime", now, metaObject);
            }
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
    }
}
