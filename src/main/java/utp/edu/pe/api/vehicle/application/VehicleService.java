package utp.edu.pe.api.vehicle.application;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleService {
	private static final List<String> TYPE_CODES = List.of("CAR", "MOTORCYCLE", "VAN");
	private static final Pattern PLATE_PATTERN = Pattern.compile("^[A-Z0-9-]{5,12}$");
	private final VehicleStore store;

	public VehicleService(VehicleStore store) { this.store = store; }

	public List<Map<String, Object>> list(String query, String state) {
		if (state != null && !List.of("ACTIVE", "INACTIVE", "ALL").contains(state)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filtro de estado inválido.");
		}
		return store.findAll(cleanQuery(query), state == null ? "ACTIVE" : state);
	}

	public Map<String, Object> get(String id) { return store.findById(id); }

	@Transactional(rollbackFor = Exception.class)
	public String create(VehicleCommand request, String siteId, String actor) {
		var data = validate(request);
		try { return store.create(siteId, data.typeCode(), data.licensePlate(), actor); }
		catch (DuplicateKeyException exception) { throw duplicatePlate(); }
	}

	@Transactional(rollbackFor = Exception.class)
	public void update(String id, VehicleCommand request, String actor) {
		var data = validate(request);
		try {
			if (store.update(id, data.typeCode(), data.licensePlate(), actor) == 0) throw notFound();
		} catch (DuplicateKeyException exception) { throw duplicatePlate(); }
	}

	@Transactional(rollbackFor = Exception.class)
	public void changeState(String id, String state, String actor) {
		if (!List.of("ACTIVE", "INACTIVE").contains(state)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de vehículo inválido.");
		if (store.changeState(id, state, actor) == 0) throw notFound();
	}

	private VehicleData validate(VehicleCommand request) {
		if (request == null || request.typeCode() == null || !TYPE_CODES.contains(request.typeCode())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona un tipo de vehículo válido.");
		}
		String plate = normalizePlate(request.licensePlate());
		if (plate == null || !PLATE_PATTERN.matcher(plate).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La placa debe tener entre 5 y 12 letras, números o guiones.");
		}
		return new VehicleData(request.typeCode(), plate);
	}

	private String normalizePlate(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
		return normalized.isBlank() ? null : normalized;
	}

	private String cleanQuery(String query) { return query == null ? null : query.trim(); }
	private ResponseStatusException duplicatePlate() { return new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un vehículo con esa placa."); }
	private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el vehículo."); }
	public record VehicleCommand(String licensePlate, String typeCode) { }
	private record VehicleData(String typeCode, String licensePlate) { }
}
