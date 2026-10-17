package com.nettrack.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest
class NetTrackBackendApplicationTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void configureTestProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test-context.db"));
		registry.add("nettrack.monitoring.enabled", () -> "false");
	}

	@Test
	void contextLoads() {
	}

}
