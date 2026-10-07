package utp.edu.pe.api.vehicle.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import utp.edu.pe.api.auth.application.UserStore;
import utp.edu.pe.api.vehicle.application.VehicleService;
import utp.edu.pe.api.vehicle.application.VehicleService.VehicleCommand;

@RestController
@RequestMapping("/api/parking/vehicles")
public class VehicleController {
	private final VehicleService vehicles;
	private final UserStore users;

	public VehicleController(VehicleService vehicles, UserStore users) {
		this.vehicles = vehicles;
		this.users = users;
	}

	@GetMapping
	public List<Map<String, Object>> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) String state) { return vehicles.list(q, state); }

	@GetMapping("/{id}")
	public Map<String, Object> get(@PathVariable String id) { return vehicles.get(id); }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, String> create(@Valid @RequestBody VehicleRequest request, Authentication authentication) {
		String siteId = users.findProfileByEmail(authentication.getName()).get("siteId").toString();
		return Map.of("id", vehicles.create(new VehicleCommand(request.licensePlate(), request.typeCode()), siteId, authentication.getName()));
	}

	@PutMapping("/{id}")
	public Map<String, String> update(@PathVariable String id, @Valid @RequestBody VehicleRequest request, Authentication authentication) {
		vehicles.update(id, new VehicleCommand(request.licensePlate(), request.typeCode()), authentication.getName());
		return Map.of("message", "Vehículo actualizado.");
	}

	@PatchMapping("/{id}/state")
	public Map<String, String> changeState(@PathVariable String id, @Valid @RequestBody VehicleStateRequest request, Authentication authentication) {
		vehicles.changeState(id, request.state(), authentication.getName());
		return Map.of("message", "Estado actualizado.");
	}

	public record VehicleRequest(@NotBlank @Size(max = 12) @Pattern(regexp = "^[A-Za-z0-9\\s-]+$") String licensePlate,
			@NotBlank @Size(max = 30) String typeCode) { }
	public record VehicleStateRequest(@NotBlank @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String state) { }
}
