package utp.edu.pe.api.auth.application;

import java.util.Map;

/** Persistence port used by authentication and account setup use cases. */
public interface UserStore {
	boolean hasAnyUser();
	void lockForBootstrap();
	String create(String firstName, String lastName, String email, String passwordHash);
	void assignSiteRole(String userId, String roleCode);
	Map<String, Object> findProfileByEmail(String email);
	boolean emailExists(String email);
	Account findAccountForAuthentication(String email);

	record Account(String email, String passwordHash, String role, boolean active) { }
}
