package utp.edu.pe.api.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Persistence port for parking configuration and user administration. */
public interface ConfigurationStore {
	Map<String, Object> findConfiguration();
	String findMainSiteId();
	void lockSite();
	void lockUserCreation();
	String findVehicleTypeId(String code);
	void updateSite(String siteId, String name, String address);
	void saveCapacity(String siteId, String typeId, int capacity);
	String replaceRate(String siteId, String typeId, BigDecimal hourlyAmount, int graceMinutes, int billingIncrementMinutes);
	void saveNightPeriod(String rateId, String name, LocalTime startsAt, LocalTime endsAt, BigDecimal amount);
	void recordConfigurationActor(String siteId, String email);
	void audit(String siteId, String actor, String action, Map<String, ?> details);
	void auditSiteUpdate(String siteId, String actor, String name, String address);
	List<Map<String, Object>> findUsers();
	void createUser(String firstName, String lastName, String email, String passwordHash, String roleCode);
	int updateUserState(String userId, String state);
	String findUserIdByEmail(String email);
}
