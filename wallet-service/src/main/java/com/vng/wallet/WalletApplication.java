package com.vng.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the whole microservice.
 *
 * When you run the app, Java calls main(), which tells Spring to:
 *   1. scan this package for our classes (controllers, services, etc.)
 *   2. wire them together automatically
 *   3. start an embedded web server (Tomcat) listening for HTTP requests.
 *
 * @SpringBootApplication is the "magic" annotation that turns all of that on.
 */
@SpringBootApplication
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
