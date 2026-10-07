package utp.edu.pe.api.parking.infrastructure;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import utp.edu.pe.api.parking.application.TicketStore;
import utp.edu.pe.api.parking.application.TicketStore.ExitRate;

@Repository
public class JdbcTicketRepository implements TicketStore {
	private final JdbcTemplate jdbc;

	public JdbcTicketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public List<Map<String, Object>> findAll(String query, String state) {
		return jdbc.queryForList("""
				SELECT t.id, t.code, t.entered_at AS "enteredAt", t.observation, t.state,
				       v.license_plate AS "licensePlate", vt.code AS "typeCode", vt.name AS "typeName",
				       entry_user.email AS "enteredBy", c.total_spaces AS capacity,
				       occupied.open_tickets AS occupied
				FROM parking_ticket t
				JOIN vehicle v ON v.id=t.vehicle_id
				JOIN vehicle_type vt ON vt.id=v.vehicle_type_id
				JOIN user_site entry_link ON entry_link.id=t.entry_user_site_id
				JOIN app_user entry_user ON entry_user.id=entry_link.user_id
				LEFT JOIN capacity c ON c.site_id=t.site_id AND c.vehicle_type_id=vt.id AND c.state='ACTIVE' AND c.deleted_at IS NULL
				LEFT JOIN LATERAL (
				  SELECT count(*) AS open_tickets FROM parking_ticket open_row
				  JOIN vehicle ov ON ov.id=open_row.vehicle_id
				  WHERE open_row.site_id=t.site_id AND ov.vehicle_type_id=vt.id AND open_row.state='OPEN' AND open_row.deleted_at IS NULL
			) occupied ON true
			WHERE t.site_id=?::uuid AND t.deleted_at IS NULL
			  AND (CAST(? AS text)='ALL' OR t.state=CAST(? AS text))
			  AND (CAST(? AS text) IS NULL OR t.code ILIKE '%' || CAST(? AS text) || '%' OR v.license_plate ILIKE '%' || CAST(? AS text) || '%')
			ORDER BY t.entered_at DESC LIMIT 200
			""", currentSiteId(), state, state, query, query, query);
	}

	@Override
	public List<Map<String, Object>> findVehicleTypes() {
		return jdbc.queryForList("SELECT code, name FROM vehicle_type WHERE state='ACTIVE' AND deleted_at IS NULL ORDER BY name");
	}

	@Override
	public List<Map<String, Object>> findOccupancy() {
		return jdbc.queryForList("""
				SELECT vt.code AS "typeCode", vt.name AS "typeName", COALESCE(c.total_spaces, 0) AS capacity,
				       count(t.id)::integer AS occupied,
				       GREATEST(COALESCE(c.total_spaces, 0)-count(t.id)::integer, 0) AS available
				FROM vehicle_type vt
			LEFT JOIN capacity c ON c.vehicle_type_id=vt.id AND c.site_id=?::uuid AND c.state='ACTIVE' AND c.deleted_at IS NULL
			LEFT JOIN vehicle v ON v.vehicle_type_id=vt.id AND v.site_id=?::uuid AND v.deleted_at IS NULL
			LEFT JOIN parking_ticket t ON t.vehicle_id=v.id AND t.site_id=?::uuid AND t.state='OPEN' AND t.deleted_at IS NULL
			WHERE vt.state='ACTIVE' AND vt.deleted_at IS NULL
			GROUP BY vt.code, vt.name, c.total_spaces ORDER BY vt.name
			""", currentSiteId(), currentSiteId(), currentSiteId());
	}

	@Override
	public List<Map<String, Object>> findHistory(String from, String to, String state, String query) {
		return jdbc.queryForList("""
				SELECT t.code, v.license_plate AS \"licensePlate\", vt.name AS \"vehicleType\", t.state,
				       t.entered_at AS \"enteredAt\", t.exited_at AS \"exitedAt\", t.calculated_amount AS amount,
				       p.method AS \"paymentMethod\", p.reference AS \"paymentReference\",
				       entry_user.first_name || ' ' || entry_user.last_name AS \"enteredBy\",
				       exit_user.first_name || ' ' || exit_user.last_name AS \"processedBy\"
				FROM parking_ticket t
				JOIN vehicle v ON v.id=t.vehicle_id
				JOIN vehicle_type vt ON vt.id=v.vehicle_type_id
				JOIN user_site entry_link ON entry_link.id=t.entry_user_site_id
				JOIN app_user entry_user ON entry_user.id=entry_link.user_id
				LEFT JOIN user_site exit_link ON exit_link.id=t.exit_user_site_id
				LEFT JOIN app_user exit_user ON exit_user.id=exit_link.user_id
				LEFT JOIN payment p ON p.parking_ticket_id=t.id AND p.state='RECORDED' AND p.deleted_at IS NULL
				JOIN site s ON s.id=t.site_id
				WHERE t.site_id=?::uuid AND t.deleted_at IS NULL
				  AND (CAST(? AS text) IS NULL OR (t.entered_at AT TIME ZONE s.time_zone)::date >= CAST(? AS date))
				  AND (CAST(? AS text) IS NULL OR (t.entered_at AT TIME ZONE s.time_zone)::date <= CAST(? AS date))
				  AND (CAST(? AS text)='ALL' OR t.state=CAST(? AS text))
				  AND (CAST(? AS text) IS NULL OR t.code ILIKE '%' || CAST(? AS text) || '%' OR v.license_plate ILIKE '%' || CAST(? AS text) || '%')
				ORDER BY t.entered_at DESC LIMIT 500
				""", currentSiteId(), from, from, to, to, state, state, query, query, query);
	}

	@Override
	public Map<String, Object> findSummary(String from, String to) {
		String siteId = currentSiteId();
		Map<String, Object> totals = jdbc.queryForMap("""
				WITH ticket_totals AS (
				  SELECT count(*) FILTER (WHERE t.state='CLOSED')::integer AS \"closedTickets\",
				         count(*) FILTER (WHERE t.state='VOIDED')::integer AS \"voidedTickets\",
				         count(*) FILTER (WHERE t.state='OPEN')::integer AS \"openTickets\"
				  FROM parking_ticket t JOIN site s ON s.id=t.site_id
				  WHERE t.site_id=?::uuid AND t.deleted_at IS NULL
				    AND (CAST(? AS text) IS NULL OR (t.entered_at AT TIME ZONE s.time_zone)::date >= CAST(? AS date))
				    AND (CAST(? AS text) IS NULL OR (t.entered_at AT TIME ZONE s.time_zone)::date <= CAST(? AS date))
				), payment_totals AS (
				  SELECT COALESCE(sum(p.amount) FILTER (WHERE p.method IN ('CASH','YAPE')), 0) AS revenue,
				         COALESCE(sum(p.amount) FILTER (WHERE p.method='CASH'), 0) AS cash,
				         COALESCE(sum(p.amount) FILTER (WHERE p.method='YAPE'), 0) AS yape
				  FROM payment p JOIN parking_ticket t ON t.id=p.parking_ticket_id
				  JOIN site s ON s.id=t.site_id
				  WHERE t.site_id=?::uuid AND t.deleted_at IS NULL AND p.deleted_at IS NULL AND p.state='RECORDED'
				    AND (CAST(? AS text) IS NULL OR (p.paid_at AT TIME ZONE s.time_zone)::date >= CAST(? AS date))
				    AND (CAST(? AS text) IS NULL OR (p.paid_at AT TIME ZONE s.time_zone)::date <= CAST(? AS date))
				)
				SELECT ticket_totals.*, payment_totals.* FROM ticket_totals CROSS JOIN payment_totals
			""", siteId, from, from, to, to, siteId, from, from, to, to);
		List<Map<String, Object>> occupancy = findOccupancy();
		totals.put("occupancy", occupancy);
		return totals;
	}

	@Override
	public Map<String, Object> findByCode(String code) {
		try {
			return jdbc.queryForMap("""
				SELECT t.id, t.code, t.entered_at AS "enteredAt", t.observation, t.state,
				       v.id AS "vehicleId", v.license_plate AS "licensePlate", vt.code AS "typeCode", vt.name AS "typeName",
				       u.first_name || ' ' || u.last_name AS "enteredBy"
			FROM parking_ticket t
			JOIN vehicle v ON v.id=t.vehicle_id JOIN vehicle_type vt ON vt.id=v.vehicle_type_id
			JOIN user_site us ON us.id=t.entry_user_site_id JOIN app_user u ON u.id=us.user_id
			WHERE t.code=? AND t.site_id=?::uuid AND t.deleted_at IS NULL
			""", code, currentSiteId());
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el ticket.");
		}
	}

	@Override
	public Map<String, Object> findByClientOperation(UUID clientOperationId) {
		if (clientOperationId == null) return null;
		jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, clientOperationId.toString());
		List<String> ids = jdbc.query("SELECT id::text FROM parking_ticket WHERE client_operation_id=? AND site_id=?::uuid AND deleted_at IS NULL",
				(result, rowNum) -> result.getString(1), clientOperationId, currentSiteId());
		return ids.isEmpty() ? null : findById(ids.getFirst(), currentSiteId());
	}

	@Override
	public Map<String, Object> createEntry(String typeCode, String plate, String observation, String actorEmail, UUID clientOperationId, OffsetDateTime offlineCreatedAt) {
		String siteId = currentSiteId();
		String typeId = jdbc.queryForObject("SELECT id::text FROM vehicle_type WHERE code=? AND state='ACTIVE' AND deleted_at IS NULL", String.class, typeCode);
		List<Integer> capacities = jdbc.queryForList("""
				SELECT total_spaces FROM capacity WHERE site_id=?::uuid AND vehicle_type_id=?::uuid AND state='ACTIVE' AND deleted_at IS NULL FOR UPDATE
				""", Integer.class, siteId, typeId);
		if (capacities.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Configura la capacidad de este tipo de vehículo antes de registrar ingresos.");
		int capacity = capacities.getFirst();
		String userSiteId = jdbc.queryForObject("""
				SELECT us.id::text FROM user_site us JOIN app_user u ON u.id=us.user_id
				WHERE us.site_id=?::uuid AND lower(u.email)=lower(?) AND us.state='ACTIVE' AND us.deleted_at IS NULL
				""", String.class, siteId, actorEmail);
		List<String> rates = jdbc.query("""
				SELECT id::text FROM rate WHERE site_id=?::uuid AND vehicle_type_id=?::uuid AND state='ACTIVE' AND deleted_at IS NULL
				  AND valid_from <= COALESCE(?::timestamptz, now())
				  AND (valid_until IS NULL OR valid_until > COALESCE(?::timestamptz, now()))
				ORDER BY valid_from DESC LIMIT 1
				""", (result, rowNum) -> result.getString(1), siteId, typeId, offlineCreatedAt, offlineCreatedAt);
		if (rates.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Configura una tarifa para este tipo de vehículo antes de registrar ingresos.");
		String rateId = rates.getFirst();
		Long occupied = jdbc.queryForObject("""
				SELECT count(*) FROM parking_ticket t JOIN vehicle v ON v.id=t.vehicle_id
				WHERE t.site_id=?::uuid AND v.vehicle_type_id=?::uuid AND t.state='OPEN' AND t.deleted_at IS NULL
			""", Long.class, siteId, typeId);
		if (occupied == null || occupied >= capacity) throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay espacios disponibles para este tipo de vehículo.");
		String vehicleId;
		try {
			vehicleId = jdbc.queryForObject("""
					INSERT INTO vehicle (site_id, vehicle_type_id, license_plate) VALUES (?::uuid, ?::uuid, ?)
					ON CONFLICT (license_plate) WHERE deleted_at IS NULL DO UPDATE
					SET vehicle_type_id=EXCLUDED.vehicle_type_id, state='ACTIVE', updated_at=now()
					WHERE NOT EXISTS (
					  SELECT 1 FROM parking_ticket t WHERE t.vehicle_id=vehicle.id AND t.state='OPEN' AND t.deleted_at IS NULL
					)
					RETURNING id::text
					""", String.class, siteId, typeId, plate);
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La placa está en uso o el vehículo ya tiene un ingreso abierto.");
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El vehículo ya tiene un ingreso abierto.");
		}
		try {
			List<String> insertedTicketIds = jdbc.query("""
					INSERT INTO parking_ticket (code, site_id, vehicle_id, entry_user_site_id, applied_rate_id, entered_at, observation, client_operation_id)
					VALUES ('PF-' || to_char(COALESCE(?::timestamptz, now()) AT TIME ZONE 'UTC', 'YYMMDDHH24MISS') || '-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 6)),
					       ?::uuid, ?::uuid, ?::uuid, ?::uuid, COALESCE(?::timestamptz, now()), ?, ?::uuid)
					ON CONFLICT (client_operation_id) WHERE client_operation_id IS NOT NULL DO NOTHING
					RETURNING id::text
					""", (result, rowNum) -> result.getString(1), offlineCreatedAt, siteId, vehicleId, userSiteId, rateId, offlineCreatedAt,
					observation == null || observation.isBlank() ? null : observation.trim(), clientOperationId);
			String ticketId;
			boolean created = !insertedTicketIds.isEmpty();
			if (created) ticketId = insertedTicketIds.getFirst();
			else if (clientOperationId != null) ticketId = jdbc.queryForObject("SELECT id::text FROM parking_ticket WHERE client_operation_id=? AND site_id=?::uuid", String.class, clientOperationId, siteId);
			else throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo registrar el ticket.");
			Map<String, Object> ticket = findById(ticketId, siteId);
			if (created) writeTicketCreatedAudit(siteId, actorEmail, ticketId, String.valueOf(ticket.get("code")), plate, typeCode);
			return ticket;
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El vehículo ya tiene un ingreso abierto.");
		}
	}

	@Override
	public void voidTicket(String code, String reason, String actorEmail) {
		String siteId = currentSiteId();
		List<String> ticketIds = jdbc.query("SELECT id::text FROM parking_ticket WHERE code=? AND site_id=?::uuid AND state='OPEN' AND deleted_at IS NULL",
				(result, rowNum) -> result.getString(1), code, siteId);
		if (ticketIds.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden anular tickets abiertos.");
		int affected = jdbc.update("""
				UPDATE parking_ticket t SET state='VOIDED', observation=concat_ws(E'\\n', nullif(t.observation, ''), 'Anulación: ' || ?), updated_at=now()
				WHERE t.code=? AND t.site_id=?::uuid AND t.state='OPEN' AND t.deleted_at IS NULL
				""", reason + " (" + actorEmail + ")", code, siteId);
		if (affected == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden anular tickets abiertos.");
		writeTicketVoidedAudit(siteId, actorEmail, ticketIds.getFirst(), code, reason);
	}

	@Override
	public ExitRate loadExitRate(String code) {
		try {
			return jdbc.queryForObject("""
				SELECT t.id::text AS ticket_id, t.code, v.license_plate, vt.name AS vehicle_type, t.entered_at, s.time_zone,
				       r.grace_minutes, r.billing_increment_minutes, r.hourly_amount,
				       rp.starts_at AS night_starts_at, rp.ends_at AS night_ends_at, rp.hourly_amount AS night_hourly_amount
			FROM parking_ticket t
			JOIN vehicle v ON v.id=t.vehicle_id
			JOIN vehicle_type vt ON vt.id=v.vehicle_type_id
			JOIN site s ON s.id=t.site_id
			LEFT JOIN rate r ON r.id=t.applied_rate_id
			LEFT JOIN LATERAL (SELECT starts_at, ends_at, hourly_amount FROM rate_period p
			                   WHERE p.rate_id=r.id AND p.deleted_at IS NULL AND p.state='ACTIVE'
			                   ORDER BY p.created_at DESC LIMIT 1) rp ON true
			WHERE (upper(t.code)=upper(?) OR upper(v.license_plate)=upper(?))
			  AND t.site_id=?::uuid AND t.state='OPEN' AND t.deleted_at IS NULL
			FOR UPDATE OF t
			""", (row, rowNum) -> {
				if (row.getBigDecimal("hourly_amount") == null) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket no tiene una tarifa configurada.");
				}
				return new ExitRate(row.getString("ticket_id"), row.getString("code"), row.getString("license_plate"), row.getString("vehicle_type"),
					row.getObject("entered_at", java.time.OffsetDateTime.class), row.getString("time_zone"),
					row.getInt("grace_minutes"), row.getInt("billing_increment_minutes"), row.getBigDecimal("hourly_amount"),
					row.getObject("night_starts_at", LocalTime.class), row.getObject("night_ends_at", LocalTime.class),
					row.getBigDecimal("night_hourly_amount"));
			}, code, code, currentSiteId());
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket no está abierto o no existe.");
		}
	}

	@Override
	public String currentUserSiteId(String actorEmail) {
		try {
			return jdbc.queryForObject("""
				SELECT us.id::text FROM user_site us JOIN app_user u ON u.id=us.user_id
				WHERE us.site_id=?::uuid AND lower(u.email)=lower(?) AND us.state='ACTIVE' AND us.deleted_at IS NULL
				""", String.class, currentSiteId(), actorEmail);
		} catch (org.springframework.dao.EmptyResultDataAccessException exception) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El usuario no tiene acceso a esta sede.");
		}
	}

	@Override
	public void closeTicket(String code, String actorSiteId, String method, String reference, BigDecimal amount, java.time.Instant exitedAt) {
		String siteId = currentSiteId();
		String ticketId = jdbc.queryForObject("SELECT id::text FROM parking_ticket WHERE code=? AND site_id=?::uuid AND state='OPEN' AND deleted_at IS NULL", String.class, code, siteId);
		java.sql.Timestamp processedAt = java.sql.Timestamp.from(exitedAt);
		jdbc.update("INSERT INTO payment (parking_ticket_id, user_site_id, amount, method, paid_at, reference) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)", ticketId, actorSiteId, amount, method, processedAt, reference);
		int updated = jdbc.update("UPDATE parking_ticket SET state='CLOSED', exited_at=?, exit_user_site_id=?::uuid, calculated_amount=?, updated_at=now() WHERE id=?::uuid AND state='OPEN' AND deleted_at IS NULL", processedAt, actorSiteId, amount, ticketId);
		if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "El ticket ya fue procesado.");
		jdbc.update("""
			INSERT INTO parking_audit (site_id, actor_email, entity_type, entity_id, action, details)
			VALUES (?::uuid, (SELECT u.email FROM user_site us JOIN app_user u ON u.id=us.user_id WHERE us.id=?::uuid),
			        'TICKET', ?::uuid, 'CLOSED', jsonb_build_object('code', ?, 'amount', ?, 'method', ?, 'reference', ?))
			""", siteId, actorSiteId, ticketId, code, amount, method, reference);
	}

	private Map<String, Object> findById(String id, String siteId) {
		return jdbc.queryForMap("""
				SELECT t.id, t.code, t.entered_at AS "enteredAt", t.observation, t.state,
				       v.id AS "vehicleId", v.license_plate AS "licensePlate", vt.code AS "typeCode", vt.name AS "typeName",
				       u.first_name || ' ' || u.last_name AS "enteredBy"
			FROM parking_ticket t JOIN vehicle v ON v.id=t.vehicle_id JOIN vehicle_type vt ON vt.id=v.vehicle_type_id
			JOIN user_site us ON us.id=t.entry_user_site_id JOIN app_user u ON u.id=us.user_id
			WHERE t.id=?::uuid AND t.site_id=?::uuid
			""", id, siteId);
	}

	private String currentSiteId() {
		return jdbc.queryForObject("SELECT id::text FROM site WHERE code='MAIN' AND state='ACTIVE' AND deleted_at IS NULL", String.class);
	}

	private void writeTicketCreatedAudit(String siteId, String actor, String entityId, String code, String plate, String typeCode) {
		jdbc.update("""
				INSERT INTO parking_audit (site_id, actor_email, entity_type, entity_id, action, details)
				VALUES (?::uuid, ?, 'TICKET', ?::uuid, 'CREATED', jsonb_build_object('code', ?, 'licensePlate', ?, 'typeCode', ?))
				""", siteId, actor, entityId, code, plate, typeCode);
	}

	private void writeTicketVoidedAudit(String siteId, String actor, String entityId, String code, String reason) {
		jdbc.update("""
				INSERT INTO parking_audit (site_id, actor_email, entity_type, entity_id, action, details)
				VALUES (?::uuid, ?, 'TICKET', ?::uuid, 'VOIDED', jsonb_build_object('code', ?, 'reason', ?))
				""", siteId, actor, entityId, code, reason);
	}
}
