package utp.edu.pe.api.parking.application;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface TicketStore {
	List<Map<String, Object>> findAll(String query, String state);
	List<Map<String, Object>> findVehicleTypes();
	List<Map<String, Object>> findOccupancy();
	List<Map<String, Object>> findHistory(String from, String to, String state, String query);
	Map<String, Object> findSummary(String from, String to);
	Map<String, Object> findByCode(String code);
	Map<String, Object> createEntry(String typeCode, String plate, String observation, String actorEmail, UUID clientOperationId, OffsetDateTime offlineCreatedAt);
	Map<String, Object> findByClientOperation(UUID clientOperationId);
	void voidTicket(String code, String reason, String actorEmail);
	ExitRate loadExitRate(String code);
	String currentUserSiteId(String actorEmail);
	void closeTicket(String code, String actorSiteId, String method, String reference, BigDecimal amount, java.time.Instant exitedAt);

	record ExitRate(String ticketId, String code, String licensePlate, String vehicleType,
			OffsetDateTime enteredAt, String timeZone,
			int graceMinutes, int billingIncrementMinutes, BigDecimal hourlyAmount,
			LocalTime nightStartsAt, LocalTime nightEndsAt, BigDecimal nightHourlyAmount) { }
}
