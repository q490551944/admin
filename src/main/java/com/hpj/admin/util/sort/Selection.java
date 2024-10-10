package com.hpj.admin.util.sort;

public class Selection<T extends  Comparable<T>> extends Sort<T>{
    @Override
    public void sort(T[] nums) {
        int n = nums.length;
        for (int i = 0; i < n - 1; i++) {
            int min = 0;
            for (int j = i; j < n; j++) {
                if (less(nums[j], nums[min])) {
                    swap(nums, min, j);
                }
            }
        }
    }
}
