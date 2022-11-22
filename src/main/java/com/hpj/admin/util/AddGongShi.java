package com.hpj.admin.util;

import com.alibaba.fastjson.JSONObject;
import org.springframework.http.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class AddGongShi {

    private static final List<String> holidayList = new ArrayList<>();

    static {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<JSONObject> entity = restTemplate.getForEntity("http://timor.tech/api/holiday/year", JSONObject.class);
        JSONObject jsonObject = entity.getBody();
        if (jsonObject != null) {
            JSONObject holiday = jsonObject.getJSONObject("holiday");
            holiday.forEach((k, v) -> {
                if (v instanceof Map) {
                    JSONObject json = new JSONObject((Map<String, Object>) v);
                    String date = json.getString("date");
                    holidayList.add(date);
                }
            });
        }
    }

    public static void main(String[] args) throws InterruptedException, ParseException {
//        String firstUrl = "http://113.247.222.69:8899/api/cube/new/card/layoutBase?type=1&modeId=165&formId=-401&_key=bqxb78&guid=card&uuid=9204CC42802CB0378629FF76045186E1&modedatastatus=0&__random__=1665663344151";
//        String url = "http://113.247.222.69:8899/api/cube/new/card/doSubmit";
//        String jsonStr = "{\"field15418\":\"464\",\"field15429\":\"95\",\"field15419\":\"${date}\",\"detail_1\":[{\"field15428\":\"\",\"field15749\":\"\",\"dtl_index\":\"\",\"field15424\":\"1107\",\"field15425\":\"ISIMCloud云仿真平台\",\"field15426\":\"2\",\"dtl_id\":\"\",\"field15427\":\"8.00\",\"field15647\":\"\",\"checkbox\":\"\"}],\"deldtlid1\":\"\",\"field15420\":\"\",\"field15431\":\"\",\"submitdtlid1\":\"0\",\"field15421\":\"\",\"field15432\":\"\",\"field15422\":\"\",\"field15423\":\"8.00\",\"field15430\":\"0\"}";
        List<String> list = getBetweenDate("2022-09-10", "2022-10-12");
        System.out.println(list);
//        for (String s : list) {
//            String replace = jsonStr.replace("${date}", s);
//            RestTemplate restTemplate = new RestTemplate();
//            restTemplate.getMessageConverters().add(  new MappingJackson2HttpMessageConverter());
//            HttpHeaders httpHeaders = new HttpHeader();
//            HttpMethod httpMethod = HttpMethod.POST;
//            httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
//            httpHeaders.set("Cookie", "ecology_JSessionid=aaaItWuqc022_hgUMgrpy; JSESSIONID=aaaItWuqc022_hgUMgrpy; languageidweaver=7; loginidweaver=H01076; loginuuids=464; __randcode__=9322e178-cebd-4221-8b92-206ab57502d0");
//
//            //预处理请求
//            HttpEntity<Object> httpEntity = new HttpEntity<>(httpHeaders);
//            ResponseEntity<JSONObject> response = restTemplate.exchange(firstUrl, HttpMethod.GET, httpEntity, JSONObject.class);
//            Thread.sleep(100);
//            if (response.getStatusCode() == HttpStatus.OK) {
//                String token = response.getBody().getString("token");
//                System.out.println(token);
//                MultiValueMap<String, String> param = new LinkedMultiValueMap<>();
//                param.add("billid", "");
//                param.add("type", "1");
//                param.add("modeId", "165");
//                param.add("formId", "-401");
//                param.add("_key", "bqxb78");
//                param.add("guid", "card");
//                param.add("token", token);
//                param.add("layoutid", "770");
//                param.add("isFormMode", "1");
//                param.add("iscreate", "1");
//                param.add("src", "submit");
//                param.add("currentLayoutId", "770");
//                param.add("pageexpandid", "3694");
//                param.add("JSONStr", replace);
//                param.add("btntype", "");
//                param.add("issystemflag", "1");
//                param.add("oldmodedatastatus", "0");
//                HttpEntity<MultiValueMap<String, String>> multiValueMapHttpEntity = new HttpEntity<>(param, httpHeaders);
//                ResponseEntity<JSONObject> exchange = restTemplate.exchange(url, httpMethod, multiValueMapHttpEntity, JSONObject.class);
//                if (exchange.getStatusCode() == HttpStatus.OK) {
//                    System.out.println(exchange.getBody());
//                }
//            }
//
//            // 新增工时请求
//
//            Thread.sleep(200);
//        }

    }

    private static List<String> getBetweenDate(String startTime, String endTime) throws ParseException {
        List<String> result = new ArrayList<>();

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

        Date startDate = simpleDateFormat.parse(startTime);
        Date endDate = simpleDateFormat.parse(endTime);
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(startDate);
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(endDate);

        while (endCalendar.after(startCalendar)) {
            int i = startCalendar.get(Calendar.DAY_OF_WEEK);
            if (i != 7 && i != 1) {
                String format = simpleDateFormat.format(startCalendar.getTime());
                if (!holidayList.contains(format)) {
                    result.add(format);
                }
            }
            startCalendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return result;
    }
}
