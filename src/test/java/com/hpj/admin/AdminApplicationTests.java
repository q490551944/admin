package com.hpj.admin;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hpj.admin.common.config.MongoDBDataSourceConfig;
import com.hpj.admin.entity.User;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
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
        System.out.println("查询文档总数:" + hits.totalHits);
        hits.forEach(e -> System.out.println("原生文档信息：" + e.getSourceAsString()));
    }
}
