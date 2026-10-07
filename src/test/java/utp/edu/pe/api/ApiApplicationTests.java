package utp.edu.pe.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class ApiApplicationTests {
	private static final String TEST_DATABASE_URL = System.getenv("TEST_DATABASE_URL");
	private static final PostgreSQLContainer POSTGRES = startContainerWhenNoTestDatabaseIsConfigured();

	private static PostgreSQLContainer startContainerWhenNoTestDatabaseIsConfigured() {
		if (TEST_DATABASE_URL != null && !TEST_DATABASE_URL.isBlank()) return null;
		PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine")
				.withDatabaseName("parkflow_test")
				.withUsername("parkflow_migrator")
				.withPassword("parkflow_migrator_test")
				.withInitScript("parkflow-test-init.sql");
		container.start();
		return container;
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		if (TEST_DATABASE_URL != null && !TEST_DATABASE_URL.isBlank()) {
			registry.add("spring.datasource.url", () -> TEST_DATABASE_URL);
			registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
			registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
			registry.add("spring.flyway.user", () -> System.getenv("TEST_DATABASE_MIGRATION_USERNAME"));
			registry.add("spring.flyway.password", () -> System.getenv("TEST_DATABASE_MIGRATION_PASSWORD"));
		} else {
			registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
			registry.add("spring.datasource.username", () -> "parkflow_app");
			registry.add("spring.datasource.password", () -> "parkflow_app_test");
			registry.add("spring.flyway.user", POSTGRES::getUsername);
			registry.add("spring.flyway.password", POSTGRES::getPassword);
		}
	}

	@AfterAll
	static void stopPostgresContainer() {
		if (POSTGRES != null) POSTGRES.stop();
	}

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired Flyway flyway;

	@Test
	void flywayCreatesInitialCatalogAndConfigurationSchema() {
		int migrationCount = flyway.info().applied().length;
		Integer roleCount = jdbc.queryForObject("SELECT count(*) FROM role", Integer.class);
		Integer vehicleTypeCount = jdbc.queryForObject("SELECT count(*) FROM vehicle_type", Integer.class);
		Integer auditTableCount = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='configuration_audit'", Integer.class);

		org.junit.jupiter.api.Assertions.assertEquals(6, migrationCount);
		org.junit.jupiter.api.Assertions.assertEquals(2, roleCount);
		org.junit.jupiter.api.Assertions.assertEquals(3, vehicleTypeCount);
		org.junit.jupiter.api.Assertions.assertEquals(1, auditTableCount);
	}

	@Test
	void adminEndpointsRejectUnauthenticatedRequests() throws Exception {
		mockMvc.perform(get("/api/admin/configuration/users")).andExpect(status().isForbidden());
	}

	@Test
	void setupStatusIsPublicBeforeFirstAdminExists() throws Exception {
		mockMvc.perform(get("/api/auth/setup-status")).andExpect(status().isOk());
	}

	@Test
	void bootstrapRejectsRequestsWithoutCsrfToken() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/bootstrap")
					.contentType("application/json")
					.content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void runtimeRoleCannotReadFlywayHistory() {
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
				() -> jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
	}
}
