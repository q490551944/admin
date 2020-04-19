package com.hpj.admin.modules.sys.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author huangpeijun
 * @date 2020/3/11
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class Resource extends SuperEntity {

    private String name;

    private Long parentId;

    private String url;

    private Integer type;
}
