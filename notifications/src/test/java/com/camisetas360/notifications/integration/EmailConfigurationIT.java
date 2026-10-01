package com.camisetas360.notifications.integration;

import com.camisetas360.notifications.service.EmailService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailConfigurationIT {

    // IT-NOT-001: load the application's actual placeholder expression from its YAML.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendEmail_shouldResolveConfiguredFrom_whenExplicitOrFallbackIsUsed(boolean explicit) {
        var sender = mock(JavaMailSender.class);
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        new YamlPropertySourceLoader()
                                .load("application", new ClassPathResource("application.yaml"))
                                .forEach(sources::addLast);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .withPropertyValues("MAIL_USERNAME=fallback@example.test", "MAIL_PASSWORD=test-only")
                .withBean(JavaMailSender.class, () -> sender)
                .withUserConfiguration(EmailService.class);
        if (explicit) {
            runner = runner.withPropertyValues("MAIL_FROM=explicit@example.test");
        }

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(EmailService.class).sendEmail("buyer@example.test", "Order", "Created");
            var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getFrom())
                    .isEqualTo(explicit ? "explicit@example.test" : "fallback@example.test");
            assertThat(message.getValue().getTo()).containsExactly("buyer@example.test");
            verifyNoMoreInteractions(sender);
        });
    }
}

