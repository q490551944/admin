package com.hpj.admin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashSet;
import java.util.Set;

@RunWith(SpringRunner.class)
@SpringBootTest
public class AdminApplicationTests {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    public void test() {
        Set<Integer> set = new HashSet<>();
        mongoTemplate.createCollection("a", CollectionOptions.emitChangedRevisions());
    }
}
