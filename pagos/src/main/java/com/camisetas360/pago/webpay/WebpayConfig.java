package com.camisetas360.pago.webpay;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebpayProperties.class)
public class WebpayConfig {

    @Bean
    WebpayPlusPort webpayPlusPort(WebpayProperties properties, ObjectMapper objectMapper) {
        var http = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.timeout());
        RestClient rest = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Tbk-Api-Key-Id", properties.codigoComercio())
                .defaultHeader("Tbk-Api-Key-Secret", properties.apiKey())
                .build();
        return new TransbankWebpayPlusAdapter(rest, objectMapper);
    }
}
