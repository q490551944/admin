package com.hpj.admin.util.sort;

import java.util.Arrays;

public class SortTest {

    public static void main(String[] args) {
        Insertion<Integer> insertion = new Insertion<>();
        Integer[] ints = {5, 2, 4, 1, 3};
        insertion.sort(ints);
        System.out.println(Arrays.toString(ints));
        System.out.println(Math.log(4));
    }
}
