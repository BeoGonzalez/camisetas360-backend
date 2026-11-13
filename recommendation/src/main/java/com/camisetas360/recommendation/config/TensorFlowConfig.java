package com.camisetas360.recommendation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita el reentrenamiento programado del modelo TensorFlow (ModeloTensorFlow). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.tensorflow.enabled", havingValue = "true", matchIfMissing = true)
public class TensorFlowConfig {
}
