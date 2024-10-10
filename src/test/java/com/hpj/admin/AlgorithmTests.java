package com.hpj.admin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    public void testDistinctNames() {
        String[] ideas = {"coffee","donuts","time","toffee"};
        long distinctNames = distinctNames(ideas);
        System.out.println(distinctNames);
    }

    public long distinctNames(String[] ideas) {
        Map<String, Set<String>> map = new HashMap<>();
        for(String s : ideas) {
            String first = s.substring(0, 1);
            String rest = s.substring(1);
            Set<String> set = map.getOrDefault(first, new HashSet<>());
            set.add(rest);
            map.put(first, set);
        }
        long result = 0;
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            for (Map.Entry<String, Set<String>> bEntry : map.entrySet()) {
                if (Objects.equals(entry.getKey(), bEntry.getKey())) {
                    continue;
                }
                result += ((long) (entry.getValue().size() - getIntersectSize(entry.getValue(), bEntry.getValue())) * (bEntry.getValue().size() - getIntersectSize(entry.getValue(), bEntry.getValue())));
            }
        }
        return result;
    }

    public int getIntersectSize(Set<String> a, Set<String> b) {
        int result = 0;
        for (String s : a) {
            if (b.contains(s)) {
                result++;
            }
        }
        return result;
    }

    @Test
    public void testMaximumSubsequenceCount() {
        String text = "aaaaaaaaaaaaa";
        String pattern = "aa";
        System.out.println(maximumSubsequenceCount(text, pattern));
    }

    public long maximumSubsequenceCount(String text, String pattern) {
        int cnt1 = 0;
        int cnt2 = 0;
        long result = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == pattern.charAt(1)) {
                result += cnt1;
                cnt2++;
            }
            if (text.charAt(i) == pattern.charAt(0)) {
                cnt1 ++;
            }
        }
        return result + Math.max(cnt1, cnt2);
    }

    @Test
    public void test() {
        List<AtomicInteger> list = new ArrayList<>();
        list.add(new AtomicInteger());
        list.get(0).getAndAdd(-100);
        list.get(0).set(0);
        System.out.println(list);
        String s = "abc";
    }

    @Test
    public void testTaskCharacters() {
        String s = "aabaaaacaabc";
        int k = 2;
        int i = takeCharacters(s, k);
        System.out.println(i);
    }

    public int takeCharacters(String s, int k) {
        int[] cnt = new int[3];
        int len = s.length();
        int ans = len;
        for(int i = 0; i < len; i++) {
            cnt[s.charAt(i) - 'a']++;
        }
        if(cnt[0] >= k && cnt[1] >= k && cnt[2] >= k) {
            ans = Math.min(ans, len);
        } else {
            return -1;
        }
        int left = 0;
        for(int right = 0; right < len; right++) {
            cnt[s.charAt(right) - 'a']--;
            while(left < right && (cnt[0] < k || cnt[1] < k || cnt[2] < k)) {
                cnt[s.charAt(left) - 'a']++;
                left++;
            }
            if(cnt[0] >= k && cnt[1] >= k && cnt[2] >= k) {
                ans = Math.min(ans, len - (right - left + 1));
            }
        }
        return ans;
    }

    @Test
    public void testArray() {
//        Arrays.fill(nums, 1);
//        System.out.println(Arrays.toString(nums));
        Map<String, String> map = new HashMap<>();
        String next = map.keySet().iterator().next();
    }

    @Test
    public void testMinimumDifference() {
        int[] nums = {1, 2, 4, 5};
        int i = minimumDifference(nums, 3);
        System.out.println(i);
    }

    public int minimumDifference(int[] nums, int k) {
        int n = nums.length;
        int[] bitsMaxPos = new int[31];
        Arrays.fill(bitsMaxPos, -1);
        List<int[]> posToBit = new ArrayList<int[]>();
        int res = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= 30; j++) {
                if ((nums[i] >> j & 1) != 0) {
                    bitsMaxPos[j] = i;
                }
            }
            posToBit.clear();
            for (int j = 0; j <= 30; j++) {
                if (bitsMaxPos[j] != -1) {
                    posToBit.add(new int[]{bitsMaxPos[j], j});
                }
            }
            Collections.sort(posToBit, (a, b) -> a[0] != b[0] ? b[0] - a[0] : b[1] - a[1]);
            int val = 0;
            for (int j = 0, p = 0; j < posToBit.size(); ) {
                while (j < posToBit.size() && posToBit.get(j)[0] == posToBit.get(p)[0]) {
                    val |= 1 << posToBit.get(j)[1];
                    j++;
                }
                res = Math.min(res, Math.abs(val - k));
                p = j;
            }
        }
        return res;
    }
}

