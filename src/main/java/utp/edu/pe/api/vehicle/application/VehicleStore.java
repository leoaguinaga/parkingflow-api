package utp.edu.pe.api.vehicle.application;

import java.util.List;
import java.util.Map;

public interface VehicleStore {
	List<Map<String, Object>> findAll(String query, String state);
	Map<String, Object> findById(String id);
	String create(String siteId, String typeCode, String licensePlate, String actorEmail);
	int update(String id, String typeCode, String licensePlate, String actorEmail);
	int changeState(String id, String state, String actorEmail);
}
