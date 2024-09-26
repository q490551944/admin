package com.hpj.admin;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableList;
import com.hankcs.hanlp.HanLP;
import com.hpj.admin.entity.Shp;
import com.hpj.admin.entity.Translate;
import com.hpj.admin.util.ExcelExportStatisticStyler;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.opengis.feature.simple.SimpleFeature;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@RunWith(JUnit4.class)
public class ShpTests {

    List<String> names = ImmutableList.of("monument", "museum", "school", "sports_centre", "police", "library", "town_hall",
            "prison", "courthouse", "stadium", "college");

    @Test
    public void test() throws IOException, URISyntaxException {
        File shpFile = new File("C:\\workerspace\\test\\shp\\gis_osm_pois_free_1.shp");
        Map<String, Object> shpParams = new HashMap<>();
//        FileDataStore dataStore = FileDataStoreFinder.getDataStore(shpFile);

        shpParams.put("url", shpFile.toURI().toURL());
        shpParams.put("charset", "UTF-8");
        DataStore dataStore = DataStoreFinder.getDataStore(shpParams);
        String typeName = dataStore.getTypeNames()[0];
        SimpleFeatureSource featureSource = dataStore.getFeatureSource(typeName);
        SimpleFeatureCollection features = featureSource.getFeatures();
        SimpleFeatureIterator iterator = features.features();
        Set<String> set = new HashSet<>();
        List<Shp> list = new ArrayList<>();
        Map<String, AtomicInteger> map = new HashMap<>();
        while (iterator.hasNext()) {
            SimpleFeature next = iterator.next();
            Shp shp = new Shp();
            String name = next.getAttribute("name").toString();
            name = convert(name);
            shp.setName(name);
            String fclass = next.getAttribute("fclass").toString();
            if (names.contains(fclass) && StringUtils.isNotBlank(name)) {
                set.add(fclass);
                shp.setFclass(fclass);
                Coordinate[] theGeoms = ((Point) next.getAttribute("the_geom")).getCoordinates();
                List<String> collect = Arrays.stream(theGeoms).map(c -> c.x + "," + c.y).collect(Collectors.toList());
                shp.setPositions(collect);
                list.add(shp);
                AtomicInteger integer = map.getOrDefault(fclass, new AtomicInteger());
                integer.getAndIncrement();
                map.put(fclass, integer);
            }
//            if (list.size() > 100) {
//                break;
//            }
        }
        System.out.println(list.size());
        System.out.println(JSONObject.toJSONString(list));
        System.out.println(JSONObject.toJSONString(map));
//        System.out.println(list.size());
//        translate(set);
    }

    private String convert(String source) {
        return HanLP.convertToSimplifiedChinese(source);
    }

    private void translate(Set<String> set) throws URISyntaxException, IOException {
        List<Translate> translates = new ArrayList<>();
        for (String s : set) {
            Translate translate = translate(s);
            translates.add(translate);
        }

        ExportParams params = new ExportParams();
        params.setStyle(ExcelExportStatisticStyler.class);
        Workbook workbook = ExcelExportUtil.exportExcel(params, Translate.class, translates);
        OutputStream os = new FileOutputStream("C:\\workerspace\\test\\translate.xlsx");
        workbook.write(os);
    }


    /**
     * 翻译
     * @param en  英文原文
     * @return    中文结果
     * @throws URISyntaxException URI语法错误
     * @throws IOException        IO异常
     */
    public Translate translate(String en) throws URISyntaxException, IOException {
        Translate translate = new Translate();
        String appid = "20240919002154092";
        Integer salt = 123456;
        translate.setEn(en);
        String url = "https://fanyi-api.baidu.com/api/trans/vip/translate";
        CloseableHttpClient client = HttpClients.createDefault();
        URIBuilder uriBuilder = new URIBuilder(url);
        uriBuilder.setParameter("from", "en");
        uriBuilder.setParameter("to", "zh");
        uriBuilder.setParameter("q", en);
        uriBuilder.setParameter("appid", appid);
        uriBuilder.setParameter("salt", salt + "");
        String s = DigestUtils.md5DigestAsHex((appid + en + salt + "jpbdX2bg6WhLqGNyNIsd").getBytes(StandardCharsets.UTF_8));
        uriBuilder.setParameter("sign", s);
        HttpGet httpGet = new HttpGet(uriBuilder.build());
        CloseableHttpResponse execute = client.execute(httpGet);
        HttpEntity entity = execute.getEntity();
        String string = EntityUtils.toString(entity);
        String zh = JSONObject.parseObject(string).getJSONArray("trans_result").getJSONObject(0).getString("dst");
        translate.setZh(zh);
        return translate;
    }

    @Test
    public void testMD5() {
        System.out.println(DigestUtils.md5DigestAsHex(("20240919002154092recycling clothes12345jpbdX2bg6WhLqGNyNIsd").getBytes(StandardCharsets.UTF_8)));
    }
}
