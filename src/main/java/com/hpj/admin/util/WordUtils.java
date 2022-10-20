package com.hpj.admin.util;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

public class WordUtils {

    /**
     * 动态渲染表格（根据二维数组动态渲染表格，二维数组的行和列对应表格的行和列）
     * @param document  word文档
     * @param data      表格数据（二维数组）
     */
    public static void createTable(XWPFDocument document, List<List<String>> data) {
        // 计算行和列，取最大的size作为列数
        int col = data.stream().map(List::size).max(Comparator.comparing(e -> e)).orElse(0);
        int row = data.size();
        // 行或列数为0时无法渲染表格
        if (row == 0 || col == 0) {
            return;
        }
        XWPFTable table = document.createTable(row, col);
        // 表格宽度设置为100%，不设置为100%可能会有文字被压缩
        table.setWidth("100%");
        // 循环渲染每一行
        for (int i = 0; i < data.size(); i++) {
            // 获取行信息
            XWPFTableRow tableRow = table.getRow(i);
            List<String> rowData = data.get(i);
            // 循环渲染每个单元格
            for (int j = 0; j < rowData.size(); j++) {
                XWPFTableCell cell = tableRow.getCell(j);
                cell.setText(rowData.get(j));
            }
        }
    }

    /**
     * 绘制barChart图表（柱状图）
     * @param document        word文档
     * @param chartParameter  图表参数
     */
    public static void createBarChart(XWPFDocument document, ChartParameter chartParameter) {
        try {
            XWPFChart chart = createChart(document, chartParameter.getTitle());
            XDDFCategoryAxis categoryAxis = createCategoryAxis(chartParameter.getXTitle(), chart);
            XDDFValueAxis valueAxis = createValueAxis(chartParameter.getYTitle(), chart);
            // 设置数据居中，不居中会有数据被遮挡
            valueAxis.setCrossBetween(AxisCrossBetween.BETWEEN);
            // 创建柱状图
            XDDFBarChartData barChart = (XDDFBarChartData) chart.createData(ChartTypes.BAR, categoryAxis, valueAxis);
            barChart.setBarDirection(BarDirection.COL);
            // 加载柱状图数据集
            XDDFCategoryDataSource categoryDataSource = XDDFDataSourcesFactory.fromArray(chartParameter.getXNames());
            // 循环添加y轴数据以及分类数据
            for (ChartParameter.GroupData groupData : chartParameter.getGroupDataList()) {
                XDDFNumericalDataSource<Double> numericalDataSource = XDDFDataSourcesFactory.fromArray(groupData.getValues());
                XDDFBarChartData.Series series = (XDDFBarChartData.Series) barChart.addSeries(categoryDataSource, numericalDataSource);
                series.setTitle(groupData.getTypeName(), null);
            }
            // 绘制图形
            chart.plot(barChart);
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建lineChart图表（折线图）
     * @param document        word文档
     * @param chartParameter  图表参数
     */
    public static void createLineChart(XWPFDocument document, ChartParameter chartParameter) {
        try {
            // 创建chart图表和x y轴信息
            XWPFChart chart = createChart(document, chartParameter.getTitle());
            XDDFCategoryAxis categoryAxis = createCategoryAxis(chartParameter.getXTitle(), chart);
            XDDFValueAxis valueAxis = createValueAxis(chartParameter.getYTitle(), chart);
            // 创建折线图
            XDDFChartData lineChart = chart.createData(ChartTypes.LINE, categoryAxis, valueAxis);
            // 加载折线图数据集
            XDDFCategoryDataSource categoryDataSource = XDDFDataSourcesFactory.fromArray(chartParameter.getXNames());
            // 循环添加y轴数据和分类数据
            for (ChartParameter.GroupData groupData : chartParameter.getGroupDataList()) {
                XDDFNumericalDataSource<Double> numericalDataSource = XDDFDataSourcesFactory.fromArray(groupData.getValues());
                // 强转类型可以进行折线的属性设置
                XDDFLineChartData.Series series = (XDDFLineChartData.Series)lineChart.addSeries(categoryDataSource, numericalDataSource);
                series.setTitle(groupData.getTypeName(), null);
                // 线条样式：true平滑曲线，false折线
                series.setSmooth(true);
                // 标记点大小
                series.setMarkerSize((short) 6);
                // 标记点样式
                series.setMarkerStyle(MarkerStyle.CIRCLE);
            }
            // 绘制图形
            chart.plot(lineChart);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建chart图表
     * @param document   word文档
     * @param title      图表标题
     * @return  chart图表对象
     */
    private static XWPFChart createChart(XWPFDocument document, String title) throws IOException, InvalidFormatException {
        // 创建chart对象（宽15厘米，高10厘米）
        XWPFChart chart = document.createChart(15 * Units.EMU_PER_CENTIMETER, 10 * Units.EMU_PER_CENTIMETER);
        // 设置chart标题
        chart.setTitleText(title);
        // 设置图例不覆盖标题
        chart.setTitleOverlay(false);
        // 设置图例位置在图表上方
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.TOP);
        return chart;
    }

    /**
     * 创建x轴
     * @param title  x轴标题
     * @param chart  chart图表对象
     * @return       x轴信息
     */
    private static XDDFCategoryAxis createCategoryAxis(String title, XWPFChart chart) {
        // 设置x轴位置为底部
        XDDFCategoryAxis categoryAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        categoryAxis.setTitle(title);
        return categoryAxis;
    }

    /**
     * 创建y轴
     * @param title  y轴标题
     * @param chart  chart图表对象
     * @return       y轴信息
     */
    private static XDDFValueAxis createValueAxis(String title, XWPFChart chart) {
        // 设置y轴位置为左边
        XDDFValueAxis valueAxis = chart.createValueAxis(AxisPosition.LEFT);
        valueAxis.setTitle(title);
        return valueAxis;
    }
}
