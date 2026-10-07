package utp.edu.pe.api.auth.application;

import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

	private final UserStore users;
	private final PasswordEncoder passwordEncoder;

	public AuthService(UserStore users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
	}

	public boolean isSetupComplete() {
		return users.hasAnyUser();
	}

	public Map<String, Object> currentUser(String email) {
		return users.findProfileByEmail(email);
	}

	@Transactional(rollbackFor = Exception.class)
	public void bootstrap(String firstName, String lastName, String email, String password) {
		users.lockForBootstrap();
		if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()
				|| email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
				|| password == null || password.length() < 12) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Completa los datos y usa una contraseña de al menos 12 caracteres.");
		}
		if (users.hasAnyUser()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración inicial ya fue completada.");
		}
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		if (users.emailExists(normalizedEmail)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta con ese correo.");
		}
		String userId = users.create(firstName.trim(), lastName.trim(), normalizedEmail, passwordEncoder.encode(password));
		users.assignSiteRole(userId, "ADMIN");
	}
}
