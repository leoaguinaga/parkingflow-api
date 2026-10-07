package utp.edu.pe.api.config;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConfigurationService {

	private final ConfigurationStore repository;

	public ConfigurationService(ConfigurationStore repository) {
		this.repository = repository;
	}

	public Map<String, Object> getConfiguration() { return repository.findConfiguration(); }

	@Transactional(rollbackFor = Exception.class)
	public void saveConfiguration(DatabaseController.ConfigurationRequest request, String actor) {
		if (request.siteName() == null || request.siteName().isBlank() || request.address() == null || request.address().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Completa el nombre y la dirección del estacionamiento.");
		}
		if (request.vehicleTypes() == null || request.vehicleTypes().size() != 3) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envía la configuración de los tres tipos de vehículo.");
		}
		for (var item : request.vehicleTypes()) {
			if (item == null || item.code() == null || item.capacity() == null || item.capacity() < 0
					|| item.hourlyAmount() == null || item.hourlyAmount().signum() < 0
					|| item.graceMinutes() == null || item.graceMinutes() < 0
					|| item.billingIncrementMinutes() == null || item.billingIncrementMinutes() < 1 || item.billingIncrementMinutes() > 1440) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revisa la capacidad, tarifa y tolerancia de cada tipo de vehículo.");
			}
			var night = item.nightPeriod();
			if (night != null && (night.name() == null || night.name().isBlank() || night.startsAt() == null || night.endsAt() == null
					|| night.startsAt().equals(night.endsAt()) || night.hourlyAmount() == null || night.hourlyAmount().signum() < 0)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Completa correctamente la franja y tarifa nocturna.");
			}
		}
		List<String> expectedCodes = List.of("CAR", "MOTORCYCLE", "VAN");
		List<String> suppliedCodes = request.vehicleTypes().stream().map(DatabaseController.VehicleTypeConfiguration::code).sorted().toList();
		if (!suppliedCodes.equals(expectedCodes.stream().sorted().toList()) || new LinkedHashSet<>(suppliedCodes).size() != suppliedCodes.size()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los tipos de vehículo no coinciden con el catálogo.");
		}
		String siteId = repository.findMainSiteId();
		repository.lockSite();
		repository.updateSite(siteId, request.siteName().trim(), request.address().trim());
		repository.auditSiteUpdate(siteId, actor, request.siteName().trim(), request.address().trim());
		for (DatabaseController.VehicleTypeConfiguration item : request.vehicleTypes()) {
			String typeId = repository.findVehicleTypeId(item.code());
			repository.saveCapacity(siteId, typeId, item.capacity());
			String rateId = repository.replaceRate(siteId, typeId, item.hourlyAmount(), item.graceMinutes(), item.billingIncrementMinutes());
			if (item.nightPeriod() != null) {
				var night = item.nightPeriod();
				repository.saveNightPeriod(rateId, night.name().trim(), night.startsAt(), night.endsAt(), night.hourlyAmount());
			}
		}
		repository.recordConfigurationActor(siteId, actor);
		repository.audit(siteId, actor, "CAPACITY_AND_RATES_UPDATED", Map.of("vehicleTypes", 3));
	}

}
