package utp.edu.pe.api.auth.application;

import java.io.Console;
import java.util.Arrays;
import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "parkflow.cli.create-admin", havingValue = "true")
public class AdminProvisioningCommand implements ApplicationRunner {
	private final AuthService authService;

	public AdminProvisioningCommand(AuthService authService) {
		this.authService = authService;
	}

	@Override
	public void run(ApplicationArguments args) {
		Console console = System.console();
		if (console == null) {
			throw new IllegalStateException("Ejecuta este comando desde una terminal interactiva para proteger la contraseña.");
		}
		String firstName = console.readLine("Nombre: ");
		String lastName = console.readLine("Apellido: ");
		String email = console.readLine("Correo: ");
		char[] password = console.readPassword("Contraseña (mínimo 12 caracteres): ");
		char[] confirmation = console.readPassword("Confirma la contraseña: ");
		try {
			if (password == null || confirmation == null) {
				throw new IllegalArgumentException("No se recibió la contraseña completa.");
			}
			if (!Arrays.equals(password, confirmation)) {
				throw new IllegalArgumentException("Las contraseñas no coinciden.");
			}
			authService.bootstrap(firstName, lastName, email, new String(password));
			console.printf("Cuenta administradora creada para %s.%n", email.trim().toLowerCase(Locale.ROOT));
		} finally {
			if (password != null) Arrays.fill(password, '\0');
			if (confirmation != null) Arrays.fill(confirmation, '\0');
		}
	}
}
