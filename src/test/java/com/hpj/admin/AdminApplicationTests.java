package com.hpj.admin;

import com.alibaba.fastjson.JSONObject;
import com.hpj.admin.common.config.MongoDBDataSourceConfig;
import com.hpj.admin.entity.User;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest
class AdminApplicationTests {

    @Autowired
    private MongoDBDataSourceConfig mongoDBDataSourceConfig;

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
}
