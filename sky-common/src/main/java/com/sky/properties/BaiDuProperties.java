package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sky.shop")
public class BaiDuProperties {

    private String address;

    private String ak;

    private String APIURL;
}
