package com.hpj.admin.util.sort;

public class Bubble<T extends Comparable<T>> extends Sort<T>{
    @Override
    public void sort(T[] nums) {
        int n = nums.length;
        boolean isSorted = false;
        for (int i = n - 1; i > 0 && !isSorted; i--) {
            isSorted = true;
            for (int j = 0; j < i; j++) {
                if (less(nums[j + 1], nums[j])) {
                    swap(nums, j + 1, j);
                    isSorted = false;
                }
            }
        }
    }
}
