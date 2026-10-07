package utp.edu.pe.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class ApiApplication {

	public static void main(String[] args) {
		if (args.length == 1 && "--create-admin".equals(args[0])) {
			var context = new SpringApplicationBuilder(ApiApplication.class)
					.web(WebApplicationType.NONE)
					.run("--parkflow.cli.create-admin=true");
			int exitCode = SpringApplication.exit(context);
			System.exit(exitCode);
			return;
		}
		SpringApplication.run(ApiApplication.class, args);
	}

}
