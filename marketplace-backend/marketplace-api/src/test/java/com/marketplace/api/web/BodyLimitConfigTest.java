package com.marketplace.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Properties;

import static com.marketplace.api.web.RequestBodyLimitFilter.DEFAULT_MAX_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request-size settings RequestBodyLimitFilter depends on live in
 * application.yml, which no Spring test ever loads: test/resources has its
 * own application.yml that replaces it on the classpath. So the production
 * values are read from disk here, and the test copy is held to them, or a
 * merge that drops a line would pass every other test and ship.
 */
class BodyLimitConfigTest {

    private static final List<String> KEYS = List.of(
            "spring.mvc.formcontent.filter.enabled",
            "server.tomcat.max-http-form-post-size",
            "spring.servlet.multipart.max-file-size",
            "spring.servlet.multipart.max-request-size");

    private static final Properties MAIN = load("src/main/resources/application.yml");
    private static final Properties TEST = load("src/test/resources/application.yml");

    @Test
    void productionKeepsFormContentFilterOff() {
        // It parsed PUT/PATCH/DELETE form bodies ahead of Spring Security on
        // every path. Turning it back on reopens that pre-authentication read.
        assertThat(MAIN.getProperty("spring.mvc.formcontent.filter.enabled")).isEqualTo("false");
    }

    @Test
    void formPostLimitMatchesTheFilterCap() {
        // The filter leaves multipart to Tomcat, whose in-memory form fields
        // this bounds. Anything larger hands multipart more heap than JSON gets.
        DataSize formPost = DataSize.parse(MAIN.getProperty("server.tomcat.max-http-form-post-size"));
        assertThat(formPost.toBytes()).isEqualTo(DEFAULT_MAX_BYTES);
    }

    @Test
    void multipartLimitsAreSet() {
        // Unset, Boot defaults to 1 MB per file and 10 MB per request; the
        // exemption in RequestBodyLimitFilter assumes these values instead.
        assertThat(MAIN.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("5MB");
        assertThat(MAIN.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("6MB");
    }

    @Test
    void testConfigRestatesProductionLimits() {
        for (String key : KEYS) {
            assertThat(TEST.getProperty(key)).as(key).isEqualTo(MAIN.getProperty(key));
        }
    }

    private static Properties load(String path) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(path));
        return yaml.getObject();
    }
}
