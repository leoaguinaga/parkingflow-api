package utp.edu.pe.api.config;

import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
		String message = exception.getReason() == null ? "No se pudo completar la solicitud." : exception.getReason();
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
	}

	@ExceptionHandler(DuplicateKeyException.class)
	ResponseEntity<Map<String, String>> handleDuplicate() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Ya existe un registro con esos datos."));
	}

	@ExceptionHandler(BadCredentialsException.class)
	ResponseEntity<Map<String, String>> handleBadCredentials() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Correo o contraseña incorrectos."));
	}
}
