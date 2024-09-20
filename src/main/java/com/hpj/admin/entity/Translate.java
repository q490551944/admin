package com.hpj.admin.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;
import lombok.Data;

@Data
public class Translate {

    @Excel(name = "英文", width = 20)
    private String en;

    @Excel(name = "中文", width = 50)
    private String zh;
}
