package utp.edu.pe.api.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.edu.pe.api.config.ConfigurationStore;

@Repository
public class ConfigurationRepository implements ConfigurationStore {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public ConfigurationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) { this.jdbc = jdbc; this.objectMapper = objectMapper; }

	public Map<String, Object> findConfiguration() {
		Map<String, Object> site = jdbc.queryForMap("SELECT id, name, address, time_zone AS \"timeZone\" FROM site WHERE code = 'MAIN' AND deleted_at IS NULL");
		List<Map<String, Object>> vehicleTypes = jdbc.queryForList("""
				SELECT vt.id, vt.code, vt.name, c.total_spaces AS capacity,
				       r.hourly_amount AS \"hourlyAmount\", r.grace_minutes AS \"graceMinutes\",
				       r.billing_increment_minutes AS \"billingIncrementMinutes\",
				       rp.name AS \"nightPeriodName\", rp.starts_at AS \"nightStartsAt\",
				       rp.ends_at AS \"nightEndsAt\", rp.hourly_amount AS \"nightHourlyAmount\"
				FROM vehicle_type vt
				LEFT JOIN capacity c ON c.vehicle_type_id = vt.id AND c.site_id = ? AND c.deleted_at IS NULL AND c.state = 'ACTIVE'
				LEFT JOIN LATERAL (
				  SELECT * FROM rate x WHERE x.vehicle_type_id = vt.id AND x.site_id = ?
				    AND x.deleted_at IS NULL AND x.state = 'ACTIVE'
				  ORDER BY x.valid_from DESC LIMIT 1
				) r ON true
				LEFT JOIN LATERAL (
				  SELECT * FROM rate_period p WHERE p.rate_id = r.id AND p.deleted_at IS NULL AND p.state = 'ACTIVE'
				  ORDER BY p.created_at DESC LIMIT 1
				) rp ON true
				WHERE vt.deleted_at IS NULL AND vt.state = 'ACTIVE' ORDER BY vt.code
				""", site.get("id"), site.get("id"));
		return Map.of("site", site, "vehicleTypes", vehicleTypes);
	}

	public String findMainSiteId() {
		return jdbc.queryForObject("SELECT id::text FROM site WHERE code = 'MAIN' AND deleted_at IS NULL", String.class);
	}

	public void lockSite() {
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('parkflow-site-config', 0))", Object.class);
	}

	public void lockUserCreation() {
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('parkflow-user-create', 0))", Object.class);
	}

	public String findVehicleTypeId(String code) {
		return jdbc.queryForObject("SELECT id::text FROM vehicle_type WHERE code = ? AND deleted_at IS NULL", String.class, code);
	}

	public void updateSite(String siteId, String name, String address) {
		jdbc.update("UPDATE site SET name = ?, address = ?, updated_at = now() WHERE id = ?::uuid", name, address, siteId);
	}

	public void saveCapacity(String siteId, String typeId, int capacity) {
		jdbc.update("""
				INSERT INTO capacity (site_id, vehicle_type_id, total_spaces) VALUES (?::uuid, ?::uuid, ?)
				ON CONFLICT (site_id, vehicle_type_id) WHERE deleted_at IS NULL
				DO UPDATE SET total_spaces = EXCLUDED.total_spaces, updated_at = now(), state = 'ACTIVE', deleted_at = null
				""", siteId, typeId, capacity);
	}

	public String replaceRate(String siteId, String typeId, BigDecimal hourlyAmount, int graceMinutes, int billingIncrementMinutes) {
		jdbc.update("UPDATE rate SET state = 'INACTIVE', valid_until = GREATEST(now(), valid_from + interval '1 microsecond'), updated_at = now() WHERE site_id = ?::uuid AND vehicle_type_id = ?::uuid AND state = 'ACTIVE' AND deleted_at IS NULL", siteId, typeId);
		return jdbc.queryForObject("INSERT INTO rate (site_id, vehicle_type_id, hourly_amount, grace_minutes, billing_increment_minutes, valid_from) VALUES (?::uuid, ?::uuid, ?, ?, ?, now()) RETURNING id::text",
				String.class, siteId, typeId, hourlyAmount, graceMinutes, billingIncrementMinutes);
	}

	public void saveNightPeriod(String rateId, String name, LocalTime startsAt, LocalTime endsAt, BigDecimal amount) {
		jdbc.update("INSERT INTO rate_period (rate_id, name, starts_at, ends_at, hourly_amount) VALUES (?::uuid, ?, ?, ?, ?)", rateId, name, startsAt, endsAt, amount);
	}

	public void recordConfigurationActor(String siteId, String email) {
		jdbc.update("""
				INSERT INTO platform_setting (site_id, setting_key, setting_value)
				VALUES (?::uuid, 'last_configuration_actor', ?)
				ON CONFLICT (site_id, setting_key) DO UPDATE
				SET setting_value = EXCLUDED.setting_value, updated_at = now(), state = 'ACTIVE', deleted_at = null
				""", siteId, email);
	}

	public void audit(String siteId, String actor, String action, Map<String, ?> details) {
		try {
			String serializedDetails = objectMapper.writeValueAsString(details);
			jdbc.update("INSERT INTO configuration_audit (site_id, actor_email, action, details) VALUES (?::uuid, ?, ?, ?::jsonb)", siteId, actor, action, serializedDetails);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Could not serialize configuration audit details", exception);
		}
	}

	public void auditSiteUpdate(String siteId, String actor, String name, String address) {
		audit(siteId, actor, "SITE_UPDATED", Map.of("name", name, "address", address));
	}

	public List<Map<String, Object>> findUsers() {
		return jdbc.queryForList("""
				SELECT u.id, u.first_name AS \"firstName\", u.last_name AS \"lastName\", u.email,
				       r.code AS role, u.state
				FROM app_user u JOIN user_site us ON us.user_id = u.id AND us.deleted_at IS NULL
				JOIN role r ON r.id = us.role_id AND r.deleted_at IS NULL
				WHERE u.deleted_at IS NULL AND u.state <> 'DELETED'
				ORDER BY CASE WHEN r.code = 'ADMIN' THEN 0 ELSE 1 END, u.first_name, u.last_name
				""");
	}

	public void createUser(String firstName, String lastName, String email, String passwordHash, String roleCode) {
		String userId = jdbc.queryForObject("INSERT INTO app_user (first_name, last_name, email, password_hash) VALUES (?, ?, ?, ?) RETURNING id::text",
				String.class, firstName, lastName, email, passwordHash);
		int affected = jdbc.update("INSERT INTO user_site (user_id, site_id, role_id) SELECT ?::uuid, s.id, r.id FROM site s CROSS JOIN role r WHERE s.code='MAIN' AND r.code=? AND s.state='ACTIVE' AND r.state='ACTIVE'",
				userId, roleCode);
		if (affected != 1) throw new IllegalStateException("Default site or role is missing");
	}

	public int updateUserState(String userId, String state) {
		return jdbc.update("UPDATE app_user SET state=?, updated_at=now() WHERE id=?::uuid AND deleted_at IS NULL", state, userId);
	}

	public String findUserIdByEmail(String email) {
		return jdbc.queryForObject("SELECT id::text FROM app_user WHERE lower(email)=lower(?) AND deleted_at IS NULL", String.class, email);
	}
}
