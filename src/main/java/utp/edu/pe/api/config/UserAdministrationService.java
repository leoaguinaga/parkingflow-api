package utp.edu.pe.api.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAdministrationService {
	private static final List<String> ROLES = List.of("ADMIN", "WORKER");
	private static final List<String> STATES = List.of("ACTIVE", "INACTIVE");

	private final ConfigurationStore store;
	private final PasswordEncoder passwordEncoder;

	public UserAdministrationService(ConfigurationStore store, PasswordEncoder passwordEncoder) {
		this.store = store;
		this.passwordEncoder = passwordEncoder;
	}

	public List<Map<String, Object>> getUsers() { return store.findUsers(); }

	@Transactional(rollbackFor = Exception.class)
	public void createUser(DatabaseController.CreateUserRequest request, String actor) {
		if (request.firstName() == null || request.firstName().isBlank() || request.firstName().length() > 120
				|| request.lastName() == null || request.lastName().isBlank() || request.lastName().length() > 120
				|| request.email() == null || request.email().length() > 254 || !request.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
				|| request.password() == null || request.password().length() < 12 || !ROLES.contains(request.role())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revisa los datos, el rol y la contraseña de al menos 12 caracteres.");
		}
		store.lockUserCreation();
		try {
			String email = request.email().trim().toLowerCase(Locale.ROOT);
			store.createUser(request.firstName().trim(), request.lastName().trim(), email,
					passwordEncoder.encode(request.password()), request.role());
			store.audit(store.findMainSiteId(), actor, "USER_CREATED", Map.of("email", email, "role", request.role()));
		} catch (DuplicateKeyException exception) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta con ese correo.");
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public void updateUserState(String userId, String state, String actor) {
		if (!STATES.contains(state)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de usuario inválido.");
		if ("INACTIVE".equals(state) && userId.equals(store.findUserIdByEmail(actor))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No puedes desactivar tu propia cuenta.");
		}
		if (store.updateUserState(userId, state) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el usuario.");
		store.audit(store.findMainSiteId(), actor, "USER_STATE_UPDATED", Map.of("userId", userId, "state", state));
	}
}
