package com.hpj.admin;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hpj.admin.common.config.MongoDBDataSourceConfig;
import com.hpj.admin.entity.User;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xwpf.usermodel.XWPFChart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.IndicesClient;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

@RunWith(SpringRunner.class)
@SpringBootTest
class AdminApplicationTests {

    @Autowired
    private MongoDBDataSourceConfig mongoDBDataSourceConfig;
    @Autowired
    private BulkProcessor bulkProcessor;
    @Autowired
    private RestHighLevelClient client;

    @Autowired
    ResourcePatternResolver resourceLoader;

    @Test
    void contextLoads() {
    }

    @Test
    public void testMongo() throws Exception {
        User user = new User();
        user.setUsername("hpj");
        user.setPassword("123");
        MongoTemplate tests = mongoDBDataSourceConfig.getMongoTemplate("hpj");
        Query query = new Query();
        List<JSONObject> jsonObjects = tests.find(query, JSONObject.class, "user");
        System.out.println(jsonObjects);
        for (JSONObject jsonObject : jsonObjects) {
            User object = JSONObject.toJavaObject(jsonObject, User.class);
            System.out.println(object);
        }
        tests.dropCollection("maybe");
    }

    /**
     * 创建索引
     */
    @Test
    public void createIndex() throws IOException {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest("json_test");
        createIndexRequest.settings(Settings.builder().put("number_of_shards", "1").put("number_of_replicas", "0"));
        createIndexRequest.mapping(" {\n" +
                " \t\"properties\": {\n" +
                "            \"name\":{\n" +
                "             \"type\":\"text\"\n" +
                "           },\n" +
                "           \"age\": {\n" +
                "              \"type\": \"number\"\n" +
                "           },\n" +
                "            \"price\":{\n" +
                "             \"type\":\"keyword\"\n" +
                "           }" +
                " \t}\n" +
                "}", XContentType.JSON);
        IndicesClient indices = client.indices();
        CreateIndexResponse indexResponse = indices.create(createIndexRequest, RequestOptions.DEFAULT);
        System.out.println(indexResponse);
    }

    /**
     * es数据测试
     *
     * @throws IOException IO异常
     */
    @Test
    public void testElasticsearch() throws IOException {
        for (int i = 0; i < 100; i++) {
            // 构建json格式数据
            XContentBuilder builder = XContentFactory.jsonBuilder()
                    .startObject()
                    .field("name", "name" + i)
                    .field("age", new Random().nextInt(50))
                    .field("sex", Math.round(Math.random()) == 1 ? "男" : "女")
                    .endObject();
            bulkProcessor.add(new IndexRequest("json_test", "_doc", IdWorker.getIdStr()).source(builder));
        }
        bulkProcessor.flush();

        SearchRequest searchRequest = new SearchRequest();
        // 索引名称
        searchRequest.indices("json_test");

        // 构建查询条件
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.query(QueryBuilders.matchAllQuery());

        // 将查询条件加入请求
        searchRequest.source(sourceBuilder);
        SearchResponse search = client.search(searchRequest, RequestOptions.DEFAULT);
        SearchHits hits = search.getHits();
        System.out.println("查询文档总数:" + hits.getTotalHits());
        hits.forEach(e -> System.out.println("原生文档信息：" + e.getSourceAsString()));
    }

    @Test
    public void exportWord() throws IOException, InvalidFormatException {
        // 创建word文档对象
        XWPFDocument xwpfDocument = new XWPFDocument();
        // 创建chart图表对象
        XWPFChart chart = xwpfDocument.createChart(15 * Units.EMU_PER_CENTIMETER, 10 * Units.EMU_PER_CENTIMETER);
        // 设置chart标题
        chart.setTitleText("测试图表");
        // 设置图例是否覆盖标题
        chart.setTitleOverlay(false);
        // 设置图例
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.TOP);
        // X轴设置
        XDDFCategoryAxis categoryAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        categoryAxis.setTitle("日期");
        String[] xAxisData = new String[]{
                "2021-01", "2021-02", "2021-03", "2021-04", "2021-05", "2021-06",
                "2021-07", "2021-08", "2021-09", "2021-10", "2021-11", "2021-12",
        };
        XDDFCategoryDataSource xAxisSource = XDDFDataSourcesFactory.fromArray(xAxisData); // 设置X轴数据

        // 6、Y轴(值轴)相关设置
        XDDFValueAxis yAxis = chart.createValueAxis(AxisPosition.LEFT); // 创建Y轴,指定位置
        yAxis.setTitle("粉丝数（个）"); // Y轴标题
        Integer[] yAxisData = new Integer[]{
                10, 35, 21, 46, 79, 88,
                39, 102, 71, 28, 99, 57
        };
        XDDFNumericalDataSource<Integer> yAxisSource = XDDFDataSourcesFactory.fromArray(yAxisData); // 设置Y轴数据

        // 7、创建折线图对象
        XDDFLineChartData lineChart = (XDDFLineChartData) chart.createData(ChartTypes.LINE, categoryAxis, yAxis);

        // 8、加载折线图数据集
        XDDFLineChartData.Series lineSeries = (XDDFLineChartData.Series) lineChart.addSeries(xAxisSource, yAxisSource);
        lineSeries.setTitle("粉丝数", null); // 图例标题
        lineSeries.setSmooth(true); // 线条样式:true平滑曲线,false折线
        lineSeries.setMarkerSize((short) 6); // 标记点大小
        lineSeries.setMarkerStyle(MarkerStyle.CIRCLE); // 标记点样式

        // 9、绘制折线图
        chart.plot(lineChart);

        // 10、输出到word文档
        FileOutputStream fos = new FileOutputStream("G:\\测试数据\\lineChart.docx");
        xwpfDocument.write(fos); // 导出word

        // 11、关闭流
        fos.close();
        xwpfDocument.close();
    }

    @Test
    public void testClassPathResource() throws IOException {
//        Resource[] resources = resourceLoader.getResources("classpath*:db/migration/*");
//        for (Resource resource : resources) {
//            System.out.println(resource.getURI().getPath());
//            if (resource.isReadable()) {
//                System.out.println(IOUtils.toString(resource.getInputStream(), StandardCharsets.UTF_8));
//            }
//        }
        List<URL> locationUrls = new ArrayList<>();
        Enumeration<URL> urls;
        urls = resourceLoader.getClassLoader().getResources("db/migration");
        while (urls.hasMoreElements()) {
            locationUrls.add(urls.nextElement());
        }
        for (URL locationUrl : locationUrls) {
            System.out.println(locationUrl.getProtocol());
        }
        System.out.println(locationUrls);
    }
}
