package utp.edu.pe.api.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/configuration")
public class DatabaseController {

	private final ConfigurationService configurationService;
	private final UserAdministrationService userAdministrationService;

	public DatabaseController(ConfigurationService configurationService, UserAdministrationService userAdministrationService) {
		this.configurationService = configurationService;
		this.userAdministrationService = userAdministrationService;
	}

	@GetMapping
	public Map<String, Object> getConfiguration() {
		return configurationService.getConfiguration();
	}

	@PutMapping
	public Map<String, String> updateConfiguration(@RequestBody ConfigurationRequest request, Authentication authentication) {
		configurationService.saveConfiguration(request, authentication.getName());
		return Map.of("message", "Configuración guardada.");
	}

	@GetMapping("/users")
	public List<Map<String, Object>> listUsers() {
		return userAdministrationService.getUsers();
	}

	@PostMapping("/users")
	public Map<String, String> createUser(@RequestBody CreateUserRequest request, Authentication authentication) {
		userAdministrationService.createUser(request, authentication.getName());
		return Map.of("message", "Usuario creado.");
	}

	@PatchMapping("/users/{id}/state")
	public Map<String, String> updateUserState(@PathVariable String id, @RequestBody UserStateRequest request, Authentication authentication) {
		userAdministrationService.updateUserState(id, request.state(), authentication.getName());
		return Map.of("message", "Estado de usuario actualizado.");
	}

	public record ConfigurationRequest(String siteName, String address, List<VehicleTypeConfiguration> vehicleTypes) { }
	public record VehicleTypeConfiguration(String code, Integer capacity, BigDecimal hourlyAmount, Integer graceMinutes, Integer billingIncrementMinutes, NightPeriod nightPeriod) { }
	public record NightPeriod(String name, LocalTime startsAt, LocalTime endsAt, BigDecimal hourlyAmount) { }
	public record CreateUserRequest(String firstName, String lastName, String email, String password, String role) { }
	public record UserStateRequest(String state) { }
}
