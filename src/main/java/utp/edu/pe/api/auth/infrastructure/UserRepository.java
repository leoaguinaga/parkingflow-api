package utp.edu.pe.api.auth.infrastructure;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import utp.edu.pe.api.auth.application.UserStore;

@Repository
public class UserRepository implements UserStore {

	private final JdbcTemplate jdbc;

	public UserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean hasAnyUser() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE deleted_at IS NULL", Integer.class);
		return count != null && count > 0;
	}

	public void lockForBootstrap() {
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('parkflow-first-admin', 0))", Object.class);
	}

	public String create(String firstName, String lastName, String email, String passwordHash) {
		return jdbc.queryForObject("""
				INSERT INTO app_user (first_name, last_name, email, password_hash)
				VALUES (?, ?, ?, ?) RETURNING id::text
				""", String.class, firstName, lastName, email, passwordHash);
	}

	public void assignSiteRole(String userId, String roleCode) {
		int affected = jdbc.update("""
				INSERT INTO user_site (user_id, site_id, role_id)
				SELECT ?::uuid, s.id, r.id FROM site s CROSS JOIN role r
				WHERE s.code = 'MAIN' AND s.state = 'ACTIVE' AND r.code = ? AND r.state = 'ACTIVE'
				""", userId, roleCode);
		if (affected != 1) throw new IllegalStateException("Default site or role is missing");
	}

	public Map<String, Object> findProfileByEmail(String email) {
		return jdbc.queryForMap("""
				SELECT u.id, u.first_name AS \"firstName\", u.last_name AS \"lastName\", u.email,
				       r.code AS role, s.id AS \"siteId\", s.name AS \"siteName\"
				FROM app_user u
				JOIN user_site us ON us.user_id = u.id AND us.deleted_at IS NULL AND us.state = 'ACTIVE'
				JOIN role r ON r.id = us.role_id AND r.deleted_at IS NULL AND r.state = 'ACTIVE'
				JOIN site s ON s.id = us.site_id AND s.deleted_at IS NULL AND s.state = 'ACTIVE'
				WHERE lower(u.email) = lower(?) AND u.deleted_at IS NULL AND u.state = 'ACTIVE'
				""", email);
	}

	public boolean emailExists(String email) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(email) = lower(?) AND deleted_at IS NULL", Integer.class, email);
		return count != null && count > 0;
	}

	public Account findAccountForAuthentication(String email) {
		return jdbc.queryForObject("""
				SELECT u.email, u.password_hash, u.state, r.code AS role
				FROM app_user u JOIN user_site us ON us.user_id=u.id AND us.state='ACTIVE' AND us.deleted_at IS NULL
				JOIN role r ON r.id=us.role_id AND r.state='ACTIVE' AND r.deleted_at IS NULL
				WHERE lower(u.email)=lower(?) AND u.deleted_at IS NULL LIMIT 1
				""", (result, rowNum) -> new Account(result.getString("email"), result.getString("password_hash"),
				result.getString("role"), "ACTIVE".equals(result.getString("state"))), email);
	}
}
