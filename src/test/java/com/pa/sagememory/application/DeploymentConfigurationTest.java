package com.pa.sagememory.application;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

/** Validate real deployment configuration without touching /apps or starting model calls. */
class DeploymentConfigurationTest {
    @TempDir Path directory;

    @Test
    void logbackLoadsAndWritesUtf8WithSizeAndTimeBasedRetention() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new ClassPathResource("logback-spring.xml").getInputStream());
        var properties = document.getElementsByTagName("property");
        boolean fixedPath = false;
        for (int i=0; i<properties.getLength(); i++) {
            var element = (org.w3c.dom.Element) properties.item(i);
            if (element.getAttribute("name").equals("LOG_HOME")) {
                assertThat(element.getAttribute("value")).isEqualTo("/apps/logs/sage-memory");
                // Only the in-memory test copy points at a writable temporary directory.
                element.setAttribute("value", directory.resolve("logs").toString().replace('\\', '/'));
                fixedPath = true;
            }
        }
        assertThat(fixedPath).isTrue();
        assertThat(document.getElementsByTagName("maxFileSize").item(0).getTextContent()).isEqualTo("100MB");
        assertThat(document.getElementsByTagName("totalSizeCap").item(0).getTextContent()).isEqualTo("1GB");
        assertThat(document.getElementsByTagName("maxHistory").item(0).getTextContent()).isEqualTo("10");
        assertThat(document.getElementsByTagName("fileNamePattern").item(0).getTextContent()).contains("%d{yyyy-MM-dd}", "%i", ".gz");
        var serialized = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(serialized));
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            var configurator = new JoranConfigurator(); configurator.setContext(context);
            configurator.doConfigure(new ByteArrayInputStream(serialized.toByteArray()));
            context.start();
            context.getLogger("deployment-test").info("部署日志 UTF-8 检查");
            assertThat(context.getStatusManager().getCopyOfStatusList()).noneMatch(status -> status.getLevel() == Status.ERROR);
            assertThat(Files.readString(directory.resolve("logs/console.log"), StandardCharsets.UTF_8)).contains("部署日志 UTF-8 检查");
        } finally { context.stop(); }
    }

    @Test
    void yamlNoLongerConfiguresASecondProjectLocalLogFile() {
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("logging.file.name")).isNull();
        assertThat(properties.getProperty("logging.file.path")).isNull();
        assertThat(properties.getProperty("logging.logback.rollingpolicy.max-history")).isNull();
        assertThat(properties.getProperty("logging.level.com.pa.sagememory")).isNotBlank();
    }

    @Test
    void dockerAndComposeUseTheRealArtifactAndPersistTheCompleteMemoryRoot() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        String version = null;
        for (var child = pom.getDocumentElement().getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeName().equals("version")) version = child.getTextContent();
        }
        assertThat(version).isNotNull();
        assertThat(Files.readString(Path.of("Dockerfile"))).contains("ARG JAR_FILE=target/sage-memory-" + version + ".jar",
                "USER 10001:10001", "HEALTHCHECK", "http://127.0.0.1:8080/health", "/app/.sage-memory/workspace")
                .doesNotContain("APT_MIRROR", "wiki-ui", "0.0.1-SNAPSHOT");
        assertThat(Files.readString(Path.of(".dockerignore"))).contains("**", "!target/sage-memory-" + version + ".jar");
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource("compose.yaml"));
        var compose = yaml.getObject();
        assertThat(compose).isNotNull();
        assertThat(compose.getProperty("services.sage-memory.volumes[0]")).isEqualTo("memory-data:/app/.sage-memory");
        assertThat(compose.getProperty("services.sage-memory.volumes[1]")).isEqualTo("/apps/logs/sage-memory:/apps/logs/sage-memory");
    }
}
