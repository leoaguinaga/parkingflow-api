package utp.edu.pe.api.parking.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import utp.edu.pe.api.parking.application.TicketService;
import utp.edu.pe.api.parking.application.TicketService.EntryCommand;

@RestController
@RequestMapping("/api/parking/tickets")
public class TicketController {
	private final TicketService tickets;

	public TicketController(TicketService tickets) { this.tickets = tickets; }

	@GetMapping
	public List<Map<String, Object>> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) String state) { return tickets.list(q, state); }

	@GetMapping("/vehicle-types")
	public List<Map<String, Object>> vehicleTypes() { return tickets.vehicleTypes(); }

	@GetMapping("/occupancy")
	public List<Map<String, Object>> occupancy() { return tickets.occupancy(); }

	@GetMapping("/history")
	public List<Map<String, Object>> history(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(required = false) String state,
			@RequestParam(required = false) String q) { return tickets.history(from, to, state, q); }

	@GetMapping("/summary")
	public Map<String, Object> summary(@RequestParam(required = false) String from,
			@RequestParam(required = false) String to) { return tickets.summary(from, to); }

	@GetMapping("/{code}")
	public Map<String, Object> get(@PathVariable String code) { return tickets.get(code); }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@Valid @RequestBody EntryRequest request, Authentication authentication) {
		return tickets.create(new EntryCommand(request.licensePlate(), request.typeCode(), request.observation(), request.clientOperationId(), request.offlineCreatedAt()), authentication.getName());
	}

	@PutMapping("/{code}/void")
	public Map<String, String> voidTicket(@PathVariable String code, @Valid @RequestBody VoidRequest request,
			Authentication authentication) {
		tickets.voidTicket(code, request.reason(), authentication.getName());
		return Map.of("message", "Ticket anulado.");
	}

	@GetMapping("/{code}/quote")
	public utp.edu.pe.api.parking.application.ParkingFeeCalculator.Quote quoteExit(@PathVariable String code) {
		return tickets.quoteExit(code);
	}

	@PutMapping("/{code}/close")
	public utp.edu.pe.api.parking.application.ParkingFeeCalculator.Quote closeTicket(@PathVariable String code,
			@Valid @RequestBody CloseRequest request, Authentication authentication) {
		return tickets.closeTicket(code, request.method(), request.reference(), request.confirmedAmount(), authentication.getName());
	}

	public record EntryRequest(@NotBlank @Size(max = 12) String licensePlate,
			@NotBlank @Size(max = 30) String typeCode, @Size(max = 500) String observation,
			UUID clientOperationId, OffsetDateTime offlineCreatedAt) { }
	public record VoidRequest(@NotBlank @Size(max = 500) String reason) { }
	public record CloseRequest(@NotBlank String method, @Size(max = 120) String reference,
			@NotNull java.math.BigDecimal confirmedAmount) { }
}
