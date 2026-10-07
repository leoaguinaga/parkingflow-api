package utp.edu.pe.api.auth.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import utp.edu.pe.api.auth.application.AuthService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;
	private final AuthenticationManager authenticationManager;

	public AuthController(AuthService authService, AuthenticationManager authenticationManager) {
		this.authService = authService;
		this.authenticationManager = authenticationManager;
	}

	@GetMapping("/setup-status")
	public Map<String, Boolean> setupStatus() {
		return Map.of("complete", authService.isSetupComplete());
	}

	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken csrfToken) {
		return Map.of("token", csrfToken.getToken());
	}

	@PostMapping("/login")
	public Map<String, String> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
		if (request.username() == null || request.password() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingresa tu correo y contraseña.");
		}
		Authentication authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.username().trim(), request.password()));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		servletRequest.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		return Map.of("message", "Sesión iniciada.");
	}

	@PostMapping("/logout")
	public Map<String, String> logout(HttpServletRequest request) {
		var session = request.getSession(false);
		if (session != null) session.invalidate();
		SecurityContextHolder.clearContext();
		return Map.of("message", "Sesión cerrada.");
	}

	@GetMapping("/me")
	public Map<String, Object> currentUser(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión para continuar.");
		}
		return authService.currentUser(authentication.getName());
	}

	public record LoginRequest(String username, String password) { }
}
