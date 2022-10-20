package com.hpj.admin.util;

import lombok.Data;

import java.util.List;

/**
 * word chart图标参数
 */
@Data
public class ChartParameter {
    /**
     * 图表标题
     */
    private String title;
    /**
     * x轴标题
     */
    private String xTitle;
    /**
     * y轴标题
     */
    private String yTitle;
    /**
     * x轴数据集
     */
    private String[] xNames;
    /**
     * y周数据集
     */
    private List<GroupData> groupDataList;

    @Data
    public static class GroupData{
        /**
         * 类型名称
         */
        private String typeName;
        /**
         * 类型数据
         */
        private Double[] values;
    }
}
