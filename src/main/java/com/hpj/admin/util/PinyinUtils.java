package com.hpj.admin.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

public class PinyinUtils {

    /**
     * 汉语转拼音（驼峰命名），非中文保留原字符
     *
     * @param s 待转汉语
     * @return 驼峰拼音
     * @throws BadHanyuPinyinOutputFormatCombination 非法的拼音格式化组合
     */
    public static String hanYuToHumpPinYin(String s) throws BadHanyuPinyinOutputFormatCombination {
        char[] chars = s.toCharArray();
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        // 小写
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        // 不包含音调
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            //判断是否为汉字字符
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                String[] multiPinYinArray = PinyinHelper.toHanyuPinyinStringArray(c, format);
                String word = multiPinYinArray[0];
                if (i > 0) {
                    result.append(word.substring(0, 1).toUpperCase())
                            .append(word.substring(1));
                } else {
                    result.append(word);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
