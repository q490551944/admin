package com.hpj.admin.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * @author huangpeijun
 * @date 2021/5/16
 */
public class UdpServer {


    public static void main(String[] args) {
        int[] ints = new int[]{};
        List<Integer> result = getResult(ints);
        System.out.println(result);
    }

    public static List<Integer> getResult(int[] source) {
        List<Integer> result = new ArrayList<>();
//        for (int i : source) {
        for (int i = 1000; i < 10000; i++) {
            int j = i * 9;
            String sj = String.valueOf(j);
            String si = String.valueOf(i);
            char[] chars = si.toCharArray();
            char[] chars1 = sj.toCharArray();
            if (chars1.length > chars.length) {
                continue;
            }
            boolean flag = true;
            for (int k = 0; k < chars.length; k++) {
                if (chars[k] != chars1[chars1.length - k - 1]) {
                    flag = false;
                    break;
                }
            }
            if (flag) {
                result.add(i);
            }
        }

//        }
        return result;
    }
}
