package utp.edu.pe.api.vehicle.infrastructure;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import utp.edu.pe.api.vehicle.application.VehicleStore;

@Repository
public class JdbcVehicleRepository implements VehicleStore {
	private final JdbcTemplate jdbc;

	public JdbcVehicleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public List<Map<String, Object>> findAll(String query, String state) {
		return jdbc.queryForList("""
				SELECT v.id, v.license_plate AS "licensePlate", v.vehicle_type_id AS "vehicleTypeId",
				       vt.code AS "typeCode", vt.name AS "typeName", v.state,
				       v.created_at AS "createdAt", open_ticket.code AS "openTicketCode",
				       open_ticket.entered_at AS "enteredAt"
				FROM vehicle v
				JOIN vehicle_type vt ON vt.id=v.vehicle_type_id AND vt.deleted_at IS NULL
				LEFT JOIN LATERAL (
				  SELECT t.code, t.entered_at FROM parking_ticket t
				  WHERE t.vehicle_id=v.id AND t.site_id=v.site_id AND t.state='OPEN' AND t.deleted_at IS NULL
			  ORDER BY t.entered_at DESC LIMIT 1
			) open_ticket ON true
			WHERE v.site_id=?::uuid AND v.deleted_at IS NULL
			  AND (CAST(? AS text)='ALL' OR v.state=CAST(? AS text))
			  AND (CAST(? AS text) IS NULL OR v.license_plate ILIKE '%' || CAST(? AS text) || '%' OR vt.name ILIKE '%' || CAST(? AS text) || '%')
			ORDER BY v.created_at DESC
			""", currentSiteId(), state, state, query, query, query);
	}

	@Override
	public Map<String, Object> findById(String id) {
		try {
			return jdbc.queryForMap("""
				SELECT v.id, v.license_plate AS "licensePlate", vt.code AS "typeCode", vt.name AS "typeName", v.state, v.created_at AS "createdAt"
				FROM vehicle v JOIN vehicle_type vt ON vt.id=v.vehicle_type_id AND vt.deleted_at IS NULL
				WHERE v.id=?::uuid AND v.site_id=?::uuid AND v.deleted_at IS NULL
				""", id, currentSiteId());
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el vehículo.");
		}
	}

	@Override
	public String create(String siteId, String typeCode, String licensePlate, String actorEmail) {
		String vehicleId = jdbc.queryForObject("""
				INSERT INTO vehicle (site_id, vehicle_type_id, license_plate)
				SELECT ?::uuid, vt.id, ? FROM vehicle_type vt WHERE vt.code=? AND vt.state='ACTIVE' AND vt.deleted_at IS NULL
				RETURNING id::text
			""", String.class, siteId, licensePlate, typeCode);
		writeAudit(siteId, actorEmail, vehicleId, "CREATED", licensePlate, typeCode, "ACTIVE");
		return vehicleId;
	}

	@Override
	public int update(String id, String typeCode, String licensePlate, String actorEmail) {
		String siteId = currentSiteId();
		int affected = jdbc.update("""
				UPDATE vehicle v SET vehicle_type_id=vt.id, license_plate=?, updated_at=now()
				FROM vehicle_type vt
				WHERE v.id=?::uuid AND v.site_id=?::uuid AND v.deleted_at IS NULL AND v.state='ACTIVE'
				  AND vt.code=? AND vt.state='ACTIVE' AND vt.deleted_at IS NULL
				  AND NOT EXISTS (SELECT 1 FROM parking_ticket t WHERE t.vehicle_id=v.id AND t.state='OPEN' AND t.deleted_at IS NULL)
			""", licensePlate, id, siteId, typeCode);
		if (affected == 0) {
			Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vehicle WHERE id=?::uuid AND site_id=?::uuid AND deleted_at IS NULL)", Boolean.class, id, siteId);
			if (Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes editar un vehículo con un ticket abierto.");
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el vehículo.");
		}
		writeAudit(siteId, actorEmail, id, "UPDATED", licensePlate, typeCode, "ACTIVE");
		return affected;
	}

	@Override
	public int changeState(String id, String state, String actorEmail) {
		String siteId = currentSiteId();
		int affected = jdbc.update("""
				UPDATE vehicle v SET state=?, updated_at=now()
				WHERE v.id=?::uuid AND v.site_id=?::uuid AND v.deleted_at IS NULL
				  AND (? <> 'INACTIVE' OR NOT EXISTS (SELECT 1 FROM parking_ticket t WHERE t.vehicle_id=v.id AND t.state='OPEN' AND t.deleted_at IS NULL))
			""", state, id, siteId, state);
		if (affected == 0) {
			Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vehicle WHERE id=?::uuid AND site_id=?::uuid AND deleted_at IS NULL)", Boolean.class, id, siteId);
			if (Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes desactivar un vehículo con un ticket abierto.");
		}
		if (affected > 0) writeAudit(siteId, actorEmail, id, "INACTIVE".equals(state) ? "DEACTIVATED" : "ACTIVATED", null, null, state);
		return affected;
	}

	private String currentSiteId() {
		return jdbc.queryForObject("SELECT id::text FROM site WHERE code='MAIN' AND state='ACTIVE' AND deleted_at IS NULL", String.class);
	}

	private void writeAudit(String siteId, String actor, String vehicleId, String action, String plate, String typeCode, String state) {
		jdbc.update("""
				INSERT INTO parking_audit (site_id, actor_email, entity_type, entity_id, action, details)
				VALUES (?::uuid, ?, 'VEHICLE', ?::uuid, ?, jsonb_strip_nulls(jsonb_build_object('licensePlate', ?, 'typeCode', ?, 'state', ?)))
				""", siteId, actor, vehicleId, action, plate, typeCode, state);
	}
}
