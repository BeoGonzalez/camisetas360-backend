package com.camisetas360.testing.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class ServiceProcess implements AutoCloseable {
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private final Process process;
    private final Path log;
    private final String module;
    private int port;

    ServiceProcess(Path root, Path logs, String module, Map<String, String> properties) throws Exception {
        this.module = module;
        Files.createDirectories(logs);
        log = logs.resolve(module + ".log");
        Path jar;
        try (var jars = Files.list(root.resolve(module).resolve("target"))) {
            var candidates = jars.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
            assertThat(candidates).as("Build one executable JAR for " + module + " before running E2E").hasSize(1);
            jar = candidates.getFirst();
        }
        var javaBinary = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(javaBinary.toString(), "-Xmx256m",
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-jar", jar.toAbsolutePath().toString(), "--server.address=127.0.0.1",
                "--server.port=0", "--spring.output.ansi.enabled=never"));
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        var builder = new ProcessBuilder(command).directory(root.resolve(module).toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key -> {
            var upper = key.toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("SPRING_") || upper.startsWith("MAIL_")
                    || upper.startsWith("RABBITMQ_") || upper.startsWith("ENTRA_");
        });
        process = builder.start();
    }

    void awaitStarted() {
        await().alias(module + " startup; see " + log).atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertThat(process.isAlive()).as(module + " process exited; see " + log).isTrue();
            var text = Files.readString(log);
            var matcher = PORT.matcher(text);
            assertThat(matcher.find()).as("HTTP port in " + log).isTrue();
            port = Integer.parseInt(matcher.group(1));
            assertThat(text).contains("Started ");
        });
    }

    String baseUrl() {
        assertThat(port).isPositive();
        return "http://127.0.0.1:" + port;
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("Process " + module + " terminates").isTrue();
        }
    }
}
