package com.hpj.admin.util;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import com.google.common.collect.Lists;
import com.hpj.admin.entity.PerformanceInfo;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ExcelUtils {

    public static void main(String[] args) throws IOException {
        PerformanceInfo performanceInfo = new PerformanceInfo();
        performanceInfo.setCount(1);
        List<PerformanceInfo.ModelInfo> list = new ArrayList<>();
        performanceInfo.setList(list);
        for (int i = 0; i < 100; i++) {
            PerformanceInfo.ModelInfo modelInfo = new PerformanceInfo.ModelInfo();
            modelInfo.setName("a");
            List<PerformanceInfo.PortInfo> portInfos = new ArrayList<>();
            for (int j = 0; j < 10; j++) {
                PerformanceInfo.PortInfo portInfo = new PerformanceInfo.PortInfo();
                portInfo.setPortName("baseAttr");
                portInfo.setTime(new Random().nextInt());
                portInfo.setAmount(BigDecimal.valueOf(2.22222222222));
                portInfos.add(portInfo);
            }
            modelInfo.setPortInfos(portInfos);
            list.add(modelInfo);
        }
        System.out.println(performanceInfo);
        ExportParams params = new ExportParams();
        params.setStyle(ExcelExportStatisticStyler.class);
        Workbook workbook = ExcelExportUtil.exportExcel(params, PerformanceInfo.class, Lists.newArrayList(performanceInfo));
        OutputStream os = new FileOutputStream("C:\\workerspace\\test\\test.xlsx");
        workbook.write(os);
    }
}
