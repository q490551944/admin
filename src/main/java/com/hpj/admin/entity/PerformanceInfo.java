package com.hpj.admin.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;
import cn.afterturn.easypoi.excel.annotation.ExcelCollection;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PerformanceInfo {

    @Excel(name = "计数", needMerge = true)
    private Integer count;

    @ExcelCollection(name = "模型信息")
    private List<ModelInfo> list;

    @Data
    public static class ModelInfo {
        @Excel(name = "模型名称", needMerge = true)
        private String name;
        @ExcelCollection(name = "端口信息")
        private List<PortInfo> portInfos;
    }

    @Data
    public static class PortInfo {
        @Excel(name = "端口名称")
        private String portName;
        @Excel(name = "端口耗时", type = 10, numFormat = "0")
        private Integer time;
        @Excel(name = "金额", type = 10, numFormat = "0.00")
        private BigDecimal amount;
    }
}
