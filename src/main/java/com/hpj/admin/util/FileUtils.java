package com.hpj.admin.util;

import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FileUtils {

    public static final String COMMA_STRING = ",";

    /**
     * 解析csv文件
     * @param inputStream 输入流
     */
    public static List<List<String>> parseCsv(InputStream inputStream) {
        List<List<String>> result = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try {
            lines = IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
        }catch (IOException e) {
            e.printStackTrace();
        }
        // csv文件每一行以逗号分隔
        for (String line : lines) {
            result.add( Arrays.asList(line.split(COMMA_STRING)));
        }
        return result;
    }

}
