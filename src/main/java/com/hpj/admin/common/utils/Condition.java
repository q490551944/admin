package com.hpj.admin.common.utils;

import lombok.Data;

/**
 * @author huangpeijun
 * @date 2020/3/9
 */
@Data
public class Condition {

    private int page;

    private int size;

    private boolean pageable = true;
}
