package com.hpj.admin.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "xh")
public class XhConfig {

    private List<String> test;

    public List<String> getTest() {
        return test;
    }

    public void setTest(List<String> test) {
        this.test = test;
    }

    public XhConfig(List<String> test) {
        this.test = test;
    }
}
