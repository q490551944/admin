package com.hpj.admin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.*;

@RunWith(JUnit4.class)
public class AlgorithmTests {

    @Test
    public void reverse() {
        int x = 1534236469;
        long result = 0;
        while(x != 0) {
            int mod = x % 10;
            result = result * 10 + mod;
            x = x / 10;
        }
        if(result > Integer.MAX_VALUE || result < Integer.MIN_VALUE) {
            result = 0;
        }
        System.out.println((int) result);
    }

    @Test
    public void testArraySpecial() {
        int[] nums = {1, 1};
        int[][] queries = {{0, 1}};
        boolean[] result = isArraySpecialDynamic(nums, queries);
        for(boolean b : result) {
            System.out.println(b);
        }
    }

    /**
     * 判断子数组是否为特殊数组，此法时间复杂度O(n^2)，数据量大会超时
     * @param nums     原数组
     * @param queries  下标取值数组
     * @return          结果数组
     */
    public boolean[] isArraySpecial(int[] nums, int[][] queries) {
        boolean[] result = new boolean[queries.length];
        for(int i = 0; i < queries.length; i++) {
            int[] index = queries[i];
            int childLength = index[1] - index[0] + 1;
            int[] child = new int[childLength];
            for(int j = 0; j < childLength; j++) {
                child[j] = nums[j + index[0]];
            }
            if (childLength == 1) {
                result[i] = true;
                continue;
            }
            boolean flag = child[0] % 2 == 0;
            for(int j = 1; j < child.length; j++) {
                int now = child[j];
                if(now % 2 == 0 && flag) {
                    result[i] = false;
                    break;
                } else if(now % 2 == 1 && !flag) {
                    result[i] = false;
                    break;
                } else {
                    result[i] = true;
                }
                flag = child[j] % 2 == 0;
            }
        }
        return result;
    }

    public boolean[] isArraySpecialDynamic(int[] nums, int[][] queries) {
        int n = nums.length;
        int[] dp = new int[n];
        Arrays.fill(dp, 1);
        for (int i = 1; i < n; i++) {
            if (((nums[i] ^ nums[i - 1]) & 1) == 1) {
                dp[i] = dp[i - 1] + 1;
            }
        }
        boolean[] result = new boolean[queries.length];
        for (int i = 0; i < queries.length; i++) {
            int[] query = queries[i];
            int length = query[1] - query[0];
            result[i] = dp[query[1]] > length;
        }
        return result;
    }

    @Test
    public void testMaxScore() {
        List<List<Integer>> grid = new ArrayList<>();
        List<Integer> list = new ArrayList<>();
        list.add(9);
        list.add(5);
        list.add(7);
        list.add(3);
        grid.add(list);
        list = new ArrayList<>();
        list.add(8);
        list.add(9);
        list.add(6);
        list.add(1);
        grid.add(list);
        list = new ArrayList<>();
        list.add(6);
        list.add(7);
        list.add(14);
        list.add(3);
        grid.add(list);
        list = new ArrayList<>();
        list.add(2);
        list.add(5);
        list.add(3);
        list.add(1);
        grid.add(list);
        int i = maxScore(grid);
        System.out.println(i);
    }

    public int maxScore(List<List<Integer>> grid) {
        Integer m = grid.get(0).size();
        Integer result = Integer.MIN_VALUE;
        int[] minimums = new int[m];
        Arrays.fill(minimums, Integer.MAX_VALUE);
        for (int i = 0; i < grid.size(); i++) {
            List<Integer> lists = grid.get(i);
            for (int j = 0; j < lists.size(); j++) {
                int minimum = minimums[0];
                for (int k = 0; k <= j; k++) {
                    if (minimum > minimums[k]) {
                        minimum = minimums[k];
                    }
                }
                int nowMinimum = minimums[j];
                Integer integer = lists.get(j);
                int cal = integer - minimum;
                if (cal > result) {
                    result = cal;
                }
                if (integer < nowMinimum) {
                    minimums[j] = integer;
                }
            }
        }
        return result;
    }

    @Test
    public void testMedianOfUniquenessArray() {
        int[] nums = {16845,5056,24737,49435,67648,14919,8923,99437,99221,36079,20319,57743,81898,29331,66368,60586,44223,51921,6585,44954,85296,32479,20924,19041,46411,83870,99114,20870,61203,20133,90091,57674,17880,59393,31481,65721,43278,4680,69011,76432,52756,32994,40489,31219,14788,85118,85662,79189,981,26224,2878,80995,88779,47094,89020,5300,35710,29120,76568,87917,96958,24574,82818,79740,3554,31427,92220,2518,66717,61754,20345,55841,62480,75069,30485,30026,96665,37722,3919,27141,94546,32287,56240,93686,38856,48282,93778,35875,1491,28444,26631,63057,36908,55306,87766,53603,31214,22126,88919,70209,30895,22025,28953,16361,60384,17032,77042,87868,46993,13786,92621,62462,24024,26686,67335,34871,26121,60802,50683,1259,18713,97262,43448,99509,13406,61370,79837,62396,51117,27788};
        int i = medianOfUniquenessArray(nums);
        System.out.println(i);
    }

    public int medianOfUniquenessArray(int[] nums) {
        int result = 0;
        long medium = (((long) nums.length * (nums.length + 1)) / 2 + 1) / 2;
        int l = 1;
        int r = nums.length;
        while (r >= l) {
            int mid = (l + r) / 2;
            if (check(nums, mid, medium)) {
                result = mid;
                r = mid - 1;
            } else {
                l = mid + 1;
            }
        }
        return result;
    }

    public boolean check(int[] nums, int mid, long medium) {
        long tot = 0;
        Map<Integer, Integer> cnt = new HashMap<>();
        for (int i = 0,j = 0; i < nums.length; i ++) {
            cnt.put(nums[i], cnt.getOrDefault(nums[i], 0) + 1);
            while (cnt.size() > mid) {
                cnt.put(nums[j], cnt.get(nums[j]) - 1);
                if (cnt.get(nums[j]) == 0) {
                    cnt.remove(nums[j]);
                }
                j++;
            }
            tot += i - j + 1;
        }
        return tot >= medium;
    }

    static int INF = 0x3f3f3f3f;

    @Test
    public void testMinimumSubstringsInPartition() {
        String s = "aba";
        int i = minimumSubstringsInPartition(s);
        System.out.println(i);
    }

    public int minimumSubstringsInPartition(String s) {
        int n = s.length();
        int[] d = new int[n + 1];
        Arrays.fill(d, INF);
        d[0] = 0;
        for(int i = 1; i <= n; i++) {
            Map<Character, Integer> occCnt = new HashMap<>();
            int maxCnt = 0;
            for(int j = i; j > 0; j--) {
                occCnt.put(s.charAt(j - 1), occCnt.getOrDefault(s.charAt(j - 1), 0) + 1);
                maxCnt = Math.max(maxCnt, occCnt.get(s.charAt(j - 1)));
                if(maxCnt * occCnt.size() == (i - j + 1) && d[j - 1] != INF) {
                    d[i] = Math.min(d[i], d[j - 1] + 1);
                }
            }
        }
        return d[n];
    }
}
