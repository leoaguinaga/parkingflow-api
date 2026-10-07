package utp.edu.pe.api.parking.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketService {
	private static final List<String> TYPE_CODES = List.of("CAR", "MOTORCYCLE", "VAN");
	private static final Pattern PLATE_PATTERN = Pattern.compile("^[A-Z0-9-]{5,12}$");
	private final TicketStore store;
	private final ParkingFeeCalculator feeCalculator;

	public TicketService(TicketStore store, ParkingFeeCalculator feeCalculator) { this.store = store; this.feeCalculator = feeCalculator; }

	public List<Map<String, Object>> list(String query, String state) {
		if (state != null && !List.of("OPEN", "VOIDED", "ALL").contains(state)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro de estado inválido.");
		}
		return store.findAll(query == null ? null : query.trim(), state == null ? "OPEN" : state);
	}
	public List<Map<String, Object>> vehicleTypes() { return store.findVehicleTypes(); }
	public List<Map<String, Object>> occupancy() { return store.findOccupancy(); }
	public List<Map<String, Object>> history(String from, String to, String state, String query) {
		validatePeriod(from, to);
		if (state != null && !List.of("ALL", "OPEN", "CLOSED", "VOIDED").contains(state)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro de estado inválido.");
		}
		return store.findHistory(from, to, state == null ? "ALL" : state, query == null || query.isBlank() ? null : query.trim());
	}
	public Map<String, Object> summary(String from, String to) {
		validatePeriod(from, to);
		return store.findSummary(from, to);
	}
	private void validatePeriod(String from, String to) {
		try {
			LocalDate start = from == null || from.isBlank() ? null : LocalDate.parse(from);
			LocalDate end = to == null || to.isBlank() ? null : LocalDate.parse(to);
			if (start != null && end != null && start.isAfter(end)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha inicial debe ser anterior a la fecha final.");
			}
		} catch (java.time.format.DateTimeParseException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usa fechas con formato AAAA-MM-DD.");
		}
	}

	public Map<String, Object> get(String code) { return store.findByCode(normalizeCode(code)); }

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> create(EntryCommand request, String actor) {
		if (request == null || request.typeCode() == null || !TYPE_CODES.contains(request.typeCode())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona un tipo de vehículo válido.");
		}
		String plate = normalizePlate(request.licensePlate());
		if (plate == null || !PLATE_PATTERN.matcher(plate).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La placa debe tener entre 5 y 12 letras, números o guiones.");
		}
		if (request.observation() != null && request.observation().length() > 500) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La observación no puede superar los 500 caracteres.");
		}
		if (request.clientOperationId() != null) {
			Map<String, Object> previous = store.findByClientOperation(request.clientOperationId());
			if (previous != null) return previous;
		}
		OffsetDateTime offlineCreatedAt = request.offlineCreatedAt();
		if (offlineCreatedAt != null && (offlineCreatedAt.isAfter(OffsetDateTime.now().plusMinutes(5)) || offlineCreatedAt.isBefore(OffsetDateTime.now().minusDays(3)))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha del ingreso offline debe estar dentro de las últimas 72 horas y no ser futura.");
		}
		try { return store.createEntry(request.typeCode(), plate, request.observation(), actor, request.clientOperationId(), offlineCreatedAt); }
		catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El vehículo ya tiene un ingreso abierto o la operación se duplicó.");
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public void voidTicket(String code, String reason, String actor) {
		String normalizedCode = normalizeCode(code);
		if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el motivo de anulación (máximo 500 caracteres).");
		}
		store.voidTicket(normalizedCode, reason.trim(), actor);
	}

	public ParkingFeeCalculator.Quote quoteExit(String code) {
		TicketStore.ExitRate rate = store.loadExitRate(normalizeCode(code));
		return feeCalculator.quote(rate, Instant.now());
	}

	@Transactional(rollbackFor = Exception.class)
	public ParkingFeeCalculator.Quote closeTicket(String code, String method, String reference, BigDecimal confirmedAmount, String actor) {
		String normalizedCode = normalizeCode(code);
		if (method == null || !List.of("CASH", "YAPE").contains(method)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona efectivo o Yape.");
		}
		if (reference != null && reference.trim().length() > 120) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La referencia no puede superar los 120 caracteres.");
		}
		TicketStore.ExitRate rate = store.loadExitRate(normalizedCode);
		ParkingFeeCalculator.Quote quote = feeCalculator.quote(rate, Instant.now());
		if (confirmedAmount == null || confirmedAmount.compareTo(quote.amount()) != 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "El monto cambió; revisa y confirma nuevamente el importe actualizado.");
		}
		store.closeTicket(normalizedCode, store.currentUserSiteId(actor), method,
				reference == null || reference.isBlank() ? null : reference.trim(), quote.amount(), quote.exitedAt());
		return quote;
	}

	private String normalizePlate(String value) {
		if (value == null) return null;
		String result = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
				.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
		return result.isBlank() ? null : result;
	}
	private String normalizeCode(String value) {
		if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el código del ticket.");
		return value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
	}
	public record EntryCommand(String licensePlate, String typeCode, String observation, UUID clientOperationId, OffsetDateTime offlineCreatedAt) { }
}
